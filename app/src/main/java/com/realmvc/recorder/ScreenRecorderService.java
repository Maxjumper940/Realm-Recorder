package com.realmvc.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

public final class ScreenRecorderService extends Service {
    static final String ACTION_STOP = "com.realmvc.recorder.STOP";
    static final String ACTION_STATUS = "com.realmvc.recorder.STATUS";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_PROJECTION_DATA = "projectionData";
    static final String EXTRA_AUDIO_UID = "audioUid";
    static final String EXTRA_AUDIO_LABEL = "audioLabel";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_RECORDING = "recording";
    static final String EXTRA_RECORDING_URI = "recordingUri";
    static final String EXTRA_ERROR = "error";
    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 41;

    private MediaProjection projection;
    private ScreenRecorderEngine engine;
    private boolean stopped;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (engine != null) return START_NOT_STICKY;
        try {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent projectionData = getProjectionData(intent);
            if (projectionData == null) throw new IllegalStateException("Android screen-capture data is missing.");
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, projectionData);
            if (projection == null) throw new IllegalStateException("Android did not create a screen-capture session.");
            int audioUid = intent.getIntExtra(EXTRA_AUDIO_UID, -1);
            String audioLabel = intent.getStringExtra(EXTRA_AUDIO_LABEL);
            engine = new ScreenRecorderEngine(this, projection, audioUid, new ScreenRecorderEngine.Listener() {
                @Override
                public void onFinished(String uri) {
                    stopped = true;
                    sendStatus("Recording saved to Movies/Realm Recorder.", false, uri, null);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }

                @Override
                public void onError(String message) {
                    stopped = true;
                    sendStatus(message, false, null, message);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
            engine.start();
            String audioSource = audioLabel == null ? "eligible app audio" : audioLabel + " audio";
            updateNotification("Recording screen and " + audioSource);
            sendStatus("Recording screen, microphone, and " + audioSource + ".", true, null, null);
        } catch (Exception e) {
            sendStatus("Could not start recording: " + e.getMessage(), false, null, e.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Intent getProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
    }

    private void startAsForeground() {
        createNotificationChannel();
        Notification notification = buildNotification("Preparing screen recording");
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Realm Recorder is capturing the screen and audio.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, ScreenRecorderService.class);
        stopIntent.setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Realm Recorder")
                .setContentText(text)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void requestStop() {
        if (engine != null) {
            engine.stop();
            sendStatus("Saving recording…", true, null, null);
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void sendStatus(String status, boolean recording, String uri, String error) {
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        update.putExtra(EXTRA_RECORDING, recording);
        if (uri != null) update.putExtra(EXTRA_RECORDING_URI, uri);
        if (error != null) update.putExtra(EXTRA_ERROR, error);
        sendBroadcast(update);
    }

    @Override
    public void onDestroy() {
        if (!stopped && engine != null) engine.stop();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
