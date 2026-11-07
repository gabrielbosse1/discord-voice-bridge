package com.discordvoicebridge.discord;

import com.discordvoicebridge.audio.AudioPipeline;

import com.sun.jna.ptr.PointerByReference;

import tomp2p.opuswrapper.Opus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
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
 * (20 ms @ 48 kHz). Senders that use longer opus frames - high-bitrate
 * music bots emit 40-120 ms frames - are rejected with
 * {@code OPUS_BUFFER_TOO_SMALL} and are silent in the MC group. This class
 * decodes <b>every</b> sender's post-DAVE opus itself with
 * {@code frame_size=5760} (the 120 ms opus maximum), splits the result into
 * 20 ms stereo chunks, time-spreads them through a 20 ms mixer, and hands the
 * summed mono to the SVC side. Long-frame senders become audible and mix
 * correctly with normal 20 ms speakers.
 *
 * <h2>Health report</h2>
 * Every 5 s one INFO line per active sender:
 * {@code pk} = post-DAVE packet rate, {@code own} = our opus decodes
 * (ok/err), {@code jda} = frames JDA's own 20 ms decoder managed,
 * {@code vol} = window average level, {@code pitch} = dominant pitch
 * (autocorrelation, every 0.5 s; 0/absent = unvoiced), and a state:
 * SPEAKING / MUTED-DTX (packets flowing but &lt; -45 dB) / SILENT (no packets
 * for &gt; 10 s) / +OPUS-ERR (our decoder failing more than it succeeds).
 */
final class AudioHealth implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int MAX_FRAME_SAMPLES = 5760; // 120 ms, the opus maximum
    private static final int CHUNK = 960;              // 20 ms per channel
    private static final int MAX_QUEUE = 16;           // 320 ms of backlog before dropping
    private static final int MAX_USERS = 32;
    private static final long STALE_MS = 600_000;      // drop a decoder idle for 10 min

    private final Consumer<short[]> sink;
    private final Map<Long, UserState> users = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    private long lastSinkWarnMs;

    private static final class UserState {
        // Set under the user lock before the native decoder is freed; an
        // in-flight onPacket re-checks it in its lock so a teardown/eviction
        // can never free a pointer that is about to be used (native crash).
        boolean dead;
        PointerByReference dec;
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
    }

    AudioHealth(Consumer<short[]> sink) {
        this.sink = sink;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dvc-audio-health");
            t.setDaemon(true);
            return t;
        });
        // One throw (an SVC encoder/channel error, e.g. mid-teardown) must not
        // kill the scheduled tasks: scheduleAtFixedRate suppresses a task
        // forever after an exception, which would mean permanent silence with
        // no diagnostics. mix warnings are throttled so a persistent failure
        // does not turn into a 50 warn/s log bomb.
        timer.scheduleAtFixedRate(() -> {
            try {
                mixTick();
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastSinkWarnMs > 5_000) {
                    lastSinkWarnMs = now;
                    LOGGER.warn("AudioHealth mix tick failed (repeats throttled)", t);
                }
            }
        }, 20, 20, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(() -> {
            try {
                report();
            } catch (Throwable t) {
                LOGGER.warn("AudioHealth report failed", t);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * One post-DAVE opus packet from JDA (receive thread). The native decode runs
     * inside the user lock: evictStale/close free the decoder under that same
     * lock and flip {@link UserState#dead}, so a packet that is still in flight
     * during a teardown can never decode into a freed native pointer.
     */
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
                st.carry = Arrays.copyOf(st.carry, avail);
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
                st = new UserState();
                st.dec = dec;
                users.put(userId, st);
            }
        }
        return st;
    }

    /** 20 ms tick: sum one chunk per sender, downmix to mono, push to SVC. */
    private void mixTick() {
        short[] acc = new short[CHUNK * CHANNELS];
        boolean any = false;
        for (UserState st : users.values()) {
            short[] chunk;
            synchronized (st) {
                chunk = st.queue.pollFirst();
            }
            if (chunk == null) {
                continue;
            }
            any = true;
            for (int i = 0; i < acc.length; i++) {
                int s = (int) acc[i] + chunk[i];
                acc[i] = (short) Math.max(-32768, Math.min(32767, s));
            }
            accumulateVolume(st, chunk);
            maybePitch(st, chunk);
        }
        if (!any) {
            Arrays.fill(acc, (short) 0); // keep the SVC stream continuous, as JDA's combined did
        }
        Consumer<short[]> s = sink;
        if (s != null) {
            s.accept(AudioPipeline.stereoToMono(acc));
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
        int speaking = 0, muted = 0, silent = 0, failing = 0, total = users.size();
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
                    speaking++;
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
                    total, speaking, muted, silent, failing, sb);
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
    }
}
