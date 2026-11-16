package com.realmvc.recorder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records a MediaProjection to an MP4 in MediaStore:
 * VirtualDisplay -> H.264 (MediaCodec surface input) and
 * AudioPlaybackCapture and/or microphone PCM -> mixed -> AAC, muxed with MediaMuxer.
 * All timestamps share one System.nanoTime() base so audio and video stay in sync.
 */
final class ScreenRecorder {
    private static final String TAG = "RealmRec.Engine";
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAMES_PER_CHUNK = 1024; // one AAC frame
    private static final int AUDIO_BITRATE = 192_000;

    interface Listener {
        /** Called on a worker thread when recording breaks and must stop. */
        void onFatal(String message);
        /** Called on a worker thread with non-fatal info for the user. */
        void onNotice(String message);
    }

    static final class Config {
        int width, height, dpi, fps, bitrate;
        boolean internalAudio, micAudio;
        int[] matchUids; // null/empty = all capturable apps
    }

    interface ShotCallback { void onShot(Bitmap bmp); }

    private final Context ctx;
    private final MediaProjection projection;
    private final Config cfg;
    private final Listener listener;

    private Uri outUri;
    private ParcelFileDescriptor outPfd;
    private MuxerSink sink;
    private MediaCodec videoCodec;
    private Surface inputSurface;
    private VirtualDisplay display;
    private MediaCodec audioCodec;
    private AudioRecord internalRec;
    private AudioRecord micRec;
    private Thread videoThread, audioThread;
    private HandlerThread shotThread;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean shotBusy = new AtomicBoolean(false);
    private volatile boolean videoEosSent;
    private long baseUs;

    ScreenRecorder(Context ctx, MediaProjection projection, Config cfg, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.projection = projection;
        this.cfg = cfg;
        this.listener = listener;
    }

    int width() { return cfg.width; }
    int height() { return cfg.height; }

    /** Sets everything up and starts capturing. Throws on failure after cleaning up. */
    void start() throws Exception {
        try {
            startInternal();
        } catch (Exception e) {
            releaseAll();
            Media.delete(ctx, outUri);
            outUri = null;
            throw e;
        }
    }

    private void startInternal() throws Exception {
        boolean wantAudio = cfg.internalAudio || cfg.micAudio;

        // ---- audio sources first, so failures can downgrade the audio mode ----
        if (cfg.internalAudio) {
            try {
                internalRec = buildPlaybackCapture();
            } catch (Exception e) {
                Log.w(TAG, "playback capture unavailable", e);
                internalRec = null;
                listener.onNotice("Internal audio isn't available on this phone; recording without it.");
            }
        }
        if (cfg.micAudio && micBusy()) {
            // Only one ordinary app can use the mic at a time. Taking it would mute the user in
            // their Realm VC call, so leave it alone and record app audio only.
            listener.onNotice("Another app (like your Realm VC call) is using the mic, so your own voice isn't recorded. "
                    + "App audio is still recorded.");
        } else if (cfg.micAudio) {
            try {
                micRec = buildMic();
            } catch (Exception e) {
                Log.w(TAG, "mic unavailable", e);
                micRec = null;
                listener.onNotice("Microphone is busy or blocked; recording without it.");
            }
        }
        wantAudio = wantAudio && (internalRec != null || micRec != null);

        // ---- output file ----
        outUri = Media.newVideo(ctx);
        if (outUri == null) throw new IllegalStateException("Couldn't create the video file in Movies/RealmRecorder");
        outPfd = ctx.getContentResolver().openFileDescriptor(outUri, "rw");
        if (outPfd == null) throw new IllegalStateException("Couldn't open the video file");
        sink = new MuxerSink(outPfd.getFileDescriptor(), wantAudio);

        // ---- video encoder ----
        videoCodec = createVideoEncoder();
        inputSurface = videoCodec.createInputSurface();
        videoCodec.start();

        if (wantAudio) {
            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_CHUNK * 2 * 2 * 4);
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();
        }

        baseUs = System.nanoTime() / 1000;

        // The MediaProjection.Callback is registered by RecorderService before this point,
        // as Android 14 requires. Only one VirtualDisplay is ever created per projection.
        display = projection.createVirtualDisplay("RealmRecorder", cfg.width, cfg.height, cfg.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);
        if (display == null) throw new IllegalStateException("Android refused to create the capture display");

