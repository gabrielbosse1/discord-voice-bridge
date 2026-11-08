package com.discordvoicebridge.svc;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.audio.AudioPipeline;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.Group.Type;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple Voice Chat side of the bridge.
 * <ul>
 *   <li>Creates the persistent group from config, name kept verbatim.</li>
 *   <li>Discord -> Minecraft: PCM is Opus-encoded via {@link VoicechatServerApi#createEncoder()}
 *       and sent through a {@link StaticAudioChannel} whose targets are exactly the
 *       bridge-group members (kept in sync every tick). The client plays a static sound
 *       as full-volume, non-positional audio, so there is no bot entity to spawn,
 *       track or follow, and nobody outside the group hears anything.</li>
 *   <li>Minecraft -> Discord: each bridge-group member's own microphone is captured
 *       through the {@link MicrophonePacketEvent} (subscribed by {@link DvcVoicechatPlugin})
 *       and the decoded PCM is handed to the Discord bot. The event is only tapped,
 *       never cancelled, so the member's normal group voice chat is unaffected.</li>
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
    private StaticAudioChannel channel;
    private OpusEncoder encoder;
    private OpusDecoder decoder;
    /** Bridge-group members the shared channel currently sends to (server thread only). */
    private final java.util.Set<UUID> channelTargets = ConcurrentHashMap.newKeySet();
    /**
     * Players with a personal mute/volume get their own static channel so the
     * override affects only them (the shared channel keeps feeding everyone
     * else with the unmodified mix). One encoder per player because SVC
     * encoders are stateful. The channel is kept (idle) after a player returns
     * to the shared mix - the API has no close(), and the count is bounded by
     * unique players who ever had a personal setting.
     */
    private final Map<UUID, Personal> personal = new ConcurrentHashMap<>();

    private static final class Personal {
        final StaticAudioChannel channel;
        final OpusEncoder encoder;
        volatile VoicechatConnection target;
        volatile boolean registered;

        Personal(StaticAudioChannel channel, OpusEncoder encoder) {
            this.channel = channel;
            this.encoder = encoder;
        }
    }

    // Diagnostics so both directions can be confirmed from the log.
    private final AtomicLong micDecodedFrames = new AtomicLong();
    private volatile long lastMicLogMs = 0;
    private final AtomicLong discordToMcFrames = new AtomicLong();
    private volatile long lastDiscordLogMs = 0;
    private volatile long lastMembershipLogMs = 0;

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

        // --- Discord -> Minecraft audio: static channel sent only to group members ---
        channel = api.createStaticAudioChannel(CHANNEL_UUID);
        // The targets are exactly the bridge-group members. SVC's static channel
        // skips players who are in an isolated group unless isolation is bypassed,
        // so an ISOLATED group config would otherwise receive nothing.
        channel.setBypassGroupIsolation(true);

        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        if (bot != null) {
            bot.setMonoPcmHandler(mono -> {
                if (channel == null) {
                    return;
                }
                byte[] opus = encoder.encode(mono);
                channel.send(opus);
                long n = discordToMcFrames.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now - lastDiscordLogMs > 10_000) {
                    lastDiscordLogMs = now;
                    LOGGER.info("Discord to MC: pushed {} opus frames to {} group member(s) so far",
                            n, channelTargets.size());
                }
            });
        }

        refreshPlayers(server);
        LOGGER.info("MC to Discord mic capture is event-driven (MicrophonePacketEvent); "
                + "hearing Discord is a static channel sent to group members only");
    }

    @Override
    public void onServerStopped(MinecraftServer server) {
        channel = null;
        channelTargets.clear();
        personal.clear();
    }

    /**
     * Called for every microphone packet on the server (from {@link DvcVoicechatPlugin}).
     * When the speaker is in the bridge group, their own voice is decoded and forwarded to
     * Discord. Runs on SVC's single voice-process thread, so the decoder is not shared
     * across threads. The event is never cancelled, so the member's normal group voice
     * chat keeps working. {@code PlayerAudioListener} cannot capture a speaker's own voice
     * - it only delivers audio a player receives from others - so this event is the
     * correct hook.
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
        updateChannelTargets(server);
        long now = System.currentTimeMillis();
        if (now - lastMembershipLogMs <= 15_000) {
            return;
        }
        lastMembershipLogMs = now;
        // Throttled diagnostic: what group (if any) each online player is in, so a
        // "can't be heard" report can be checked against live membership.
        StringBuilder sb = new StringBuilder("Bridge group membership view (group=").append(GROUP_UUID)
                .append("): hearing targets=").append(channelTargets.size()).append(":");
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
     * Keeps the shared channel's targets in sync with bridge-group membership, every
     * tick (server thread), and routes players who have a personal mute/volume to
     * their own channel instead (so the override affects only them). Nobody outside
     * the group hears anything; there is no bot entity to track.
     */
    private void updateChannelTargets(MinecraftServer server) {
        if (channel == null) {
            return;
        }
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            VoicechatConnection conn = api.getConnectionOf(uuid);
            boolean inGroup = conn != null && conn.isInGroup()
                    && conn.getGroup().getId().equals(GROUP_UUID);
            boolean needPersonal = inGroup && bot != null && bot.needsPersonalChannel(uuid);
            boolean inShared = channelTargets.contains(uuid);

            if (needPersonal) {
                if (inShared) {
                    channelTargets.remove(uuid);
                    if (conn != null) {
                        channel.removeTarget(conn);
                    }
                }
                ensurePersonal(uuid, conn, player, bot);
            } else {
                stopPersonal(uuid, player, bot);
                if (inGroup && !inShared) {
                    channelTargets.add(uuid);
                    if (conn != null) {
                        channel.addTarget(conn);
                    }
                    LOGGER.info("Discord audio now sent to {} (joined the bridge group)",
                            player.getGameProfile().name());
                } else if (!inGroup && inShared) {
                    channelTargets.remove(uuid);
                    if (conn != null) {
                        channel.removeTarget(conn);
                    }
                    LOGGER.info("Discord audio no longer sent to {} (left the bridge group)",
                            player.getGameProfile().name());
                }
            }
        }
    }

    /** Creates (once) and points a player's personal channel at them. */
    private void ensurePersonal(UUID uuid, VoicechatConnection conn, ServerPlayer player, DiscordBotManager bot) {
        Personal p = personal.get(uuid);
        if (p == null) {
            StaticAudioChannel ch = api.createStaticAudioChannel(personalChannelId(uuid));
            ch.setBypassGroupIsolation(true);
            p = new Personal(ch, api.createEncoder());
            personal.put(uuid, p);
        }
        if (bot != null && !p.registered) {
            Personal ref = p;
            bot.registerPlayer(uuid, mono -> {
                byte[] opus = ref.encoder.encode(mono);
                ref.channel.send(opus);
            });
            p.registered = true;
        }
        if (p.target != conn) {
            if (p.target != null) {
                p.channel.removeTarget(p.target);
            }
            if (conn != null) {
                p.channel.addTarget(conn);
            }
            p.target = conn;
            LOGGER.info("Personal Discord audio (mute/volume) now sent to {}", player.getGameProfile().name());
        }
    }

    /** Stops feeding a player's personal channel (kept idle for reuse). */
    private void stopPersonal(UUID uuid, ServerPlayer player, DiscordBotManager bot) {
        Personal p = personal.get(uuid);
        if (p == null) {
            return;
        }
        if (p.target != null) {
            p.channel.removeTarget(p.target);
            p.target = null;
        }
        if (p.registered && bot != null) {
            bot.unregisterPlayer(uuid);
            p.registered = false;
            LOGGER.info("Personal Discord audio for {} cleared (back to group volume)",
                    player.getGameProfile().name());
        }
    }

    private static UUID personalChannelId(UUID player) {
        return UUID.nameUUIDFromBytes(("discord_voice_bridge:personal:" + player).getBytes(StandardCharsets.UTF_8));
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
