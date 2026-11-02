package com.discordvoicebridge.audio;

import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock audio pipeline test: verifies the PCM conversion and buffering logic plus a
 * simulated Opus encode/decode round trip, without any real Discord token or audio
 * hardware.
 */
class AudioPipelineTest {

    @Test
    void stereoToMonoAveragesChannels() {
        short[] stereo = { 1000, -2000, 0, 4000, -1000, 1000 };
        short[] mono = AudioPipeline.stereoToMono(stereo);
        assertArrayEquals(new short[] { -500, 2000, 0 }, mono);
    }

    @Test
    void stereoToFrameIsJdaSendContract() {
        // JDA expects exactly one 20 ms stereo frame: 3840 bytes, big-endian 16-bit.
        short[] stereo = new short[AudioPipeline.SAMPLES_PER_FRAME];
        stereo[0] = 0x1234;              // first sample: big-endian 0x12 0x34
        stereo[1] = -1;                  // second sample: 0xFF 0xFF
        byte[] frame = AudioPipeline.stereoToFrame(stereo);
        assertEquals(AudioPipeline.STEREO_FRAME_BYTES, frame.length);
        assertEquals(0x12, frame[0]);
        assertEquals(0x34, frame[1]);
        assertEquals((byte) 0xFF, frame[2]);
        assertEquals((byte) 0xFF, frame[3]);
        // remainder of the frame is silence (zeros)
        for (int i = 4; i < frame.length; i++) {
            assertEquals(0, frame[i]);
        }
    }

    @Test
    void stereoToFramePadsShortInput() {
        byte[] frame = AudioPipeline.stereoToFrame(new short[] { 0x0100 });
        assertEquals(AudioPipeline.STEREO_FRAME_BYTES, frame.length);
        assertEquals(0x01, frame[0]);
        assertEquals(0x00, frame[1]);
    }

    @Test
    void monoToStereoDuplicatesChannel() {
        short[] mono = { 1, -2, 3 };
        assertArrayEquals(new short[] { 1, 1, -2, -2, 3, 3 }, AudioPipeline.monoToStereo(mono));
    }

    @Test
    void stereoFrameQueueDropsOldestWhenFull() {
        AudioPipeline.StereoFrameQueue queue = new AudioPipeline.StereoFrameQueue(2);
        queue.offer(new byte[] { 1 });
        queue.offer(new byte[] { 2 });
        queue.offer(new byte[] { 3 });
        assertArrayEquals(new byte[] { 2 }, queue.poll());
        assertArrayEquals(new byte[] { 3 }, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void pcmRoundTripThroughMockCodec() {
        // MC -> Discord path: mono mic PCM is duplicated to stereo, encoded, decoded
        // again as mono. Stereo frames derived from mono must round-trip exactly.
        short[] mono = new short[960];
        for (int i = 0; i < 960; i++) {
            mono[i] = (short) (i % 1000 - 500);
        }
        short[] stereo = AudioPipeline.monoToStereo(mono);

        short[] backToMono = AudioPipeline.stereoToMono(stereo);

        FakeEncoder encoder = new FakeEncoder();
        byte[] opus = encoder.encode(backToMono);
        assertArrayEquals(mono, FakeEncoder.lastPcm);

        short[] decoded = FakeDecoder.decode(opus);
        assertArrayEquals(mono, decoded);
        assertArrayEquals(stereo, AudioPipeline.monoToStereo(decoded));
    }

    /** Fake SVC OpusEncoder: records the PCM it was asked to encode. */
    static class FakeEncoder implements OpusEncoder {
        static short[] lastPcm;

        @Override
        public byte[] encode(short[] pcm) {
            lastPcm = pcm.clone();
            return new byte[] { 1, 2, 3 };
        }

        @Override
        public void resetState() {
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    /** Fake decoder that returns the last PCM the fake encoder received. */
    static class FakeDecoder {
        static short[] decode(byte[] opus) {
            return FakeEncoder.lastPcm;
        }
    }
}