        videoThread = new Thread(this::videoLoop, "RealmRec-video");
        videoThread.start();
        if (wantAudio) {
            internalRec = startOrDrop(internalRec);
            micRec = startOrDrop(micRec);
            if (internalRec == null && micRec == null) {
                listener.onNotice("Android wouldn't start audio capture; recording video only.");
                sink.dropAudio();
            } else {
                audioThread = new Thread(this::audioLoop, "RealmRec-audio");
                audioThread.start();
            }
        }
    }

    private boolean micBusy() {
        try {
            android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
            if (am == null) return false;
            for (android.media.AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                int src = c.getClientAudioSource();
                // Ignore always-on hotword listeners (1999 = HOTWORD) and other apps' playback capture.
                if (src == 1999 || src == MediaRecorder.AudioSource.REMOTE_SUBMIX) continue;
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static AudioRecord startOrDrop(AudioRecord r) {
        if (r == null) return null;
        try {
            r.startRecording();
            return r;
        } catch (RuntimeException e) {
            Log.w(TAG, "startRecording failed", e);
            try { r.release(); } catch (RuntimeException ignored) {}
            return null;
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before starting.
    private AudioRecord buildPlaybackCapture() {
        AudioPlaybackCaptureConfiguration.Builder b = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        if (cfg.matchUids != null) {
            for (int uid : cfg.matchUids) b.addMatchingUid(uid);
        }
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min, FRAMES_PER_CHUNK * 4) * 4)
                .setAudioPlaybackCaptureConfig(b.build())
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("playback capture not initialized");
        }
        return r;
    }

    @SuppressLint("MissingPermission")
    private AudioRecord buildMic() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("mic format unsupported");
        AudioRecord r = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, FRAMES_PER_CHUNK * 2) * 4)
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("mic not initialized");
        }
        return r;
    }

    private MediaCodec createVideoEncoder() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            MediaCodecInfo.VideoCapabilities caps = codec.getCodecInfo()
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            fitToEncoder(caps);
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // Keep frames flowing when the screen is static, and cap 90/120 Hz screens to the chosen fps.
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / cfg.fps);
            f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, cfg.fps);
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException first) {
                Log.w(TAG, "encoder rejected " + cfg.width + "x" + cfg.height + "@" + cfg.fps + ", retrying safer", first);
                codec.reset();
                scaleTo(720);
                cfg.fps = 30;
                cfg.bitrate = Math.min(cfg.bitrate, 6_000_000);
                MediaFormat g = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height);
                g.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                g.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate);
                g.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                g.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                g.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 30);
                codec.configure(g, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                listener.onNotice("Your phone's encoder can't do that quality, so it's recording at "
                        + Math.min(cfg.width, cfg.height) + "p 30fps.");
            }
            return codec;
        } catch (Exception e) {
            codec.release();
            throw e;
        }
    }

    /** Shrinks the size, fps and bitrate until the hardware encoder supports them. */
    private void fitToEncoder(MediaCodecInfo.VideoCapabilities caps) {
        int wa = Math.max(2, caps.getWidthAlignment());
        int ha = Math.max(2, caps.getHeightAlignment());
        cfg.width = cfg.width / wa * wa;
        cfg.height = cfg.height / ha * ha;
        int guard = 0;
        while (!caps.isSizeSupported(cfg.width, cfg.height) && guard++ < 20) {
            cfg.width = (int) (cfg.width * 0.9) / wa * wa;
            cfg.height = (int) (cfg.height * 0.9) / ha * ha;
        }
        if (!caps.areSizeAndRateSupported(cfg.width, cfg.height, cfg.fps)) {
            cfg.fps = 30;
        }
        Integer maxBr = caps.getBitrateRange().getUpper();
        if (maxBr != null && cfg.bitrate > maxBr) cfg.bitrate = maxBr;
    }

    private void scaleTo(int shortSide) {
        int s = Math.min(cfg.width, cfg.height);
        if (s <= shortSide) return;
        float k = shortSide / (float) s;
        cfg.width = Math.round(cfg.width * k) / 16 * 16;
        cfg.height = Math.round(cfg.height * k) / 16 * 16;
    }

    // ------------------------------------------------------------------ video

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long eosWaitStart = 0;
        try {
            while (true) {
                int idx = videoCodec.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.addTrack(videoCodec.getOutputFormat(), true);
                } else if (idx >= 0) {
                    ByteBuffer buf = videoCodec.getOutputBuffer(idx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (buf != null && info.size > 0) {
                        info.presentationTimeUs -= baseUs;
                        if (info.presentationTimeUs >= 0) sink.write(true, buf, info);
                    }
                    videoCodec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (videoEosSent) {
                    if (eosWaitStart == 0) eosWaitStart = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - eosWaitStart > 3000) break; // encoder never sent EOS
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "video loop", e);
            if (!stopping.get()) listener.onFatal("The video encoder stopped unexpectedly.");
        }
    }

    // ------------------------------------------------------------------ audio

    private void audioLoop() {
        final boolean mix = internalRec != null && micRec != null;
        final short[] main = new short[FRAMES_PER_CHUNK * 2]; // stereo
        final short[] mic = new short[FRAMES_PER_CHUNK];        // mono
        final byte[] bytes = new byte[FRAMES_PER_CHUNK * 4];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frames = 0;
        long startUs = -1;
        boolean formatSeen = false;
        try {
            while (!stopping.get()) {
                if (internalRec != null) {
                    int n = readFully(internalRec, main, main.length);
                    if (n < 0) throw new IllegalStateException("internal read " + n);
                    if (n < main.length) java.util.Arrays.fill(main, n, main.length, (short) 0);
                    if (mix) {
                        int m = micRec.read(mic, 0, mic.length, AudioRecord.READ_NON_BLOCKING);
                        for (int i = 0; i < Math.max(0, m); i++) {
                            int l = main[2 * i] + mic[i];
                            int r = main[2 * i + 1] + mic[i];
                            main[2 * i] = clamp(l);
                            main[2 * i + 1] = clamp(r);
                        }
                    }
                } else {
                    int m = readFully(micRec, mic, mic.length);
                    if (m < 0) throw new IllegalStateException("mic read " + m);
                    for (int i = 0; i < FRAMES_PER_CHUNK; i++) {
                        short s = i < m ? mic[i] : 0;
                        main[2 * i] = s;
                        main[2 * i + 1] = s;
                    }
                }
                if (stopping.get()) break;
                if (startUs < 0) {
                    // Time of the first sample = now minus the chunk we just read.
                    startUs = System.nanoTime() / 1000 - baseUs - (FRAMES_PER_CHUNK * 1_000_000L / SAMPLE_RATE);
                    if (startUs < 0) startUs = 0;
                }
                for (int i = 0; i < main.length; i++) {
                    bytes[2 * i] = (byte) (main[i] & 0xff);
                    bytes[2 * i + 1] = (byte) ((main[i] >> 8) & 0xff);
                }
                long pts = startUs + frames * 1_000_000L / SAMPLE_RATE;
                frames += FRAMES_PER_CHUNK;
                queueAudio(bytes, bytes.length, pts, 0);
                formatSeen |= drainAudio(info, false);
            }
            long pts = startUs < 0 ? 0 : startUs + frames * 1_000_000L / SAMPLE_RATE;
            queueAudio(bytes, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            formatSeen |= drainAudio(info, true);
        } catch (RuntimeException e) {
            Log.e(TAG, "audio loop", e);
            if (!stopping.get()) listener.onNotice("Audio stopped; the rest of the video will be silent.");
        } finally {
            if (!formatSeen) sink.dropAudio();
        }
    }

    private static int readFully(AudioRecord r, short[] buf, int len) {
        int off = 0;
        while (off < len) {
            int n = r.read(buf, off, len - off, AudioRecord.READ_BLOCKING);
            if (n < 0) return off > 0 ? off : n;
            if (n == 0) break; // stopped
            off += n;
        }
        return off;
    }

    private static short clamp(int v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    private void queueAudio(byte[] data, int len, long pts, int flags) {
        for (int tries = 0; tries < 50; tries++) {
            int idx = audioCodec.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer in = audioCodec.getInputBuffer(idx);
                if (in == null) return;
                in.clear();
                int n = Math.min(len, in.remaining());
                in.put(data, 0, n);
                audioCodec.queueInputBuffer(idx, 0, n, pts, flags);
                return;
            }
            drainAudio(new MediaCodec.BufferInfo(), false);
        }
    }

    /** @return true if the output format was delivered during this call. */
    private boolean drainAudio(MediaCodec.BufferInfo info, boolean untilEos) {
        boolean format = false;
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            int idx = audioCodec.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.addTrack(audioCodec.getOutputFormat(), false);
                format = true;
            } else if (idx >= 0) {
                ByteBuffer buf = audioCodec.getOutputBuffer(idx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (buf != null && info.size > 0) sink.write(false, buf, info);
                audioCodec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return format;
            } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || System.currentTimeMillis() > deadline) return format;
            }
        }
    }

    // ------------------------------------------------------------- screenshot

    /** Grabs one frame while recording by briefly pointing the capture display at an ImageReader. */
    boolean screenshot(ShotCallback cb) {
        if (display == null || stopping.get() || !shotBusy.compareAndSet(false, true)) return false;
        if (shotThread == null) {
            shotThread = new HandlerThread("RealmRec-shot");
            shotThread.start();
        }
        Handler h = new Handler(shotThread.getLooper());
        ImageReader reader = ImageReader.newInstance(cfg.width, cfg.height, PixelFormat.RGBA_8888, 2);
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable restore = () -> {
            if (!done.compareAndSet(false, true)) return;
            try {
                if (display != null && !stopping.get()) display.setSurface(inputSurface);
            } catch (RuntimeException ignored) {}
            reader.close();
            shotBusy.set(false);
        };
        reader.setOnImageAvailableListener(r -> {
            if (done.get()) return;
            Bitmap bmp = null;
            try (Image img = r.acquireLatestImage()) {
                if (img != null) bmp = imageToBitmap(img);
            } catch (RuntimeException e) {
                Log.w(TAG, "shot read failed", e);
            }
            restore.run();
            if (bmp != null) cb.onShot(bmp);
        }, h);
        try {
            display.setSurface(reader.getSurface());
        } catch (RuntimeException e) {
            restore.run();
            return false;
        }
        h.postDelayed(restore, 1500);
        return true;
    }

    static Bitmap imageToBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        int w = img.getWidth(), h = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        int padW = rowStride / pixelStride;
        if (pixelStride == 4 && buf.remaining() >= (long) padW * h * 4) {
            Bitmap tmp = Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888);
            tmp.copyPixelsFromBuffer(buf);
            if (padW == w) return tmp;
            Bitmap out = Bitmap.createBitmap(tmp, 0, 0, w, h);
            tmp.recycle();
            return out;
        }
        // Slow path: the last row is shorter than rowStride on some devices.
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * rowStride;
            for (int x = 0; x < w; x++) {
                int o = row + x * pixelStride;
                if (o + 3 >= buf.limit()) break;
                int r = buf.get(o) & 0xff, g = buf.get(o + 1) & 0xff, b = buf.get(o + 2) & 0xff;
                px[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ------------------------------------------------------------------ stop

    /**
     * Stops and finalises the file. Blocks for up to a few seconds: call off the main thread.
     * @return the saved video's Uri, or null if nothing usable was recorded.
     */
    Uri stop() {
        if (!stopping.compareAndSet(false, true)) return null;
        // Stop feeding frames before telling the encoder the stream has ended.
        VirtualDisplay vd = display;
        display = null;
        if (vd != null) { try { vd.release(); } catch (RuntimeException ignored) {} }
        try {
            if (videoCodec != null) {
                videoCodec.signalEndOfInputStream();
                videoEosSent = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "signalEndOfInputStream", e);
            videoEosSent = true;
        }
        // Unblock pending AudioRecord reads.
        stopRec(internalRec);
        stopRec(micRec);
        join(audioThread);
        join(videoThread);
        boolean ok = sink != null && sink.finish();
        releaseAll();
        if (outUri == null) return null;
        if (ok) {
            Media.publish(ctx, outUri);
            return outUri;
        }
        Media.delete(ctx, outUri);
        return null;
    }

    private static void stopRec(AudioRecord r) {
        if (r == null) return;
        try { r.stop(); } catch (RuntimeException ignored) {}
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void releaseAll() {
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) {} display = null; }
        if (videoCodec != null) {
            try { videoCodec.stop(); } catch (RuntimeException ignored) {}
            try { videoCodec.release(); } catch (RuntimeException ignored) {}
            videoCodec = null;
        }
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        if (audioCodec != null) {
            try { audioCodec.stop(); } catch (RuntimeException ignored) {}
            try { audioCodec.release(); } catch (RuntimeException ignored) {}
            audioCodec = null;
        }
        if (internalRec != null) { try { internalRec.release(); } catch (RuntimeException ignored) {} internalRec = null; }
        if (micRec != null) { try { micRec.release(); } catch (RuntimeException ignored) {} micRec = null; }
        if (sink != null && !stopping.get()) { sink.finish(); }
        if (outPfd != null) { try { outPfd.close(); } catch (Exception ignored) {} outPfd = null; }
        if (shotThread != null) { shotThread.quitSafely(); shotThread = null; }
    }
}
