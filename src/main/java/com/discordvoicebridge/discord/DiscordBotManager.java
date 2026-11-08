package com.discordvoicebridge.discord;

import com.discordvoicebridge.Lang;
import com.discordvoicebridge.audio.AudioPipeline;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Headless Discord bot. Joins the configured voice channel and bridges PCM audio
 * in both directions:
 * <ul>
 *   <li>Discord -> Minecraft: every sender's post-DAVE opus is decoded and mixed
 *       by {@link DiscordMixer} (which also logs the per-sender health report) and
 *       the summed mono is handed to the SVC side via the callback set with
 *       {@link #setMonoPcmHandler(Consumer)}.</li>
 *   <li>Minecraft -> Discord: stereo PCM frames are enqueued via
 *       {@link #playToDiscord(short[])} and drained by the JDA send handler.</li>
 * </ul>
 * No slash commands or message listeners are registered (voice-state intent only).
 */
public class DiscordBotManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private final DiscordVoiceBridgeConfig config;
    private final AudioPipeline.StereoFrameQueue sendQueue = new AudioPipeline.StereoFrameQueue(100);
    private LegacyAudioPassthrough legacyPassthrough;
    // Diagnostic: per-sender counts of post-DAVE frames vs successfully
    // opus-decoded frames, logged every 10 s, so JDA's user-less
    // "Decoder failed to decode" line can be attributed to a sender.
    private final java.util.Map<Long, long[]> audioDiag = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastAudioDiagLogMs = 0;
    // First captured post-DAVE payload per user (once per boot): tells us
    // whether the bytes that reach opus are decrypted audio or still a raw
    // DAVE/transport envelope (leading 0xff...).
    private final java.util.Map<Long, byte[]> firstPayload = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Consumer<short[]> monoPcmHandler = mono -> { };
    private volatile boolean running = false;
    private volatile JDA jda;
    private final java.util.concurrent.atomic.AtomicLong providedFrames = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastProvidedLogMs = 0;

    // DAVE recovery: a sender that keeps delivering post-DAVE frames but never
    // opus-decodes it has a stuck MLS key-generation in the native libdave
    // session ("no valid cryptor" / MISSING_KEY_RATCHET) - a known upstream
    // libdave/JDA limitation. The native DAVE/MLS session is created once per
    // AudioConnection and REUSED across JDA in-band reconnects, so the stuck
    // state survives them (verified live: the sender stayed at 0 decoded after a
    // close+open). The only thing that re-syncs it is a brand-new JDA, which
    // builds a fresh AudioConnection -> fresh DAVE/MLS session that processes the
    // current group epoch from scratch. We force that, capped + cooled-down so
    // it cannot loop and disrupt both directions unboundedly.
    private static final int DAVE_BAD_WINDOW_MIN_POST = 25;      // post-DAVE frames per 10 s to call a sender "active"
    private static final int DAVE_BAD_WINDOWS_TO_RESTART = 3;    // consecutive active-but-undecoded 10 s windows (~30 s)
    private static final long DAVE_RESTART_COOLDOWN_MS = 90_000;
    private static final int DAVE_MAX_RESTARTS = 2;
    private final java.util.Map<Long, Integer> daveBadWindows = new java.util.concurrent.ConcurrentHashMap<>();
    // Senders that decoded at least once in this JDA session. A sender that was
    // healthy and then went active-but-undecoded is a REGRESSION (a real MLS
    // epoch-transition gap) that a fresh DAVE/MLS session heals. A sender that
    // was never decodable (e.g. a music bot whose E2EE key-generation libdave
    // cannot build) is NOT a regression: a restart does not help it (verified
    // live) and would only drop every other speaker, so it is left alone.
    private final java.util.Set<Long> daveEverHealthy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger daveRestarts = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastDaveRestartMs = 0;
    private volatile boolean daveCapLogged = false;
    private final java.util.concurrent.ScheduledExecutorService reconnecter =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dvc-dave-reconnect");
                t.setDaemon(true);
                return t;
            });
    private final Object reconnectLock = new Object();
    private volatile boolean sessionDescLogged = false;
    // Per-session audio path + health monitor: decodes every sender's post-DAVE
    // opus with a 120 ms frame size (JDA's 20 ms decoder drops long music frames),
    // mixes all senders, emits one mono frame per 20 ms (plus per-player frames
    // with personal mute/volume) and reports sender health every 5 s.
    private volatile DiscordMixer mixer;
    // Bumped by a real stop() and by each DAVE restart; the delayed restart
    // start() only proceeds if it is still the current session, so a server
    // shutdown or a newer restart cannot be clobbered by a stale resume.
    private final java.util.concurrent.atomic.AtomicLong instanceId = new java.util.concurrent.atomic.AtomicLong();

    public DiscordBotManager(DiscordVoiceBridgeConfig config) {
        this.config = config;
    }

    /** SVC installs this: receives one mono PCM frame (960 samples = 20 ms @ 48 kHz) per Discord packet. */
    public void setMonoPcmHandler(Consumer<short[]> handler) {
        this.monoPcmHandler = handler != null ? handler : mono -> { };
    }

    /** Called by SVC whenever a group member speaks (stereo 48 kHz 16-bit shorts). */
    public void playToDiscord(short[] stereoPcm) {
        if (stereoPcm == null || stereoPcm.length == 0) {
            return;
        }
        sendQueue.offer(AudioPipeline.stereoToFrame(stereoPcm));
    }

    /** Diagnostic: count one post-DAVE opus packet (decoded=0) or one opus success (decoded=1). */
    private void countAudioDiag(long userId, int decoded) {
        long[] c = audioDiag.computeIfAbsent(userId, k -> new long[2]);
        if (decoded > 0) {
            c[1] += decoded;      // frames successfully opus-decoded
            daveEverHealthy.add(userId); // DAVE is working for this sender right now
        } else {
            c[0]++;               // frames that reached the opus decoder
        }
        long now = System.currentTimeMillis();
        if (now - lastAudioDiagLogMs > 10_000) {
            lastAudioDiagLogMs = now;
            StringBuilder sb = new StringBuilder("Audio per-sender (opus-decoded / post-DAVE frames):");
            for (java.util.Map.Entry<Long, long[]> e : audioDiag.entrySet()) {
                sb.append(String.format(" | %s: %d/%d", e.getKey(), e.getValue()[1], e.getValue()[0]));
            }
            LOGGER.info(sb.toString());
            if (!sessionDescLogged) {
                sessionDescLogged = true;
                LegacyAudioPassthrough p = legacyPassthrough;
                if (p != null) {
                    LOGGER.info("DAVE session: {}", p.describeSession());
                }
            }
            evaluateDaveRecovery();
            // keep only the recent window so the map does not grow per speaker
            audioDiag.clear();
        }
    }

    /**
     * DAVE recovery: a sender that is actively delivering post-DAVE frames but
     * never opus-decodes it has a stuck MLS key-generation in the native libdave
     * session. After a few consecutive such 10 s windows, restart the whole JDA
     * so a brand-new DAVE/MLS session re-syncs the generation. A healthy window
     * (nobody stuck) resets the restart budget.
     */
    private void evaluateDaveRecovery() {
        int offenders = 0;
        for (java.util.Map.Entry<Long, long[]> e : audioDiag.entrySet()) {
            long userId = e.getKey();
            long post = e.getValue()[0];   // post-DAVE frames this window
            long dec = e.getValue()[1];    // opus-decoded this window
            if (post >= DAVE_BAD_WINDOW_MIN_POST && dec == 0) {
                // Only a REGRESSION is recoverable: this sender decoded earlier
                // this session and now stopped. A never-decodable sender (a
                // bot libdave can't decrypt) is left alone - a restart does not
                // help it and would drop every other speaker.
                if (!daveEverHealthy.contains(userId)) {
                    continue;
                }
                Integer bad = daveBadWindows.merge(userId, 1, Integer::sum);
                if (bad != null && bad >= DAVE_BAD_WINDOWS_TO_RESTART) {
                    offenders++;
                }
            } else {
                daveBadWindows.remove(userId);
            }
        }
        if (offenders > 0) {
            scheduleDaveRestart(offenders);
        } else if (!daveBadWindows.isEmpty()) {
            // No active failure right now: clear the budget so a future,
            // unrelated stuck generation still gets a recovery chance.
            daveBadWindows.clear();
            daveRestarts.set(0);
        }
    }

    private void scheduleDaveRestart(int offenders) {
        synchronized (reconnectLock) {
            long now = System.currentTimeMillis();
            if (now - lastDaveRestartMs < DAVE_RESTART_COOLDOWN_MS) {
                return; // still cooling down; the counter keeps accruing and fires again after
            }
            if (daveRestarts.get() >= DAVE_MAX_RESTARTS) {
                if (!daveCapLogged) {
                    daveCapLogged = true;
                    LOGGER.warn("DAVE recovery: restart cap ({}) reached; a fresh DAVE/MLS session did not clear "
                            + "the stuck key-generation, so this sender needs an upstream libdave/JDA fix or a "
                            + "full server restart. Offender(s): {}", DAVE_MAX_RESTARTS, offenders);
                }
                return;
            }
            lastDaveRestartMs = now;
            int n = daveRestarts.incrementAndGet();
            daveBadWindows.clear(); // re-arm after the restart
            LOGGER.warn("DAVE key-generation stuck for {} active sender(s) over ~{} s; restarting JDA to build "
                    + "a fresh DAVE/MLS session ({} of {})", offenders, DAVE_BAD_WINDOWS_TO_RESTART * 10, n, DAVE_MAX_RESTARTS);
        }
        reconnecter.execute(this::restartJda);
    }

    /**
     * Shuts the whole bot down and starts it again. A JDA rebuild creates a new
     * AudioConnection, whose constructor builds a fresh native DAVE/MLS session
     * that re-processes the current group epoch from scratch - the only thing
     * that clears the stuck libdave key-generation (an in-band voice reconnect
     * reuses the existing DAVE session and was verified not to help).
     */
    private void restartJda() {
        if (jda == null) {
            return;
        }
        long token = instanceId.get(); // do NOT bump: this is our own teardown, not a real stop
        try {
            teardownBot();           // running=false, JDA.shutdownNow(), passthrough.close()
        } catch (Exception e) {
            LOGGER.error("DAVE recovery: shutdown failed: {}", e.getMessage(), e);
        }
        reconnecter.schedule(() -> {
            // Only resume if nobody stopped us (server shutdown / newer restart)
            // in the teardown window.
            if (instanceId.get() != token) {
                return;
            }
            try {
                start();             // rebuilds JDA + passthrough, running=true
            } catch (Exception e) {
                LOGGER.error("DAVE recovery: JDA restart failed: {}", e.getMessage(), e);
                running = true;      // don't get stuck not-running
            }
        }, 4, TimeUnit.SECONDS);
    }

    /**
     * Start is non-blocking: JDA has no ready callback, only the blocking
     * awaitReady(), so the wait and channel connect run on a daemon thread.
     */
    public void start() {
        if (running) {
            return;
        }
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            LOGGER.warn(Lang.get("discord_voice_bridge.error.missing_token"));
            return;
        }
        String channelId = config.getVoiceChannelId();
        if (channelId == null || channelId.isBlank()) {
            LOGGER.warn(Lang.get("discord_voice_bridge.error.missing_channel"));
            return;
        }

        legacyPassthrough = new LegacyAudioPassthrough(config.getLegacyUserIds());

        running = true;
        LOGGER.info(Lang.get("discord_voice_bridge.startup.starting"));
        // Fresh JDA session: forget per-session DAVE recovery state (the
        // "ever healthy" set and the restart budget belong to one session).
        daveEverHealthy.clear();
        daveBadWindows.clear();
        daveRestarts.set(0);
        daveCapLogged = false;
        sessionDescLogged = false;
        DiscordMixer old = mixer;
        if (old != null) {
            old.close();
        }
        // Reads the volatile handler on every frame: SVC installs its sink
        // independently of JDA start/stop order.
        mixer = new DiscordMixer(mono -> monoPcmHandler.accept(mono));
        // Discord enforces E2EE/DAVE encrypted voice: the audio module must
        // carry a DAVE session factory (jdave) or the voice server closes the
        // connection with "E2EE/DAVE protocol required" in a reconnect loop.
        // Use create() (NOT createLight): the receive path resolves each
        // speaker via getUserById(), which is fed by the member cache.
        // createLight sets MemberCachePolicy.NONE, so every packet from a
        // speaking user hits a null User and is dropped (silence). create()
        // with GUILD_VOICE_STATES caches in-voice members (VOICE.or(OWNER)),
        // which is all we need and needs no privileged intent.
        LegacyAudioPassthrough passthrough = legacyPassthrough;
        JDA bot = JDABuilder.create(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(
                        new JDaveSessionFactory() {
                            @Override
                            public net.dv8tion.jda.api.audio.dave.DaveSession createDaveSession(
                                    net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks callbacks,
                                    long selfUserId, long channelId) {
                                net.dv8tion.jda.api.audio.dave.DaveSession s =
                                        super.createDaveSession(callbacks, selfUserId, channelId);
                                if (passthrough != null) {
                                    passthrough.bindSession(s);
                                }
                                return DaveSessionTapper.wrap(s);
                            }
                        }))
                .build();
        jda = bot;
        if (passthrough != null) {
            passthrough.start();
        }
        Thread worker = new Thread(() -> {
            try {
                bot.awaitReady();
                connectVoice(channelId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop();
            } catch (Exception e) {
                LOGGER.error(Lang.get("discord_voice_bridge.error.bot_start_failed", e.getMessage()), e);
                stop();
            }
        }, "discord-voice-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectVoice(String channelId) {
        JDA self = jda;
        if (self == null || !running) {
            return;
        }

        VoiceChannel channel = null;
        Guild guild = null;
        for (Guild g : self.getGuilds()) {
            VoiceChannel vc = g.getVoiceChannelCache().getElementById(channelId);
            if (vc != null) {
                guild = g;
                channel = vc;
                break;
            }
        }
        if (channel == null) {
            LOGGER.error(Lang.get("discord_voice_bridge.error.channel_not_found", channelId));
            stop();
            return;
        }

        AudioManager manager = guild.getAudioManager();
        manager.setReceivingHandler(new AudioReceiveHandler() {
            @Override
            public boolean canReceiveCombined() {
                // Audio is mixed in DiscordMixer (own 120 ms-capable decoder + 20 ms
                // timer), so JDA's fixed-20 ms combined stream is not consumed.
                // canReceiveUser stays true: LegacyAudioPassthrough observes
                // JDA's per-user decode success (noteDecoded) via handleUserAudio.
                return false;
            }

            @Override
            public boolean canReceiveUser() {
                return true;
            }

            @Override
            public boolean canReceiveEncoded() {
                return true;
            }

            @Override
            public void handleEncodedAudio(net.dv8tion.jda.api.audio.OpusPacket packet) {
                long userId = packet.getUserId();
                LegacyAudioPassthrough p = legacyPassthrough;
                if (p != null) {
                    p.notePacket(userId);
                }
                countAudioDiag(userId, 0);
                DiscordMixer h = mixer;
                if (h != null) {
                    h.onPacket(userId, packet.getSSRC(), packet.getOpusAudio());
                }
                byte[] payload = packet.getOpusAudio();
                if (payload != null && payload.length > 0) {
                    if (firstPayload.putIfAbsent(userId,
                            java.util.Arrays.copyOf(payload, Math.min(payload.length, 16))) == null) {
                        byte[] sample = firstPayload.get(userId);
                        StringBuilder hex = new StringBuilder();
                        for (byte b : sample) {
                            hex.append(String.format("%02x", b));
                        }
                        LOGGER.info("Audio first payload for user {}: ssrc={} len={} head={}",
                                userId, packet.getSSRC(), payload.length, hex);
                    }
                }
            }

            @Override
            public void handleUserAudio(UserAudio userAudio) {
                LegacyAudioPassthrough p = legacyPassthrough;
                if (p != null) {
                    try {
                        p.noteDecoded(Long.parseLong(userAudio.getUser().getId()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                try {
                    long userId = Long.parseLong(userAudio.getUser().getId());
                    countAudioDiag(userId, 1);
                    DiscordMixer h = mixer;
                    if (h != null) {
                        h.onJdaDecoded(userId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });
        manager.setSendingHandler(new AudioSendHandler() {
            @Override
            public boolean canProvide() {
                return true;
            }

            @Override
            public ByteBuffer provide20MsAudio() {
                byte[] frame = sendQueue.poll();
                if (frame != null) {
                    long n = providedFrames.incrementAndGet();
                    long now = System.currentTimeMillis();
                    if (now - lastProvidedLogMs > 10_000) {
                        lastProvidedLogMs = now;
                        LOGGER.info("MC to Discord: JDA sent {} frames to the voice connection so far", n);
                    }
                }
                return frame == null ? null : ByteBuffer.wrap(frame);
            }
        });
        manager.setAutoReconnect(true);
        manager.openAudioConnection(channel);

        LOGGER.info(Lang.get("discord_voice_bridge.startup.started", config.getGroupName()));
    }

    public void stop() {
        // A real stop (server shutdown / admin disable): tear down and bump the
        // instance token so any pending DAVE-recovery resume aborts.
        teardownBot();
        instanceId.incrementAndGet();
    }

    private void teardownBot() {
        running = false;
        JDA self = jda;
        jda = null;
        if (self != null) {
            try {
                self.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        LegacyAudioPassthrough passthrough = legacyPassthrough;
        legacyPassthrough = null;
        if (passthrough != null) {
            passthrough.close();
        }
        DiscordMixer health = mixer;
        mixer = null;
        if (health != null) {
            health.close();
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ----- Discord voice-channel introspection (for the /dvc menu) -----

    /** One user currently in the configured Discord voice channel. */
    public record SenderInfo(long id, String username, String displayName, boolean muted, boolean bot) {
    }

    /**
     * All users in the configured voice channel. Display name = nickname when
     * set, else the global display name, else the username. {@code muted} is
     * the Discord-side mute (not the personal bridge mute). Empty when the bot
     * is not running or the channel is unknown.
     */
    public List<SenderInfo> voiceUsers() {
        List<SenderInfo> out = new ArrayList<>();
        JDA self = jda;
        if (self == null) {
            return out;
        }
        String channelId = config.getVoiceChannelId();
        java.util.List<net.dv8tion.jda.api.entities.Guild> guilds = self.getGuilds();
        if (guilds.isEmpty()) {
            return out;
        }
        for (net.dv8tion.jda.api.entities.GuildVoiceState vs : guilds.get(0).getVoiceStates()) {
            if (vs.getChannel() == null || !vs.getChannel().getId().equals(channelId)) {
                continue;
            }
            net.dv8tion.jda.api.entities.Member member = vs.getMember();
            net.dv8tion.jda.api.entities.User user = member.getUser();
            String nick = member.getNickname();
            String global = user.getGlobalName();
            String display = (nick != null && !nick.isBlank()) ? nick
                    : (global != null && !global.isBlank()) ? global : user.getName();
            out.add(new SenderInfo(user.getIdLong(), user.getName(), display, vs.isMuted(), user.isBot()));
        }
        return out;
    }

    /** Sorted unique user names (not nicknames) for brigadier autocomplete. */
    public List<String> voiceUsernames() {
        List<String> out = new ArrayList<>();
        for (SenderInfo s : voiceUsers()) {
            out.add(s.username());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Resolves a user name (case-insensitive, exact) to a Discord user id, or
     * null. Used by /dvc to target a sender.
     */
    public Long resolveUsername(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (SenderInfo s : voiceUsers()) {
            if (s.username().equalsIgnoreCase(name.trim())) {
                return s.id();
            }
        }
        return null;
    }

    /** Display name (nickname, else global name, else username) for a user id, or the id if unknown. */
    public String displayName(long discordUserId) {
        for (SenderInfo s : voiceUsers()) {
            if (s.id() == discordUserId) {
                return s.displayName();
            }
        }
        return String.valueOf(discordUserId);
    }

    /** Whether the sender produced a packet within the last 2 s. */
    public boolean isSpeaking(long discordUserId) {
        DiscordMixer m = mixer;
        return m != null && m.isSpeaking(discordUserId);
    }

    public long lastPacketMs(long discordUserId) {
        DiscordMixer m = mixer;
        return m != null ? m.lastPacketMs(discordUserId) : 0;
    }

    // ----- per-player output (personal mute/volume) -----

    public void registerPlayer(UUID player, Consumer<short[]> sink) {
        DiscordMixer m = mixer;
        if (m != null) {
            m.registerPlayer(player, sink);
        }
    }

    public void unregisterPlayer(UUID player) {
        DiscordMixer m = mixer;
        if (m != null) {
            m.unregisterPlayer(player);
        }
    }

    public boolean isPlayerRegistered(UUID player) {
        DiscordMixer m = mixer;
        return m != null && m.isPlayerRegistered(player);
    }

    /** True if this player's mute/volume differs from the group default. */
    public boolean needsPersonalChannel(UUID player) {
        DiscordMixer m = mixer;
        return m != null && m.needsPersonalChannel(player);
    }

    /** Discord user ids this player has a personal mute/volume for (for the menu). */
    public java.util.Set<Long> overriddenSenders(UUID player) {
        DiscordMixer m = mixer;
        return m != null ? m.overriddenSenders(player) : java.util.Set.of();
    }

    public void setPlayerSenderMuted(UUID player, long senderId, boolean muted) {
        DiscordMixer m = mixer;
        if (m != null) {
            m.setPlayerSenderMuted(player, senderId, muted);
        }
    }

    public boolean isPlayerSenderMuted(UUID player, long senderId) {
        DiscordMixer m = mixer;
        return m != null && m.isPlayerSenderMuted(player, senderId);
    }

    public void setPlayerSenderVolume(UUID player, long senderId, float volume) {
        DiscordMixer m = mixer;
        if (m != null) {
            m.setPlayerSenderVolume(player, senderId, volume);
        }
    }

    public float getPlayerSenderVolume(UUID player, long senderId) {
        DiscordMixer m = mixer;
        return m != null ? m.getPlayerSenderVolume(player, senderId) : 1.0f;
    }

    /** Clears this player's personal mute and volume for one Discord sender. */
    public void clearPlayerSender(UUID player, long senderId) {
        DiscordMixer m = mixer;
        if (m != null) {
            m.clearPlayerSender(player, senderId);
        }
    }
}
