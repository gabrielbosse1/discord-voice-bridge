package com.discordvoicebridge.discord;

import com.discordvoicebridge.audio.AudioPipeline;

import com.sun.jna.ptr.PointerByReference;

import tomp2p.opuswrapper.Opus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Discord -&gt; MC audio path and the per-sender health monitor.
 *
 * <h2>Why a custom decoder/mixer</h2>
 * JDA's {@code Decoder.decodeFromOpus} hardcodes {@code frame_size=960}
 * (20 ms @ 48 kHz). Senders that use longer opus frames - high-bitrate music
 * bots emit 40-120 ms frames - are rejected with {@code OPUS_BUFFER_TOO_SMALL}
 * and are silent in the MC group. This class decodes <b>every</b> sender's
 * post-DAVE opus itself with {@code frame_size=5760} (the 120 ms opus
 * maximum), splits the result into 20 ms chunks with exact sample carry-over,
 * and a 20 ms timer mixes them into one mono frame per tick.
 *
 * <h2>Output</h2>
 * One shared (unmodified) mono frame goes to the group channel. Players with
 * a personal mute or volume for one or more Discord senders instead get their
 * own channel, where each sender is weighted individually:
 * {@code weight} is 0 when that player muted that sender, otherwise that
 * player's volume for that sender (default 1.0). The weights are per
 * (player, Discord user), so muting someone affects only that player - never
 * the rest of the group, which keeps hearing the unmodified mix.
 *
 * <h2>Health report</h2>
 * Every 5 s one INFO line per active sender: {@code pk} = post-DAVE packet
 * rate, {@code own} = our opus decodes (ok/err), {@code jda} = frames JDA's own
 * 20 ms decoder managed, {@code vol} = window average level, {@code pitch} =
 * dominant pitch (autocorrelation, every 0.5 s; absent = unvoiced), and a
 * state: SPEAKING / MUTED-DTX (&lt; -45 dB) / SILENT (no packets &gt; 10 s) /
 * +OPUS-ERR.
 */
