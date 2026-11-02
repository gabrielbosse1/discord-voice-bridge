package com.discordvoicebridge.svc;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.Lang;
import com.discordvoicebridge.audio.AudioPipeline;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.Group.Type;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import com.google.common.collect.ImmutableMultimap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat side of the bridge.
 * <ul>
 *   <li>Creates the persistent group from config, name kept verbatim.</li>
 *   <li>Spawns a {@link FakePlayer} for the bot (custom name + skin URL).
 *       {@code PlayerListMixin} keeps it out of the TAB list.</li>
 *   <li>Discord -> Minecraft: PCM is Opus-encoded via {@link VoicechatServerApi#createEncoder()}
 *       and sent through an {@link EntityAudioChannel} bound to the fake player, so it sounds
 *       like a real player inside the group.</li>
 *   <li>Minecraft -> Discord: {@link PlayerAudioListener}s decode group members' Opus mic
 *       packets to PCM and hand them to the Discord bot.</li>
 * </ul>
 * Uses the public {@code de.maxhenkel.voicechat.api} surface; the only exception
 * is {@link #setRawName}, because the public API truncates group names to 16 chars.
 */
public class SvcBridge implements SvcVoicechatPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private static final UUID GROUP_UUID = UUID.nameUUIDFromBytes("discord_voice_bridge:group".getBytes(StandardCharsets.UTF_8));
    private static final UUID CHANNEL_UUID = UUID.nameUUIDFromBytes("discord_voice_bridge:bot-audio".getBytes(StandardCharsets.UTF_8));

    private final DiscordVoiceBridgeConfig config;
    private VoicechatServerApi api;
    private Group botGroup;
    private EntityAudioChannel channel;
    private OpusEncoder encoder;
    private OpusDecoder decoder;
    private FakePlayer fakePlayer;
    private final ConcurrentHashMap<UUID, PlayerAudioListener> listeners = new ConcurrentHashMap<>();

    public SvcBridge(DiscordVoiceBridgeConfig config) {
        this.config = config;
    }

    @Override
    public void onServerStarted(MinecraftServer server) {
        api = CommonCompatibilityManager.INSTANCE.getServerApi();
        if (api == null) {
            LOGGER.error("Simple Voice Chat API is not available yet");
            return;
        }

        // --- persistent group (created once, then reused across restarts) ---
        botGroup = api.getGroups().stream()
                .filter(g -> g.getId().equals(GROUP_UUID))
                .findFirst().orElse(null);
        if (botGroup == null) {
            Type type = "ISOLATED".equalsIgnoreCase(config.getGroupType()) ? Type.ISOLATED : Type.NORMAL;
            Group.Builder builder = api.groupBuilder()
                    .setId(GROUP_UUID)
                    .setName(config.getGroupName())
                    .setPassword("")
                    .setPersistent(true)
                    .setType(type);
            setRawName(builder, config.getGroupName());
            botGroup = builder.build();
            LOGGER.info("Created SVC group {} (id={}, type={})", config.getGroupName(), GROUP_UUID, type);
        }

        encoder = api.createEncoder();
        decoder = api.createDecoder();

        // --- fake player: custom display name + skin head, positioned at world spawn ---
        ServerLevel overworld = server.overworld();
        GameProfile profile = new GameProfile(DiscordVoiceBridgeMod.BOT_UUID, config.getBotDisplayName());
        String skinUrl = config.getBotSkinUrl();
        if (skinUrl != null && !skinUrl.isBlank()) {
            String textures = "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"}}}";
            Property texturesProperty = new Property("textures",
                    Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)));
            profile = new GameProfile(DiscordVoiceBridgeMod.BOT_UUID, config.getBotDisplayName(),
                    new PropertyMap(ImmutableMultimap.of("textures", texturesProperty)));
        }
        fakePlayer = FakePlayer.get(overworld, profile);
        var spawn = overworld.getLevelData().getRespawnData().pos();
        fakePlayer.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        if (!overworld.addFreshEntity(fakePlayer)) {
            LOGGER.warn("Fake player could not be spawned into the overworld");
        }

        // --- Discord -> Minecraft audio channel (positional, like a real player) ---
        channel = api.createEntityAudioChannel(CHANNEL_UUID, api.fromEntity(fakePlayer));
        if (botGroup.getType() == Type.ISOLATED) {
            channel.setFilter(player -> {
                VoicechatConnection conn = api.getConnectionOf(player);
                return conn != null && conn.isInGroup() && conn.getGroup().getId().equals(GROUP_UUID);
            });
        }

        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        if (bot != null) {
            bot.setMonoPcmHandler(mono -> {
                byte[] opus = encoder.encode(mono);
                channel.send(opus);
            });
        }

        refreshPlayers(server);
    }

    @Override
    public void onServerStopped(MinecraftServer server) {
        for (UUID uuid : listeners.keySet()) {
            if (api != null) {
                api.unregisterAudioListener(uuid);
            }
        }
        listeners.clear();
        if (fakePlayer != null) {
            fakePlayer.discard();
            fakePlayer = null;
        }
    }

    @Override
    public void refreshPlayers(MinecraftServer server) {
        if (api == null || botGroup == null) {
            return;
        }
        Set<UUID> inGroup = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VoicechatConnection conn = api.getConnectionOf(player.getUUID());
            if (conn != null && conn.isInGroup() && conn.getGroup().getId().equals(GROUP_UUID)) {
                inGroup.add(player.getUUID());
                ensureListener(player.getUUID());
            }
        }
        listeners.keySet().removeIf(uuid -> {
            if (inGroup.contains(uuid)) {
                return false;
            }
            api.unregisterAudioListener(uuid);
            return true;
        });
    }

    private void ensureListener(UUID uuid) {
        if (listeners.containsKey(uuid)) {
            return;
        }
        PlayerAudioListener listener = api.playerAudioListenerBuilder()
                .setPlayer(uuid)
                .setPacketListener(packet -> {
                    if (!(packet instanceof MicrophonePacket)) {
                        return;
                    }
                    MicrophonePacket mic = (MicrophonePacket) packet;
                    byte[] opus = mic.getOpusEncodedData();
                    if (opus == null || opus.length == 0) {
                        return;
                    }
                    // The listener only receives packets from `uuid` (setPlayer), so the
                    // group check below covers the sender as well.
                    VoicechatConnection conn = api.getConnectionOf(uuid);
                    if (conn == null || !conn.isInGroup()
                            || !conn.getGroup().getId().equals(GROUP_UUID)) {
                        return;
                    }
                    short[] mono = decoder.decode(opus);
                    if (mono == null || mono.length == 0) {
                        return;
                    }
                    short[] stereo = AudioPipeline.monoToStereo(mono);
                    DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
                    if (bot != null) {
                        bot.playToDiscord(stereo);
                    }
                })
                .build();
        if (api.registerAudioListener(listener)) {
            listeners.put(uuid, listener);
        }
    }

    /**
     * SVC's public group builder runs {@code convertGroupName} on the name
     * that is set: it strips control characters, trims leading whitespace and
     * truncates to 16 characters. The config name has to be preserved verbatim
     * (spaces, emojis, Unicode), so the builder's {@code name} field is
     * overwritten right before {@code build()}. This is the only reflection
     * used in the mod; everything else goes through the public API.
     */
    private static void setRawName(Group.Builder builder, String rawName) {
        try {
            java.lang.reflect.Field field = builder.getClass().getDeclaredField("name");
            field.setAccessible(true);
            field.set(builder, rawName);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not preserve unsanitized group name; SVC may truncate it: {}", e.getMessage());
        }
    }
}
