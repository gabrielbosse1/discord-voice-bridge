package com.discordvoicebridge.audio;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Shared audio conversion and buffering helpers for the Discord <-> SVC bridge.
 * All rates are 48 kHz, 16-bit signed PCM.
 */
public final class AudioPipeline {

    /** One 20 ms frame at 48 kHz = 960 samples per channel. */
    public static final int SAMPLES_PER_FRAME = 960;
    /** One 20 ms stereo frame as JDA expects: 960 samples * 2 ch * 2 bytes, big-endian. */
    public static final int STEREO_FRAME_BYTES = SAMPLES_PER_FRAME * 2 * 2;

    private AudioPipeline() {
    }

    /**
     * Convert interleaved stereo PCM to one fixed-size JDA send frame: 960 samples
     * per channel (1920 interleaved = 20 ms @ 48 kHz), big-endian 16-bit, padded with
     * silence (zeros) when the input is short. JDA's {@code AudioSendHandler#provide20MsAudio}
     * contract requires exactly this shape. The cap is {@code SAMPLES_PER_FRAME * 2}
     * because the input is interleaved L/R, not mono - capping at SAMPLES_PER_FRAME would
     * keep only the first 10 ms and pad the rest with silence (robotic/choppy output).
     */
    public static byte[] stereoToFrame(short[] stereo) {
        byte[] frame = new byte[STEREO_FRAME_BYTES];
        int samples = Math.min(stereo.length, SAMPLES_PER_FRAME * 2);
        for (int i = 0; i < samples; i++) {
            frame[i * 2] = (byte) (stereo[i] >> 8);
            frame[i * 2 + 1] = (byte) stereo[i];
        }
        return frame;
    }

    /** Stereo 48kHz PCM (interleaved L/R shorts) -> mono 48kHz PCM by averaging channels. */
    public static short[] stereoToMono(short[] stereo) {
        if (stereo == null) {
            return new short[0];
        }
        short[] mono = new short[stereo.length / 2];
        for (int i = 0, j = 0; i < stereo.length; i += 2, j++) {
            mono[j] = (short) ((stereo[i] + stereo[i + 1]) / 2);
        }
        return mono;
    }

    /** Mono 48kHz PCM -> stereo 48kHz PCM (duplicate mono into L and R). */
    public static short[] monoToStereo(short[] mono) {
        if (mono == null) {
            return new short[0];
        }
        short[] stereo = new short[mono.length * 2];
        for (int i = 0, j = 0; i < mono.length; i++, j += 2) {
            short s = mono[i];
            stereo[j] = s;
            stereo[j + 1] = s;
        }
        return stereo;
    }

    /**
     * Bounded queue of stereo PCM frames (byte[], 3840 bytes = 20 ms @ 48 kHz).
     * Offer drops the oldest frame when full.
     */
    public static class StereoFrameQueue {
        private final LinkedBlockingQueue<byte[]> buffer;

        public StereoFrameQueue(int capacityFrames) {
            this.buffer = new LinkedBlockingQueue<>(Math.max(1, capacityFrames));
        }

        public void offer(byte[] frame) {
            if (frame == null) {
                return;
            }
            if (!buffer.offer(frame)) {
                buffer.poll(); // drop oldest
                buffer.offer(frame);
            }
        }

        public byte[] poll() {
            return buffer.poll();
        }
    }
}
