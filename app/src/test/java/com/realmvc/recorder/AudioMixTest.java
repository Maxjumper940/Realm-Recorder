package com.realmvc.recorder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The mic used to be mixed with one non-blocking read, which dropped samples. */
public class AudioMixTest {
    @Test
    public void micIsAddedToBothChannels() {
        short[] stereo = {1, 2, 3, 4};
        short[] mic = {10, 20};
        AudioMix.mixMicIntoStereo(stereo, mic, 2);
        assertEquals(11, stereo[0]);
        assertEquals(12, stereo[1]);
        assertEquals(23, stereo[2]);
        assertEquals(24, stereo[3]);
    }

    @Test
    public void loudSamplesClampInsteadOfWrapping() {
        short[] stereo = {32760, 0, -32760, 0};
        short[] mic = {100, -100};
        AudioMix.mixMicIntoStereo(stereo, mic, 2);
        assertEquals(Short.MAX_VALUE, stereo[0]);
        assertEquals(100, stereo[1]);
        assertEquals(Short.MIN_VALUE, stereo[2]);
        assertEquals(-100, stereo[3]);
    }

    @Test
    public void shortMicReadDoesNotEraseTheRestOfTheGameAudio() {
        short[] stereo = {1, 1, 5, 5};
        short[] mic = {2, 9};
        AudioMix.mixMicIntoStereo(stereo, mic, 1);
        assertEquals(3, stereo[0]);
        assertEquals(3, stereo[1]);
        assertEquals(5, stereo[2]);
        assertEquals(5, stereo[3]);
    }

    @Test
    public void missingMicLeavesGameAudioUntouched() {
        short[] stereo = {4, 5, 6, 7};
        AudioMix.mixMicIntoStereo(stereo, null, 4);
        AudioMix.mixMicIntoStereo(stereo, new short[]{9, 9}, 0);
        assertEquals(4, stereo[0]);
        assertEquals(7, stereo[3]);
    }

    @Test
    public void monoMicFillsBothChannelsWhenThereIsNoGameAudio() {
        short[] stereo = new short[4];
        AudioMix.monoToStereo(stereo, new short[]{8, -3});
        assertEquals(8, stereo[0]);
        assertEquals(8, stereo[1]);
        assertEquals(-3, stereo[2]);
        assertEquals(-3, stereo[3]);
    }

    @Test
    public void everyMicFrameIsMixedWhenBothStreamsProduce() throws Exception {
        // Same handoff the recorder uses: blocking queues, then mix one chunk from each.
        // A non-blocking read would have kept only whatever was already buffered.
        int chunks = 30;
        int frames = 4;
        PcmQueue internalQ = new PcmQueue(chunks);
        PcmQueue micQ = new PcmQueue(chunks);
        for (int c = 0; c < chunks; c++) {
            short[] stereo = new short[frames * 2];
            short[] mic = new short[frames];
            for (int i = 0; i < frames; i++) {
                stereo[2 * i] = (short) (c + 1);
                stereo[2 * i + 1] = (short) (c + 1);
                mic[i] = (short) (100 + i);
            }
            internalQ.put(stereo);
            micQ.put(mic);
        }
        for (int c = 0; c < chunks; c++) {
            short[] stereo = internalQ.poll(1000);
            short[] mic = micQ.poll(1000);
            assertNotNull(stereo);
            assertNotNull(mic);
            AudioMix.mixMicIntoStereo(stereo, mic, mic.length);
            assertEquals((c + 1) + 100, stereo[0]);
            assertEquals((c + 1) + 100, stereo[1]);
            assertEquals((c + 1) + 103, stereo[6]);
        }
    }

    @Test(timeout = 5000)
    public void queueKeepsEveryChunkWhenTheConsumerIsSlower() throws Exception {
        PcmQueue q = new PcmQueue(2);
        int n = 20;
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < n; i++) q.put(new short[]{(short) i});
                q.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        producer.start();
        for (int i = 0; i < n; i++) {
            short[] got = null;
            while (got == null) got = q.poll(200);
            assertEquals(i, got[0]);
        }
        producer.join();
        assertTrue(q.isClosed());
        assertNull(q.poll(0));
    }
}
