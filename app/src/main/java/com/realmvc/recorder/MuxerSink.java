package com.realmvc.recorder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe MediaMuxer wrapper. MediaMuxer can only start once every track
 * is added, so samples that arrive earlier (usually the first video key frame)
 * are held in memory and written as soon as the muxer starts.
 */
final class MuxerSink {
    private static final String TAG = "RealmRec.Muxer";
    private static final int MAX_PENDING = 900;
    private static final int AUDIO_WAIT_LIMIT = 240;

    private static final class Pending {
        final boolean video;
        final byte[] data;
        final long ptsUs;
        final int flags;
        Pending(boolean video, byte[] data, long ptsUs, int flags) {
            this.video = video; this.data = data; this.ptsUs = ptsUs; this.flags = flags;
        }
    }

    private final MediaMuxer muxer;
    private int expectedTracks;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean started;
    private boolean released;
    private long videoSamples;
    private long lastVideoPts = -1;
    private long lastAudioPts = -1;
    private final List<Pending> pending = new ArrayList<>();

    MuxerSink(FileDescriptor fd, boolean withAudio) throws IOException {
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        expectedTracks = withAudio ? 2 : 1;
    }

    synchronized void setOrientationHint(int degrees) {
        if (!started) muxer.setOrientationHint(degrees);
    }

    synchronized void addTrack(MediaFormat format, boolean video) {
        if (started || released) return;
        if (video && videoTrack < 0) videoTrack = muxer.addTrack(format);
        else if (!video && audioTrack < 0) audioTrack = muxer.addTrack(format);
        maybeStart();
    }

    /** Audio failed before it produced a format: carry on with video only. */
    synchronized void dropAudio() {
        if (started || released || audioTrack >= 0) return;
        expectedTracks = 1;
        pending.removeIf(p -> !p.video);
        maybeStart();
    }

    private void maybeStart() {
        int have = (videoTrack >= 0 ? 1 : 0) + (audioTrack >= 0 ? 1 : 0);
        if (have < expectedTracks || videoTrack < 0) return;
        muxer.start();
        started = true;
        for (Pending p : pending) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, p.data.length, p.ptsUs, p.flags);
            writeLocked(p.video, ByteBuffer.wrap(p.data), info);
        }
        pending.clear();
    }

    synchronized void write(boolean video, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (released || info.size <= 0) return;
        if (!started) {
            if (video && pending.size() >= AUDIO_WAIT_LIMIT && audioTrack < 0 && expectedTracks == 2) {
                // Audio never produced anything (e.g. capture blocked): don't hold video forever.
                Log.w(TAG, "no audio format after " + pending.size() + " samples, continuing video-only");
                expectedTracks = 1;
                pending.removeIf(p -> !p.video);
                maybeStart();
                if (started) { writeLocked(true, buf, info); return; }
            }
            if (pending.size() >= MAX_PENDING) return;
            byte[] copy = new byte[info.size];
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            buf.get(copy);
            pending.add(new Pending(video, copy, info.presentationTimeUs, info.flags));
            return;
        }
        writeLocked(video, buf, info);
    }

    private void writeLocked(boolean video, ByteBuffer buf, MediaCodec.BufferInfo info) {
        int track = video ? videoTrack : audioTrack;
        if (track < 0) return;
        // Timestamps must never go backwards within a track.
        if (video) {
            if (info.presentationTimeUs <= lastVideoPts) info.presentationTimeUs = lastVideoPts + 1;
            lastVideoPts = info.presentationTimeUs;
        } else {
            if (info.presentationTimeUs <= lastAudioPts) info.presentationTimeUs = lastAudioPts + 1;
            lastAudioPts = info.presentationTimeUs;
        }
        try {
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            muxer.writeSampleData(track, buf, info);
            if (video) videoSamples++;
        } catch (RuntimeException e) {
            Log.w(TAG, "writeSampleData failed", e);
        }
    }

    /** @return true when a playable file with at least one video frame was written. */
    synchronized boolean finish() {
        if (released) return false;
        released = true;
        boolean ok = false;
        try {
            if (started) {
                muxer.stop();
                ok = videoSamples > 0;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "muxer stop failed", e);
        }
        try { muxer.release(); } catch (RuntimeException ignored) {}
        return ok;
    }
}
