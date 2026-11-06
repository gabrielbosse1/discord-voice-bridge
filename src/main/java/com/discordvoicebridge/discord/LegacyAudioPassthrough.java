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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Makes Discord senders that still transmit legacy (unencrypted) voice audible
 * on a DAVE/E2EE connection, per user ID from the config.
 *
 * Discord now enforces the DAVE protocol: libdave keeps one decryptor per
 * sender and only accepts unencrypted frames ("pass through") for senders
 * negotiated to protocol version 0. Some senders (older clients, older music
 * bots) are negotiated to version 1 yet still transmit plaintext, so their
 * frames are dropped with "frame is not encrypted and pass through is
 * disabled".
 *
 * {@code DaveDecryptor.prepareTransition(0)} is the public jdave call that
 * flips a single decryptor into pass-through mode; it is idempotent and is
 * re-applied every second, because an MLS epoch transition re-prepares the
 * *same* decryptor instance with the negotiated version (turning the flag
 * back off) and a leaving user gets a fresh instance on rejoin. The per-user
 * decryptor map is private, so it is reached with two type-based field
 * lookups (no class-name strings, so jar relocation cannot break this). Only
 * the configured user IDs are touched; everyone else keeps full E2EE
 * decryption.
 */
public final class LegacyAudioPassthrough implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private final Set<Long> userIds;
    private volatile DaveSessionManager manager;
    private ScheduledExecutorService poller;

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

    public void start() {
        if (userIds.isEmpty()) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dvc-legacy-audio");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::applyPassThrough, 2, 1, TimeUnit.SECONDS);
    }

    private void applyPassThrough() {
        DaveSessionManager mgr = manager;
        if (mgr == null) {
            return;
        }
        try {
            Field f = DaveSessionManager.class.getDeclaredField("decryptors");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, DaveDecryptor> decryptors = (Map<Long, DaveDecryptor>) f.get(mgr);
            for (Long id : userIds) {
                DaveDecryptor current = decryptors.get(id);
                if (current != null) {
                    // Idempotent; re-applied after every epoch transition and
                    // for decryptors created by a late join.
                    current.prepareTransition(0);
                }
            }
        } catch (NoSuchFieldException e) {
            LOGGER.warn("DaveSessionManager layout changed; legacy passthrough disabled: {}", e.getMessage());
        } catch (Throwable t) {
            // The map is a plain HashMap mutated by JDA's DAVE thread without
            // a shared lock; a transient read conflict is fine, the next tick
            // retries.
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
