package com.discordvoicebridge.discord;

import club.minnced.discord.jdave.DaveDecryptor;
import club.minnced.discord.jdave.manager.DaveSessionManager;

import net.dv8tion.jda.api.audio.dave.DaveSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Makes Discord senders that transmit legacy (unencrypted) voice audible on a
 * DAVE/E2EE connection - and keeps working when Discord migrates them to
 * E2EE later - per user ID from the config.
 *
 * Discord now enforces the DAVE protocol: libdave keeps one decryptor per
 * sender and only accepts unencrypted frames ("pass through") for senders
 * negotiated to protocol version 0. Legacy senders (older clients, older
 * music bots) are often migrated to E2EE by Discord over time, so a fixed
 * flag is wrong in one direction or the other:
 * <ul>
 *   <li>flag ON on a migrated sender: libdave passes the still-encrypted
 *       bytes straight through, JDA's opus decoder rejects them and the
 *       sender is silent;</li>
 *   <li>flag OFF on a plaintext sender: libdave drops every frame
 *       ("frame is not encrypted and pass through is disabled") before JDA
 *       sees it.</li>
 * </ul>
 *
 * The flag is therefore switched automatically per user by observing JDA's
 * per-user frames (1 s tick):
 * <ul>
 *   <li>no packet seen and nothing decoded for 5 ticks -&gt; flag ON (a
 *       plaintext sender's frames are discarded before JDA, so their only
 *       trace is absence; a silent user getting the flag is harmless);</li>
 *   <li>packets seen but nothing decoded for 3 ticks -&gt; flag OFF (it is
 *       passing raw ciphertext: the sender is now encrypted and decrypts
 *       normally);</li>
 *   <li>anything decoded -&gt; keep the current flag.</li>
 * </ul>
 * Non-listed users are never touched.
 *
 * The native flag is switched with the public
 * {@code DaveDecryptor.prepareTransition(0)} (on) and
 * {@code prepareTransition(negotiatedVersion)} (off). The per-user decryptor
 * map and the negotiated version are private, reached by class reference plus
 * field name - no package-name strings, so the jdave jar relocation cannot
 * break this. All accesses run under the DAVE manager's own monitor (its
 * methods are synchronized on it), because {@code prepareTransition}
 * re-attaches the native key ratchet and must not run while the receive
 * thread decrypts.
 */
