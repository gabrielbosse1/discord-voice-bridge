package com.discordvoicebridge.svc;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.audio.AudioPipeline;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.Group.Type;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

import com.mojang.authlib.GameProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple Voice Chat side of the bridge.
 * <ul>
 *   <li>Creates the persistent group from config, name kept verbatim.</li>
 *   <li>Spawns an invisible spectator {@link FakePlayer} at world spawn for the bot.
 *       It is not in the TAB list (never goes through PlayerList), is excluded from
 *       the sleep count (vanilla skips spectators), cannot be hit, move, sleep, ride
 *       or act (FakePlayer hooks + spectator), and is invisible to normal players
 *       (Invisibility effect).</li>
 *   <li>Discord -> Minecraft: PCM is Opus-encoded via {@link VoicechatServerApi#createEncoder()}
 *       and sent through an {@link EntityAudioChannel} bound to the fake player, so it
 *       sounds like a player inside the group. The channel distance is very large so
 *       it is audible across the overworld, not just near spawn.</li>
 *   <li>Minecraft -> Discord: each bridge-group member's own microphone is captured
 *       through the {@link MicrophonePacketEvent} (subscribed by {@link DvcVoicechatPlugin})
 *       and the decoded PCM is handed to the Discord bot.</li>
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
    private FakePlayer botPlayer;

    // Diagnostics so both directions can be confirmed from the log.
    private final AtomicLong micDecodedFrames = new AtomicLong();
    private volatile long lastMicLogMs = 0;
    private final AtomicLong discordToMcFrames = new AtomicLong();
    private volatile long lastDiscordLogMs = 0;
    private volatile long lastMembershipLogMs = 0;
    /** Last bridge-group member the bot was following (position is kept on it). */
    private volatile UUID followTarget;

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
        String password = config.getGroupPassword();
        boolean wantsPassword = password != null && !password.isBlank();
        botGroup = api.getGroups().stream()
                .filter(g -> g.getId().equals(GROUP_UUID))
                .findFirst().orElse(null);
        // hasPassword() is a null check, not an empty check: setPassword("") would
        // lock the group behind a blank password, so only pass a password when set.
        if (botGroup != null && botGroup.hasPassword() != wantsPassword) {
            // Old build created the group with a blank password; drop it and
            // recreate with the configured password state.
            LOGGER.info("Recreating SVC group {} to apply the configured password", GROUP_UUID);
            api.removeGroup(GROUP_UUID);
            botGroup = null;
        }
        if (botGroup == null) {
            Type type = "ISOLATED".equalsIgnoreCase(config.getGroupType()) ? Type.ISOLATED : Type.NORMAL;
            Group.Builder builder = api.groupBuilder()
                    .setId(GROUP_UUID)
                    .setName(config.getGroupName())
                    .setPersistent(true)
                    .setType(type);
            if (wantsPassword) {
                builder.setPassword(password);
            }
            setRawName(builder, config.getGroupName());
            botGroup = builder.build();
            LOGGER.info("Created SVC group {} (id={}, type={}, password={})",
                    config.getGroupName(), GROUP_UUID, type, wantsPassword);
        }

        encoder = api.createEncoder();
        decoder = api.createDecoder();

        // --- Discord -> Minecraft audio: fake player, invisible spectator at spawn ---
        ServerLevel overworld = server.overworld();
        GameProfile profile = new GameProfile(DiscordVoiceBridgeMod.BOT_UUID, config.getBotDisplayName());
        botPlayer = FakePlayer.get(overworld, profile);
        var spawn = overworld.getLevelData().getRespawnData().pos();
        botPlayer.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        // Spectator: excluded from the sleep count (vanilla skips spectators),
        // cannot sleep or interact. The setGameMode broadcast only carries
        // UPDATE_GAME_MODE entries, which the client ignores for a UUID it never
        // saw in ADD_PLAYER, so the bot cannot leak into the TAB list.
        botPlayer.setGameMode(GameType.SPECTATOR);
        // Invisible to normal players (spectators still see it). The entity must
        // still be sent to clients: the SVC client anchors player-sound audio on
        // the tracked entity via ClientLevel.getPlayerByUUID(). No ambient
        // particles so the invisibility is not even a faint shimmer.
        botPlayer.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 1_000_000_000, 0, false, false));
        if (!overworld.addFreshEntity(botPlayer)) {
            LOGGER.warn("Bot player could not be spawned into the overworld");
        }

        channel = api.createEntityAudioChannel(CHANNEL_UUID, api.fromEntity(botPlayer));
        // A player-sound channel only reaches players within its distance of the
        // entity. A very large distance keeps full volume at any position in the
        // overworld (broadcast scans the online player list, not chunks, so it is
        // cheap), so bridge members hear Discord without walking to the spawn point.
        channel.setDistance(1_000_000f);
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
                long n = discordToMcFrames.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now - lastDiscordLogMs > 10_000) {
                    lastDiscordLogMs = now;
                    LOGGER.info("Discord to MC: pushed {} opus frames to the bot player so far", n);
                }
            });
        }

        LOGGER.info("MC to Discord mic capture is event-driven (MicrophonePacketEvent); hearing "
                + "Discord uses the bot player '{}' at spawn", profile.name());
    }

    @Override
    public void onServerStopped(MinecraftServer server) {
        if (botPlayer != null) {
            botPlayer.discard();
            botPlayer = null;
        }
        channel = null;
        followTarget = null;
    }

    /**
     * Called for every microphone packet on the server (from {@link DvcVoicechatPlugin}).
     * When the speaker is in the bridge group, their own voice is decoded and forwarded to
     * Discord. Runs on SVC's single voice-process thread, so the decoder is not shared
     * across threads. {@code PlayerAudioListener} cannot capture a speaker's own voice - it
     * only delivers audio a player receives from others - so this event is the correct hook.
     */
    public void onMicPacket(MicrophonePacketEvent event) {
        if (decoder == null) {
            return;
        }
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        if (bot == null) {
            return;
        }
        VoicechatConnection conn = event.getSenderConnection();
        if (conn == null || !conn.isInGroup()
                || !conn.getGroup().getId().equals(GROUP_UUID)) {
            return; // not a bridge-group member: leave their mic to SVC
        }
        MicrophonePacket mic = event.getPacket();
        byte[] opus = mic.getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            return;
        }
        short[] mono = decoder.decode(opus);
        if (mono == null || mono.length == 0) {
            return;
        }
        bot.playToDiscord(AudioPipeline.monoToStereo(mono));
        long n = micDecodedFrames.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastMicLogMs > 10_000) {
            lastMicLogMs = now;
            LOGGER.info("MC to Discord: decoded {} opus frames from bridge-group members so far", n);
        }
    }

    @Override
    public void refreshPlayers(MinecraftServer server) {
        if (api == null || botGroup == null) {
            return;
        }
        updateBotPosition(server);
        long now = System.currentTimeMillis();
        if (now - lastMembershipLogMs <= 15_000) {
            return;
        }
        lastMembershipLogMs = now;
        // Throttled diagnostic: what group (if any) each online player is in, so a
        // "can't be heard" report can be checked against live membership.
        StringBuilder sb = new StringBuilder("Bridge group membership view (group=").append(GROUP_UUID).append("):");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VoicechatConnection conn = api.getConnectionOf(player.getUUID());
            if (conn == null) {
                sb.append(" [").append(player.getGameProfile().name()).append(": no voicechat connection]");
            } else if (!conn.isInGroup()) {
                sb.append(" [").append(player.getGameProfile().name()).append(": not in a group]");
            } else if (conn.getGroup().getId().equals(GROUP_UUID)) {
                sb.append(" [").append(player.getGameProfile().name()).append(": IN BRIDGE GROUP]");
            } else {
                sb.append(" [").append(player.getGameProfile().name()).append(": in other group ")
                        .append(conn.getGroup().getId()).append(']');
            }
        }
        LOGGER.info(sb.toString());
    }

    /**
     * Keeps the bot player next to the bridge-group member it follows, every tick.
     * The SVC client plays a player's audio anchored on the tracked entity it has
     * locally, and the server only sends entities within the viewer's render
     * distance - so a bot parked at spawn is inaudible to a member far away.
     * Following the member keeps the bot tracked (full volume, any position or
     * dimension) without affecting anything: the bot has no physics (FakePlayer
     * ticks are a no-op) and is a spectator, so the move is invisible and inert.
     */
    private void updateBotPosition(MinecraftServer server) {
        FakePlayer bot = botPlayer;
        if (bot == null || bot.isRemoved()) {
            return;
        }
        ServerPlayer target = null;
        UUID targetId = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VoicechatConnection conn = api.getConnectionOf(player.getUUID());
            if (conn != null && conn.isInGroup() && conn.getGroup().getId().equals(GROUP_UUID)) {
                target = player;
                targetId = player.getUUID();
                break;
            }
        }
        if (target == null) {
            followTarget = null;
            return; // nobody in the group: bot stays where it is
        }
        if (bot.level() != target.level()) {
            // Dimension change: teleport the bot to the member there.
            bot.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(),
                    java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class),
                    bot.getYRot(), bot.getXRot(), true);
            followTarget = targetId;
            LOGGER.info("Bot player followed {} to their dimension", target.getGameProfile().name());
            return;
        }
        if (!targetId.equals(followTarget)) {
            // New member to follow: snap once, then track.
            bot.teleportTo(target.getX(), target.getY(), target.getZ());
            followTarget = targetId;
            return;
        }
        // Same member, same dimension: follow smoothly. setPos sends no teleport
        // packet, so the client just interpolates the normal movement.
        if (bot.distanceToSqr(target.getX(), target.getY(), target.getZ()) > 4f * 4f) {
            bot.setPos(target.getX(), target.getY(), target.getZ());
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
