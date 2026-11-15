package com.realmvc.recorder;

import android.content.ContentValues;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScreenRecorderEngine {
    interface Listener {
        void onFinished(String uri);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int AUDIO_FRAMES_PER_CHUNK = 1024;
    private static final int AUDIO_CHANNELS = 2;
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final PcmBlock END_BLOCK = new PcmBlock(new short[0], 0, 0);

    private final Context context;
    private final MediaProjection projection;
    private final int audioAppUid;
    private final Listener listener;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean captureRunning = new AtomicBoolean();
    private final BlockingQueue<PcmBlock> playbackBlocks = new ArrayBlockingQueue<>(8);
    private final BlockingQueue<PcmBlock> microphoneBlocks = new ArrayBlockingQueue<>(8);

    private Uri outputUri;
    private ParcelFileDescriptor outputDescriptor;
    private MuxerWriter muxerWriter;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord playbackRecord;
    private AudioRecord microphoneRecord;
    private VirtualDisplay virtualDisplay;
    private Surface encoderSurface;
    private Thread videoDrainThread;
    private Thread audioMixThread;
    private String failureMessage;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private long audioPresentationTimeUs;
    private long videoStartTimeUs = -1;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stop();
        }
    };

    ScreenRecorderEngine(Context context, MediaProjection projection, int audioAppUid, Listener listener) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.audioAppUid = audioAppUid;
        this.listener = listener;
    }

    void start() throws Exception {
        try {
            createOutput();
            setupEncoders();
            setupAudioRecords();
            videoEncoder.start();
            audioEncoder.start();
            microphoneRecord.startRecording();
            playbackRecord.startRecording();

            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
            DisplayMetrics metrics = recordingMetrics();
            virtualDisplay = projection.createVirtualDisplay(
                    "Realm Recorder",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    encoderSurface,
                    null,
                    null);
            if (virtualDisplay == null) throw new IllegalStateException("Android could not create the screen capture display.");

            startDrainThreads();
            captureRunning.set(true);
            startAudioCaptureThreads();
        } catch (Exception e) {
            releaseOnStartFailure();
            deleteOutput();
            throw e;
        }
    }

    private void createOutput() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "Realm-Recording-" + timestamp + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Realm Recorder");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        outputUri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IOException("Android could not create the recording file.");
        outputDescriptor = context.getContentResolver().openFileDescriptor(outputUri, "rw");
        if (outputDescriptor == null) throw new IOException("Android could not open the recording file.");
        MediaMuxer muxer = new MediaMuxer(
                outputDescriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerWriter = new MuxerWriter(muxer, this::onMuxingComplete);
    }

    private void setupEncoders() throws IOException {
        DisplayMetrics metrics = recordingMetrics();
        MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, metrics.widthPixels, metrics.heightPixels);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = videoEncoder.createInputSurface();

        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, AUDIO_CHANNELS);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void setupAudioRecords() {
        AudioFormat playbackFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int playbackBufferSize = positiveBufferSize(AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT));
        android.media.AudioPlaybackCaptureConfiguration.Builder captureBuilder =
                new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        if (audioAppUid >= 0) captureBuilder.addMatchingUid(audioAppUid);
        android.media.AudioPlaybackCaptureConfiguration captureConfig = captureBuilder.build();
        playbackRecord = new AudioRecord.Builder()
                .setAudioFormat(playbackFormat)
                .setBufferSizeInBytes(playbackBufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build();

        AudioFormat microphoneFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int microphoneBufferSize = positiveBufferSize(AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
        microphoneRecord = new AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setAudioFormat(microphoneFormat)
                .setBufferSizeInBytes(microphoneBufferSize)
                .build();
        if (playbackRecord.getState() != AudioRecord.STATE_INITIALIZED
                || microphoneRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Android could not initialize audio capture.");
        }
    }

    private int positiveBufferSize(int minimum) {
        if (minimum <= 0) throw new IllegalStateException("This phone does not support the required audio format.");
        return Math.max(minimum, AUDIO_FRAMES_PER_CHUNK * AUDIO_CHANNELS * 2 * 4);
    }

    private DisplayMetrics recordingMetrics() {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        int maxDimension = Math.max(metrics.widthPixels, metrics.heightPixels);
        if (maxDimension > 1280) {
            float scale = 1280f / maxDimension;
            metrics.widthPixels = (int) (metrics.widthPixels * scale) & ~1;
            metrics.heightPixels = (int) (metrics.heightPixels * scale) & ~1;
        }
        metrics.widthPixels = Math.max(2, metrics.widthPixels & ~1);
        metrics.heightPixels = Math.max(2, metrics.heightPixels & ~1);
        return metrics;
    }

    private void startAudioCaptureThreads() {
        new Thread(() -> readPcm(playbackRecord, AUDIO_CHANNELS, playbackBlocks), "playback-audio-capture").start();
        new Thread(() -> readPcm(microphoneRecord, 1, microphoneBlocks), "microphone-audio-capture").start();
        audioMixThread = new Thread(this::mixAndEncodeAudio, "mixed-audio-encoder");
        audioMixThread.start();
    }

    private void readPcm(AudioRecord record, int channels, BlockingQueue<PcmBlock> queue) {
        short[] buffer = new short[AUDIO_FRAMES_PER_CHUNK * channels];
        try {
            while (captureRunning.get()) {
                int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    short[] samples = Arrays.copyOf(buffer, count);
                    queue.put(new PcmBlock(samples, channels, count / channels));
                } else if (count < 0 && captureRunning.get()) {
                    fail("Android stopped audio capture (" + count + ").");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (captureRunning.get()) fail("Could not read audio: " + e.getMessage());
        } finally {
            try {
                queue.put(END_BLOCK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void mixAndEncodeAudio() {
        boolean microphoneEnded = false;
        boolean playbackEnded = false;
        PcmBlock microphoneBlock = null;
        PcmBlock playbackBlock = null;
        try {
            while (!microphoneEnded || !playbackEnded) {
                if (!microphoneEnded && microphoneBlock == null) {
                    microphoneBlock = microphoneBlocks.take();
                    if (microphoneBlock == END_BLOCK) {
                        microphoneEnded = true;
                        microphoneBlock = null;
                    }
                }
                if (!playbackEnded && playbackBlock == null) {
                    playbackBlock = playbackBlocks.take();
                    if (playbackBlock == END_BLOCK) {
                        playbackEnded = true;
                        playbackBlock = null;
                    }
                }
                if (microphoneBlock == null && playbackBlock == null) continue;

                int frames = Math.max(
                        microphoneBlock == null ? 0 : microphoneBlock.frames,
                        playbackBlock == null ? 0 : playbackBlock.frames);
                short[] mixed = new short[frames * AUDIO_CHANNELS];
                for (int frame = 0; frame < frames; frame++) {
                    short mic = sample(microphoneBlock, frame, 0);
                    short left = sample(playbackBlock, frame, 0);
                    short right = sample(playbackBlock, frame, 1);
                    mixed[frame * 2] = clamp((mic + left) / 2);
                    mixed[frame * 2 + 1] = clamp((mic + right) / 2);
                }
                encodeAudio(mixed, frames);
                microphoneBlock = null;
                playbackBlock = null;
                drainEncoder(audioEncoder, false);
            }

            int inputIndex;
            while ((inputIndex = audioEncoder.dequeueInputBuffer(10_000)) < 0) {
                drainEncoder(audioEncoder, false);
            }
            audioEncoder.queueInputBuffer(
                    inputIndex, 0, 0, audioPresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            while (!drainEncoder(audioEncoder, false)) {
                // Drain until the encoder emits its end-of-stream buffer.
            }
        } catch (Exception e) {
            fail("Could not encode call audio: " + e.getMessage());
            try {
                if (audioEncoder != null) audioEncoder.stop();
            } catch (RuntimeException ignored) {
            }
        } finally {
            releaseCodec(audioEncoder);
            muxerWriter.trackEnded();
        }
    }

    private short sample(PcmBlock block, int frame, int channel) {
        if (block == null || frame >= block.frames) return 0;
        int sourceChannel = block.channels == 1 ? 0 : channel;
        return block.samples[frame * block.channels + sourceChannel];
    }

    private short clamp(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private void encodeAudio(short[] samples, int frames) throws Exception {
        int inputIndex = audioEncoder.dequeueInputBuffer(10_000);
        if (inputIndex < 0) throw new IllegalStateException("Audio encoder did not provide an input buffer.");
        ByteBuffer input = audioEncoder.getInputBuffer(inputIndex);
        if (input == null || input.capacity() < samples.length * 2) {
            throw new IllegalStateException("Audio encoder input buffer is too small.");
        }
        input.clear();
        for (short sample : samples) input.putShort(sample);
        long pts = audioPresentationTimeUs;
        audioEncoder.queueInputBuffer(inputIndex, 0, samples.length * 2, pts, 0);
        audioPresentationTimeUs += frames * 1_000_000L / SAMPLE_RATE;
    }

    private void startDrainThreads() {
        videoDrainThread = new Thread(this::drainVideoUntilEnd, "video-encoder-drain");
        videoDrainThread.start();
    }

    private void drainVideoUntilEnd() {
        boolean endSignaled = false;
        try {
            while (true) {
                if (stopping.get() && !endSignaled) {
                    videoEncoder.signalEndOfInputStream();
                    endSignaled = true;
                }
                if (drainEncoder(videoEncoder, true)) break;
            }
        } catch (Exception e) {
            fail("Could not encode the screen: " + e.getMessage());
        } finally {
            releaseCodec(videoEncoder);
            muxerWriter.trackEnded();
        }
    }

    private boolean drainEncoder(MediaCodec codec, boolean video) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            int track = muxerWriter.addTrack(codec.getOutputFormat());
            if (video) videoTrack = track;
            else audioTrack = track;
            return false;
        }
        if (outputIndex < 0) return false;

        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        try {
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                if (video) {
                    if (videoStartTimeUs < 0) videoStartTimeUs = info.presentationTimeUs;
                    info.presentationTimeUs = Math.max(0, info.presentationTimeUs - videoStartTimeUs);
                }
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if (output == null) throw new IllegalStateException("Encoder output buffer was unavailable.");
                output.position(info.offset);
                output.limit(info.offset + info.size);
                int track = video ? videoTrack : audioTrack;
                if (track < 0) throw new IllegalStateException("Encoder output track was not initialized.");
                muxerWriter.writeSample(track, output, info);
            }
        } finally {
            codec.releaseOutputBuffer(outputIndex, false);
        }
        return eos;
    }

    void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        captureRunning.set(false);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        stopRecord(playbackRecord);
        stopRecord(microphoneRecord);
        try {
            projection.unregisterCallback(projectionCallback);
        } catch (RuntimeException ignored) {
        }
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
        }
    }

    private void stopRecord(AudioRecord record) {
        if (record == null) return;
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
        } catch (RuntimeException ignored) {
        }
    }

    private void fail(String message) {
        if (!failed.compareAndSet(false, true)) return;
        failureMessage = message;
        stop();
    }

    private void onMuxingComplete(String muxError) {
        releaseAudioRecords();
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        String error = failureMessage != null ? failureMessage : muxError;
        if (error != null) {
            deleteOutput();
            listener.onError(error);
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            int updated = context.getContentResolver().update(outputUri, values, null, null);
            if (updated != 1) throw new IOException("Android could not publish the saved recording.");
            closeOutputDescriptor();
            listener.onFinished(outputUri.toString());
        } catch (Exception e) {
            deleteOutput();
            listener.onError("Could not save the recording: " + e.getMessage());
        }
    }

    private void releaseOnStartFailure() {
        captureRunning.set(false);
        stopRecord(playbackRecord);
        stopRecord(microphoneRecord);
        if (virtualDisplay != null) virtualDisplay.release();
        if (encoderSurface != null) encoderSurface.release();
        releaseCodec(videoEncoder);
        releaseCodec(audioEncoder);
        if (muxerWriter != null) muxerWriter.abort();
        releaseAudioRecords();
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
        }
        closeOutputDescriptor();
    }

    private void releaseAudioRecords() {
        if (playbackRecord != null) {
            playbackRecord.release();
            playbackRecord = null;
        }
        if (microphoneRecord != null) {
            microphoneRecord.release();
            microphoneRecord = null;
        }
    }

    private void releaseCodec(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            codec.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void deleteOutput() {
        if (outputUri != null) context.getContentResolver().delete(outputUri, null, null);
        closeOutputDescriptor();
    }

    private void closeOutputDescriptor() {
        if (outputDescriptor == null) return;
        try {
            outputDescriptor.close();
        } catch (IOException ignored) {
        }
        outputDescriptor = null;
    }

    private static final class PcmBlock {
        final short[] samples;
        final int channels;
        final int frames;

        PcmBlock(short[] samples, int channels, int frames) {
            this.samples = samples;
            this.channels = channels;
            this.frames = frames;
        }
    }

    private interface MuxCompletion {
        void onComplete(String error);
    }

    private static final class MuxerWriter {
        private final MediaMuxer muxer;
        private final MuxCompletion completion;
        private int tracksAdded;
        private int tracksEnded;
        private boolean started;
        private boolean completed;
        private String error;

        MuxerWriter(MediaMuxer muxer, MuxCompletion completion) {
            this.muxer = muxer;
            this.completion = completion;
        }

        synchronized int addTrack(MediaFormat format) {
            int track = muxer.addTrack(format);
            tracksAdded++;
            if (tracksAdded == 2) {
                muxer.start();
                started = true;
                notifyAll();
            }
            return track;
        }

        synchronized void writeSample(int track, ByteBuffer data, MediaCodec.BufferInfo info) throws Exception {
            while (!started && !completed) wait();
            if (!started || completed) return;
            muxer.writeSampleData(track, data, info);
        }

        synchronized void abort() {
            if (completed) return;
            completed = true;
            notifyAll();
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
            }
        }

        void trackEnded() {
            String finalError = null;
            synchronized (this) {
                tracksEnded++;
                if (tracksEnded < 2 || completed) return;
                completed = true;
                notifyAll();
                try {
                    if (started) muxer.stop();
                } catch (RuntimeException e) {
                    error = "Could not finish the MP4 file: " + e.getMessage();
                }
                try {
                    muxer.release();
                } catch (RuntimeException e) {
                    if (error == null) error = "Could not close the MP4 file: " + e.getMessage();
                }
                finalError = error;
            }
            completion.onComplete(finalError);
        }
    }
}