public final class LegacyAudioPassthrough implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private static final int QUIET_TICKS_TO_ENABLE = 5;
    private static final int FAILING_TICKS_TO_DISABLE = 3;

    private final Set<Long> userIds;
    private volatile DaveSessionManager manager;
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    private final Map<Long, Integer> seenThisTick = new ConcurrentHashMap<>();
    private final Map<Long, Integer> decodedThisTick = new ConcurrentHashMap<>();
    private ScheduledExecutorService poller;

    /** Per-user observation state and desired/current native flag. */
    private static final class State {
        int quietTicks;
        int failingTicks;
        // Start OFF: the native default for a v1-negotiated decryptor is already
        // "key ratchet" (no pass-through), so an actively streaming E2EE user is
        // never touched at boot. Forcing the flag ON first would feed a migrated
        // sender's ciphertext into JDA's opus decoder and corrupt that
        // decoder's state for seconds.
        boolean flagOn = false;
        Boolean nativeFlag = false;
        long seenTotal;
        long decodedTotal;
    }

    public LegacyAudioPassthrough(List<String> configIds) {
        Set<Long> ids = new HashSet<>();
        for (String s : configIds) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring invalid legacyUserIds entry: {}", s);
            }
        }
        this.userIds = Collections.unmodifiableSet(ids);
    }

    /** Stores the DAVE session manager of the created JDA audio session. Called from the factory. */
    public void bindSession(DaveSession session) {
        try {
            for (Class<?> c = session.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == DaveSessionManager.class) {
                        f.setAccessible(true);
                        manager = (DaveSessionManager) f.get(session);
                        // New voice connection = new decryptors in the native
                        // default state (no pass-through); forget old state.
                        for (State s : states.values()) {
                            s.nativeFlag = false;
                            s.flagOn = false;
                            s.quietTicks = 0;
                            s.failingTicks = 0;
                        }
                        LOGGER.info("Legacy audio passthrough armed for {} configured user id(s)", userIds.size());
                        return;
                    }
                }
            }
            LOGGER.warn("Could not find the DAVE session manager in the audio session; legacy passthrough disabled");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not arm legacy audio passthrough: {}", e.getMessage());
        }
    }

    /** JDA received a post-DAVE frame from this user (may still be raw ciphertext). */
    public void notePacket(long userId) {
        if (!userIds.contains(userId)) {
            return;
        }
        seenThisTick.merge(userId, 1, Integer::sum);
    }

    /** JDA successfully opus-decoded a frame from this user (audio is flowing). */
    public void noteDecoded(long userId) {
        if (!userIds.contains(userId)) {
            return;
        }
        decodedThisTick.merge(userId, 1, Integer::sum);
    }

    public void start() {
        if (userIds.isEmpty()) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dvc-legacy-audio");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        DaveSessionManager mgr = manager;
        if (mgr == null) {
            return;
        }
        for (Long id : userIds) {
            Integer seenBoxed = seenThisTick.remove(id);
            Integer decodedBoxed = decodedThisTick.remove(id);
            int seen = seenBoxed == null ? 0 : seenBoxed;
            int decoded = decodedBoxed == null ? 0 : decodedBoxed;

            State st = states.computeIfAbsent(id, k -> new State());
            st.seenTotal += seen;
            st.decodedTotal += decoded;
            if (seen == 0 && decoded == 0) {
                st.quietTicks++;
                st.failingTicks = 0;
            } else if (decoded > 0) {
                st.quietTicks = 0;
                st.failingTicks = 0;
            } else {
                st.failingTicks++;
                st.quietTicks = 0;
            }

            boolean want = st.flagOn;
            if (st.quietTicks == QUIET_TICKS_TO_ENABLE) {
                want = true;
            } else if (st.failingTicks == FAILING_TICKS_TO_DISABLE) {
                want = false;
            }
            st.flagOn = want;

            if (!st.nativeFlag.equals(want)) {
                if (applyNativeFlag(mgr, id, want)) {
                    st.nativeFlag = want;
                    LOGGER.info("Legacy audio passthrough {} for Discord user {} (packets seen: {}, decoded: {})",
                            want ? "enabled" : "disabled", id, st.seenTotal, st.decodedTotal);
                }
            }
        }
    }

    private boolean applyNativeFlag(DaveSessionManager mgr, long userId, boolean on) {
        try {
            // The DAVE manager's methods are synchronized on the manager
            // itself; prepareTransition re-attaches the native key ratchet,
            // so it must not run off-lock while the receive thread decrypts.
            synchronized (mgr) {
                Field mapField = DaveSessionManager.class.getDeclaredField("decryptors");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, DaveDecryptor> decryptors = (Map<Long, DaveDecryptor>) mapField.get(mgr);
                DaveDecryptor decryptor = decryptors.get(userId);
                if (decryptor == null) {
                    return false; // sender not in the channel yet; retry next tick
                }
                int protocolVersion = 1;
                try {
                    Field v = DaveSessionManager.class.getDeclaredField("currentProtocolVersion");
                    v.setAccessible(true);
                    protocolVersion = v.getInt(mgr);
                } catch (ReflectiveOperationException ignored) {
                    // v1 is the E2EE version in practice; only used for "off"
                }
                decryptor.prepareTransition(on ? 0 : Math.max(1, protocolVersion));
                return true;
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("DaveSessionManager layout changed; legacy passthrough disabled: {}", e.getMessage());
            return false;
        } catch (Throwable t) {
            // Transient conflict with the DAVE thread is fine; the next tick
            // retries (the native flag only changes on state transitions).
            return false;
        }
    }

    @Override
    public void close() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        manager = null;
    }
}
