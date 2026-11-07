package com.discordvoicebridge.discord;

import net.dv8tion.jda.api.audio.dave.DaveSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interceptor around the JDA DAVE session, installed by the DAVE session
 * factory so every {@code decrypt()} call passes through here.
 *
 * <p><b>It is the receive-side fix for senders that append RFC3550 RTP
 * padding.</b> Every DAVE frame must end with the {@code 0xFAFA} magic marker
 * (see libdave {@code InboundFrameProcessor.ParseFrame}); some senders - most
 * notably high-bitrate music bots - pad the RTP packet, and the padding ends up
 * <i>after</i> the DAVE frame once the transport layer is decrypted. JDA never
 * strips it, so libdave sees a buffer that no longer ends in {@code 0xFAFA} and
 * drops the frame ("not encrypted" / wrong key generation). This is the
 * wrapper-side fix from discord.js PR #11449 - the reference DAVE author is
 * explicit that the native lib is not the bug, the consumer library is.
 *
 * <p>The strip is self-verifying: it only fires when removing {@code pad}
 * trailing bytes (where {@code pad} is the final byte of the packet, per
 * RFC3550) lands exactly on a {@code 0xFAFA} marker. An unpadded DAVE frame
 * already ends in {@code 0xFA} (250), which is never a valid padding length, so
 * working senders are never touched.
 *
 * <p>It also logs the first few {@code decrypt()} calls per user (in/out sizes
 * and the trailing bytes) so the wire format can be inspected live.
 */
final class DaveSessionTapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");
    private static final int SAMPLES_PER_USER = 3;
    private static final int SUCCESS_SAMPLES_PER_USER = 8;
    private static final Map<Long, Integer> SAMPLED = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> SUCCESS_SAMPLES = new ConcurrentHashMap<>();

    private DaveSessionTapper() {
    }

    static DaveSession wrap(DaveSession session) {
        @SuppressWarnings("unchecked")
        DaveSession proxy = (DaveSession) Proxy.newProxyInstance(
                DaveSession.class.getClassLoader(),
                new Class<?>[]{DaveSession.class},
                new Handler(session));
        return proxy;
    }

    /**
     * Strips RFC3550 RTP padding from a post-transport DAVE payload in place by
     * lowering the buffer's limit. Returns the number of bytes removed (0 when
     * there is none). Safe to call on any frame: it only trims when doing so
     * yields a well-formed {@code 0xFAFA}-terminated DAVE frame.
     */
    private static int stripPadding(ByteBuffer in) {
        if (in == null) {
            return 0;
        }
        int position = in.position();
        int limit = in.limit();
        int len = limit - position;
        if (len < 4) { // cannot hold a marker + a padding-length byte
            return 0;
        }
        int pad = in.get(limit - 1) & 0xFF; // RFC3550: last octet = padding octet count
        if (pad == 0 || pad >= len - 2) { // invalid length (also rejects the 0xFA terminator)
            return 0;
        }
        int m0 = limit - pad - 2; // first  0xFA of the marker
        int m1 = limit - pad - 1; // second 0xFA of the marker
        if (m0 < position) {
            return 0;
        }
        if ((in.get(m0) & 0xFF) != 0xFA || (in.get(m1) & 0xFF) != 0xFA) {
            return 0; // not a padded DAVE frame; leave it alone
        }
        in.limit(limit - pad);
        return pad;
    }

    private static final class Handler implements InvocationHandler {
        private final DaveSession target;

        Handler(DaveSession target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean isDecrypt = "decrypt".equals(method.getName()) && args != null && args.length == 4;
            long userId = 0;
            ByteBuffer in = null;
            int before = 0;
            String tailIn = "(n/a)";
            if (isDecrypt) {
                userId = (Long) args[1];
                in = (ByteBuffer) args[2];
                before = in == null ? -1 : in.remaining();
                tailIn = tail(in);
                int stripped = stripPadding(in);
                if (stripped > 0 && SAMPLED.getOrDefault(userId, 0) < SAMPLES_PER_USER
                        && LOGGER.isInfoEnabled()) {
                    LOGGER.info("DaveSession: stripped {}b of RTP padding for user {} "
                                    + "(frame {}b -> {}b, tail now {})",
                            stripped, userId, before, in.remaining(), tail(in));
                }
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
            if (isDecrypt && SAMPLED.merge(userId, 1, Integer::sum) <= SAMPLES_PER_USER) {
                ByteBuffer out = (ByteBuffer) args[3];
                LOGGER.info("DaveSession.decrypt user {} in {}b tail={} -> {} (out {}b head={})",
                        userId, before, tailIn, result, outSize(out), head(out));
            }
            // A successful decrypt is the only frame worth reading the opus TOC
            // from (its first byte encodes the frame duration, which JDA's
            // hardcoded 20 ms decoder chokes on for long music frames).
            if (isDecrypt && Boolean.TRUE.equals(result)
                    && SUCCESS_SAMPLES.merge(userId, 1, Integer::sum) <= SUCCESS_SAMPLES_PER_USER) {
                ByteBuffer out = (ByteBuffer) args[3];
                LOGGER.info("DaveSession OK user {} out {}b TOC={} (opus frame)",
                        userId, outSize(out), head(out));
            }
            return result;
        }
    }

    private static int outSize(ByteBuffer b) {
        return b == null ? -1 : b.remaining();
    }

    private static String tail(ByteBuffer b) {
        if (b == null || b.remaining() < 4) {
            return "(short)";
        }
        int limit = b.limit();
        StringBuilder sb = new StringBuilder();
        for (int i = limit - 4; i < limit; i++) {
            sb.append(String.format("%02x", b.get(i)));
        }
        return sb.toString();
    }

    private static String head(ByteBuffer b) {
        if (b == null || b.remaining() < 4) {
            return "(short)";
        }
        int pos = b.position();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8 && i < b.remaining(); i++) {
            sb.append(String.format("%02x", b.get(pos + i)));
        }
        return sb.toString();
    }
}
