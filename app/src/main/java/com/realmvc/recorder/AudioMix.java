package com.realmvc.recorder;

/**
 * Mixes microphone PCM into the stereo app-playback buffer.
 * The microphone is mono; it is added to both channels and saturated so a loud
 * voice cannot wrap around into a click.
 */
final class AudioMix {
    private AudioMix() {}

    /** Copies mono frames into both stereo channels. */
    static void monoToStereo(short[] stereo, short[] mic) {
        int n = Math.min(mic.length, stereo.length / 2);
        for (int i = 0; i < n; i++) {
            stereo[2 * i] = mic[i];
            stereo[2 * i + 1] = mic[i];
        }
    }

    /**
     * Adds {@code micFrames} mono microphone frames onto an interleaved stereo buffer.
     * Frames past {@code micFrames} are left untouched, so a short read cannot invent
     * samples and cannot erase the game audio that was already there.
     */
    static void mixMicIntoStereo(short[] stereo, short[] mic, int micFrames) {
        if (stereo == null || mic == null || micFrames <= 0) return;
        int n = Math.min(micFrames, Math.min(mic.length, stereo.length / 2));
        for (int i = 0; i < n; i++) {
            stereo[2 * i] = sat(stereo[2 * i] + mic[i]);
            stereo[2 * i + 1] = sat(stereo[2 * i + 1] + mic[i]);
        }
    }

    static short sat(int v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }
}