final class DiscordMixer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int MAX_FRAME_SAMPLES = 5760; // 120 ms, the opus maximum
    private static final int CHUNK = 960;              // 20 ms per channel
    private static final int MAX_QUEUE = 16;           // 320 ms of backlog before dropping
    private static final int MAX_USERS = 32;
    private static final long STALE_MS = 600_000;      // drop a decoder idle for 10 min

    /** One output stream (the group sink, or a player's personal sink). */
    private static final class Entry {
        final UUID player;
        final Consumer<short[]> sink;

        Entry(UUID player, Consumer<short[]> sink) {
            this.player = player;
            this.sink = sink;
        }
    }

    private static final class UserState {
        // Set under the user lock before the native decoder is freed; an
        // in-flight onPacket re-checks it in its lock so a teardown/eviction
        // can never free a pointer that is about to be used (native crash).
        boolean dead;
        PointerByReference dec;
        final long senderId;
        final short[] decBuf = new short[MAX_FRAME_SAMPLES * CHANNELS];
        final ArrayDeque<short[]> queue = new ArrayDeque<>();
        // Carry-over of not-yet-chunked interleaved samples so a frame whose
        // duration is not a multiple of 20 ms (music bots send ~116 ms frames)
        // does not lose samples: dropping them would speed the audio up and
        // make it choppy. Leftovers are prepended to the next decode.
        short[] carry = new short[0];
        int carryLen;
        // 5 s window, reset by report()
        long packets, jdaDecoded, decOk, decErr;
        int lastErr;
        long sqSum;
        int sqN;
        int pitchHz;
        int chunkSincePitch;
        long lastPktMs;
        int ssrc;

        UserState(long senderId) {
            this.senderId = senderId;
        }
    }

    private final Entry defaultEntry;
    private final Map<UUID, Entry> playerEntries = new ConcurrentHashMap<>();
    private final Map<Long, UserState> users = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    private long lastSinkWarnMs;
    // Live per-sender state, refreshed each mix tick; the /dvc menu reads it
    // to show who is actually producing sound.
    private final Map<Long, Boolean> speaking = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastPkt = new ConcurrentHashMap<>();
    // Per (player, Discord user) personal overrides: a mute (weight 0) and a
    // volume (0.0-2.0). Default weight for an unset pair is 1.0 (normal).
    private final Map<UUID, Set<Long>> perMuted = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Float>> perVolume = new ConcurrentHashMap<>();

    DiscordMixer(Consumer<short[]> sink) {
        this.defaultEntry = new Entry(null, sink);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dvc-discord-mixer");
            t.setDaemon(true);
            return t;
        });
        // One throw (an SVC encoder/channel error, e.g. mid-teardown) must not
        // kill the scheduled tasks: scheduleAtFixedRate suppresses a task
        // forever after an exception, which would mean permanent silence with
        // no diagnostics. Sink warnings are throttled so a persistent failure
        // does not turn into a log bomb.
        timer.scheduleAtFixedRate(() -> {
            try {
                mixTick();
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastSinkWarnMs > 5_000) {
                    lastSinkWarnMs = now;
                    LOGGER.warn("DiscordMixer mix tick failed (repeats throttled)", t);
                }
            }
        }, 20, 20, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(() -> {
            try {
                report();
            } catch (Throwable t) {
                LOGGER.warn("DiscordMixer report failed", t);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** One post-DAVE opus packet from JDA (receive thread). */
    void onPacket(long userId, int ssrc, byte[] opus) {
        UserState st = state(userId);
        if (st == null) {
            return;
        }
        synchronized (st) {
            if (st.dead) {
                return;
            }
            st.packets++;
            st.lastPktMs = System.currentTimeMillis();
            st.ssrc = ssrc;
            if (opus == null || opus.length == 0) {
                return;
            }
            int n;
            try {
                n = Opus.INSTANCE.opus_decode(st.dec, opus, opus.length,
                        ShortBuffer.wrap(st.decBuf), MAX_FRAME_SAMPLES, 0);
            } catch (Throwable t) {
                n = -999; // native throw: treat as a decode error, never kill the receive thread
            }
            if (n <= 0) {
                st.decErr++;
                st.lastErr = n;
                return;
            }
            st.decOk++;
            int fresh = n * CHANNELS; // interleaved stereo samples just decoded
            int avail = st.carryLen + fresh;
            if (avail > st.carry.length) {
                st.carry = java.util.Arrays.copyOf(st.carry, avail);
            }
            System.arraycopy(st.decBuf, 0, st.carry, st.carryLen, fresh);
            st.carryLen = avail;
            int chunkSize = CHUNK * CHANNELS;
            int off = 0;
            while (st.carryLen - off >= chunkSize) {
                short[] chunk = new short[chunkSize];
                System.arraycopy(st.carry, off, chunk, 0, chunkSize);
                st.queue.add(chunk);
                while (st.queue.size() > MAX_QUEUE) {
                    st.queue.pollFirst(); // bounded backlog: drop the oldest
                }
                off += chunkSize;
            }
            // Keep any sub-20 ms remainder (frames whose length is not a multiple
            // of 20 ms) for the next decode instead of discarding it.
            int left = st.carryLen - off;
            if (off > 0 && left > 0) {
                System.arraycopy(st.carry, off, st.carry, 0, left);
            }
            st.carryLen = left;
        }
    }

    /** JDA's own 20 ms decoder handled a frame for this user (receive thread). */
    void onJdaDecoded(long userId) {
        UserState st = users.get(userId);
        if (st != null) {
            synchronized (st) {
                st.jdaDecoded++;
            }
        }
    }

    private UserState state(long userId) {
        UserState st = users.get(userId);
        if (st != null) {
            return st;
        }
        synchronized (users) {
            if (users.size() >= MAX_USERS) {
                evictStale();
            }
            st = users.get(userId);
            if (st == null && users.size() < MAX_USERS) {
                IntBuffer err = IntBuffer.allocate(1);
                PointerByReference dec = Opus.INSTANCE.opus_decoder_create(SAMPLE_RATE, CHANNELS, err);
                if (dec == null || err.get(0) != 0) {
                    return null;
                }
                st = new UserState(userId);
                st.dec = dec;
                users.put(userId, st);
            }
        }
        return st;
    }

    /** 20 ms tick: sum one chunk per sender and emit to the group + personal sinks. */
    private void mixTick() {
        short[] groupMono = new short[CHUNK];
        short[][] chunks = new short[MAX_USERS][];
        long[] senders = new long[MAX_USERS];
        int collected = 0;
        for (UserState st : users.values()) {
            short[] stereoChunk;
            synchronized (st) {
                stereoChunk = st.queue.pollFirst();
            }
            if (stereoChunk == null) {
                continue;
            }
            short[] mono = monoOf(stereoChunk);
            for (int i = 0; i < CHUNK; i++) {
                groupMono[i] = clip(groupMono[i] + mono[i]);
            }
            if (collected < chunks.length) {
                chunks[collected] = mono;
                senders[collected] = st.senderId;
                collected++;
            }
            accumulateVolume(st, stereoChunk);
            maybePitch(st, stereoChunk);
        }
        refreshLive();
        safeAccept(defaultEntry.sink, groupMono);
        for (Entry e : playerEntries.values()) {
            short[] personal = new short[CHUNK];
            for (int k = 0; k < collected; k++) {
                float w = weightFor(e.player, senders[k]);
                if (w == 0f) {
                    continue; // this player muted this sender
                }
                short[] c = chunks[k];
                for (int i = 0; i < CHUNK; i++) {
                    personal[i] = clip(personal[i] + (int) (c[i] * w));
                }
            }
            safeAccept(e.sink, personal);
        }
    }

    /** Effective per-(player, sender) weight: 0 if muted, else volume (1.0 default). */
    private float weightFor(UUID player, long senderId) {
        if (player == null) {
            return 1f; // group sink: never weighted
        }
        Set<Long> m = perMuted.get(player);
        if (m != null && m.contains(senderId)) {
            return 0f;
        }
        Map<Long, Float> v = perVolume.get(player);
        if (v != null) {
            Float f = v.get(senderId);
            if (f != null) {
                return f;
            }
        }
        return 1f;
    }

    private void safeAccept(Consumer<short[]> sink, short[] mono) {
        try {
            sink.accept(mono);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastSinkWarnMs > 5_000) {
                lastSinkWarnMs = now;
                LOGGER.warn("DiscordMixer sink failed (repeats throttled)", t);
            }
        }
    }

    private static short[] monoOf(short[] stereo) {
        short[] m = new short[CHUNK];
        for (int i = 0; i < CHUNK; i++) {
            m[i] = (short) (((int) stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        }
        return m;
    }

    private static short clip(int v) {
        return (short) Math.max(-32768, Math.min(32767, v));
    }

    private void refreshLive() {
        long now = System.currentTimeMillis();
        for (UserState st : users.values()) {
            long lp;
            synchronized (st) {
                lp = st.lastPktMs;
            }
            if (lp > 0) {
                lastPkt.put(st.senderId, lp);
                speaking.put(st.senderId, now - lp < 2_000);
            } else {
                speaking.remove(st.senderId);
            }
        }
    }

    private void accumulateVolume(UserState st, short[] stereo) {
        for (int i = 0; i < CHUNK; i++) {
            int v = ((int) stereo[i * 2] + stereo[i * 2 + 1]) / 2;
            st.sqSum += (long) v * v;
            st.sqN++;
        }
    }

    /** Dominant pitch by normalized autocorrelation, 50-500 Hz, every 0.5 s. */
    private void maybePitch(UserState st, short[] stereo) {
        if (++st.chunkSincePitch < 25) {
            return;
        }
        st.chunkSincePitch = 0;
        double energy = 0;
        double[] x = new double[CHUNK];
        for (int i = 0; i < CHUNK; i++) {
            x[i] = ((int) stereo[i * 2] + stereo[i * 2 + 1]) / 2;
            energy += x[i] * x[i];
        }
        if (energy < CHUNK * 400.0) { // below ~-32 dB RMS: unvoiced, skip the search
            st.pitchHz = 0;
            return;
        }
        int bestLag = 0;
        double best = 0;
        for (int lag = 96; lag <= CHUNK; lag++) {
            double c = 0;
            for (int i = 0; i + lag < CHUNK; i++) {
                c += x[i] * x[i + lag];
            }
            c /= energy;
            if (c > best) {
                best = c;
                bestLag = lag;
            }
        }
        st.pitchHz = best > 0.35 ? (int) (SAMPLE_RATE / bestLag) : 0;
    }

    /** 5 s report: one line per sender + a summary of states. */
    private void report() {
        long now = System.currentTimeMillis();
        int speakingCount = 0, muted = 0, silent = 0, failing = 0, total = users.size();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, UserState> e : users.entrySet()) {
            UserState st = e.getValue();
            synchronized (st) {
                if (st.sqN == 0 && st.packets == 0 && st.jdaDecoded == 0) {
                    continue; // idle placeholder
                }
                double rate = st.packets / 5.0;
                double volDb = st.sqN > 0
                        ? 20 * Math.log10(Math.sqrt((double) st.sqSum / st.sqN) / 32768.0)
                        : Double.NEGATIVE_INFINITY;
                long since = now - st.lastPktMs;
                boolean fail = st.decErr > st.decOk && st.packets > 5;
                String state;
                if (since > 10_000) {
                    state = "SILENT " + (since / 1000) + "s";
                    silent++;
                } else if (volDb <= -45) {
                    state = "MUTED-DTX";
                    muted++;
                } else {
                    state = "SPEAKING";
                    speakingCount++;
                }
                if (fail) {
                    failing++;
                }
                if (sb.length() > 0) {
                    sb.append("  |  ");
                }
                sb.append(e.getKey())
                        .append(" pk=").append(String.format("%.1f", rate)).append("/s")
                        .append(" own=").append(st.decOk).append('/').append(st.decErr)
                        .append(st.decErr > 0 ? " err=" + st.lastErr : "")
                        .append(" jda=").append(st.jdaDecoded)
                        .append(" vol=").append(volDb == Double.NEGATIVE_INFINITY ? "-inf"
                                : String.format("%.1f", volDb)).append("dB")
                        .append(st.pitchHz > 0 ? " pitch=" + st.pitchHz + "Hz" : "")
                        .append(" [").append(state);
                if (fail) {
                    sb.append(" +OPUS-ERR");
                }
                sb.append(']');
                // reset the window
                st.packets = st.jdaDecoded = st.decOk = st.decErr = 0;
                st.lastErr = 0;
                st.sqSum = 0;
                st.sqN = 0;
            }
        }
        evictStale();
        if (total == 0) {
            LOGGER.info("Audio health: no senders in the channel");
        } else {
            LOGGER.info("Audio health ({} sender(s), speaking={}, muted={}, silent={}, failing={}): {}",
                    total, speakingCount, muted, silent, failing, sb);
        }
    }

    private void evictStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, UserState> e : users.entrySet()) {
            UserState st = e.getValue();
            if (st.lastPktMs > 0 && now - st.lastPktMs > STALE_MS) {
                // Remove first, then free under the user lock: an in-flight
                // onPacket blocks on the lock, sees dead=true and bails before
                // it can decode into the freed decoder.
                if (users.remove(e.getKey(), st)) {
                    synchronized (st) {
                        st.dead = true;
                        if (st.dec != null) {
                            Opus.INSTANCE.opus_decoder_destroy(st.dec);
                        }
                    }
                }
            }
        }
    }

    // ----- live per-sender state (for the /dvc menu) -----

    /** True while a sender produced a packet within the last 2 s. */
    public boolean isSpeaking(long discordUserId) {
        return Boolean.TRUE.equals(speaking.get(discordUserId));
    }

    /** Timestamp (ms) of the sender's last packet, or 0 if none this session. */
    public long lastPacketMs(long discordUserId) {
        Long v = lastPkt.get(discordUserId);
        return v != null ? v : 0;
    }

    // ----- per-(player, Discord user) personal overrides -----

    /**
     * Wires a player's personal sink (their own SVC static channel), applying
     * their remembered per-sender mute/volume weights.
     */
    public void registerPlayer(UUID player, Consumer<short[]> sink) {
        playerEntries.put(player, new Entry(player, sink));
    }

    public void unregisterPlayer(UUID player) {
        playerEntries.remove(player);
    }

    public boolean isPlayerRegistered(UUID player) {
        return playerEntries.containsKey(player);
    }

    /** True if this player has any personal mute/volume (needs their own channel). */
    public boolean needsPersonalChannel(UUID player) {
        Set<Long> m = perMuted.get(player);
        if (m != null && !m.isEmpty()) {
            return true;
        }
        Map<Long, Float> v = perVolume.get(player);
        return v != null && !v.isEmpty();
    }

    /** Discord user ids this player has a personal mute/volume for (for the menu). */
    public Set<Long> overriddenSenders(UUID player) {
        Set<Long> out = new HashSet<>();
        Set<Long> m = perMuted.get(player);
        if (m != null) {
            out.addAll(m);
        }
        Map<Long, Float> v = perVolume.get(player);
        if (v != null) {
            out.addAll(v.keySet());
        }
        return out;
    }

    /** Mutes/unmutes one Discord sender for this player only. */
    public void setPlayerSenderMuted(UUID player, long senderId, boolean muted) {
        if (muted) {
            perMuted.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(senderId);
        } else {
            Set<Long> m = perMuted.get(player);
            if (m != null) {
                m.remove(senderId);
                if (m.isEmpty()) {
                    perMuted.remove(player);
                }
            }
        }
    }

    public boolean isPlayerSenderMuted(UUID player, long senderId) {
        Set<Long> m = perMuted.get(player);
        return m != null && m.contains(senderId);
    }

    /** Sets this player's volume (0.0-2.0, 1.0 = normal) for one Discord sender. */
    public void setPlayerSenderVolume(UUID player, long senderId, float volume) {
        float v = Math.max(0.0f, Math.min(2.0f, volume));
        if (v <= 1.0f) {
            Map<Long, Float> m = perVolume.get(player);
            if (m != null) {
                m.remove(senderId);
                if (m.isEmpty()) {
                    perVolume.remove(player);
                }
            }
        } else {
            perVolume.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(senderId, v);
        }
    }

    public float getPlayerSenderVolume(UUID player, long senderId) {
        Map<Long, Float> v = perVolume.get(player);
        return v != null && v.get(senderId) != null ? v.get(senderId) : 1.0f;
    }

    /** Clears a player's personal mute and volume for one Discord sender. */
    public void clearPlayerSender(UUID player, long senderId) {
        setPlayerSenderMuted(player, senderId, false);
        setPlayerSenderVolume(player, senderId, 1.0f);
    }

    @Override
    public void close() {
        timer.shutdownNow();
        for (UserState st : users.values()) {
            synchronized (st) {
                st.dead = true;
                if (st.dec != null) {
                    Opus.INSTANCE.opus_decoder_destroy(st.dec);
                }
            }
        }
        users.clear();
        playerEntries.clear();
        perMuted.clear();
        perVolume.clear();
        speaking.clear();
        lastPkt.clear();
    }
}
