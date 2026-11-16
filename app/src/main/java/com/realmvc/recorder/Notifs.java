package com.realmvc.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/** Builds the XRecorder-style shade controls: Record/Stop, Screenshot, Tools, Home, Exit. */
final class Notifs {
    static final String CH_RECORDING = "recording";
    static final String CH_TOOLBAR = "toolbar";
    static final int ID_RECORDING = 41;
    static final int ID_TOOLBAR = 42;
    static final int ID_SAVED = 43;

    private Notifs() {}

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel rec = new NotificationChannel(CH_RECORDING,
                c.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW);
        rec.setShowBadge(false);
        rec.setSound(null, null);
        NotificationChannel bar = new NotificationChannel(CH_TOOLBAR,
                c.getString(R.string.channel_toolbar), NotificationManager.IMPORTANCE_LOW);
        bar.setShowBadge(false);
        bar.setSound(null, null);
        nm.createNotificationChannel(rec);
        nm.createNotificationChannel(bar);
    }

    // --------------------------------------------------------------- intents

    static PendingIntent projectionPi(Context c, String mode, int req) {
        Intent i = new Intent(c, ProjectionActivity.class)
                .putExtra(ProjectionActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent servicePi(Context c, String action, int req) {
        Intent i = new Intent(c, RecorderService.class).setAction(action);
        return PendingIntent.getService(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent toolsPi(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 30, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openAppPi(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 31, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent homePi(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 32, i, PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent exitPi(Context c) {
        Intent i = new Intent(c, ExitReceiver.class).setAction(ExitReceiver.ACTION_EXIT);
        return PendingIntent.getBroadcast(c, 33, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------------------------------------------------------- notifications

    private static RemoteViews controls(Context c, int layout, boolean recording) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setImageViewResource(R.id.icRec, recording ? R.drawable.ic_stop : R.drawable.ic_rec);
        v.setTextViewText(R.id.txtRec, recording ? "Stop" : "Record");
        v.setOnClickPendingIntent(R.id.btnRec, recording
                ? servicePi(c, RecorderService.ACTION_STOP, 20)
                : projectionPi(c, ProjectionActivity.MODE_RECORD, 21));
        v.setOnClickPendingIntent(R.id.btnShot, recording
                ? servicePi(c, RecorderService.ACTION_SHOT, 22)
                : projectionPi(c, ProjectionActivity.MODE_SHOT, 23));
        v.setOnClickPendingIntent(R.id.btnTools, toolsPi(c));
        v.setOnClickPendingIntent(R.id.btnHome, homePi(c));
        v.setOnClickPendingIntent(R.id.btnExit, exitPi(c));
        return v;
    }

    /** Ongoing notification while recording (used by the foreground service). */
    static Notification recording(Context c, String detail) {
        RemoteViews small = controls(c, R.layout.notif_small, true);
        RemoteViews big = controls(c, R.layout.notif_big, true);
        long base = RecState.startedElapsed();
        big.setTextViewText(R.id.status, "\u25CF Recording");
        big.setViewVisibility(R.id.chrono, View.VISIBLE);
        big.setChronometer(R.id.chrono, base, null, true);
        big.setTextViewText(R.id.sub, detail);
        long wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - base);
        Notification.Builder b = new Notification.Builder(c, CH_RECORDING)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFFF4FA8)
                .setContentTitle("Recording")
                .setContentText(detail)
                .setSubText("Recording")
                .setWhen(wall)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(openAppPi(c));
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** Short-lived foreground notification used while taking a one-off screenshot. */
    static Notification working(Context c, String text) {
        return new Notification.Builder(c, CH_RECORDING)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFFF4FA8)
                .setContentTitle("Realm Recorder")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openAppPi(c))
                .build();
    }

    /** Idle toolbar in the shade so recording can start from anywhere. */
    static Notification toolbar(Context c) {
        RemoteViews small = controls(c, R.layout.notif_small, false);
        RemoteViews big = controls(c, R.layout.notif_big, false);
        String detail = Prefs.audioModeLabel(Prefs.audioMode(c)) + " \u00B7 " + Prefs.targetLabel(Prefs.audioTarget(c));
        big.setTextViewText(R.id.status, "Ready to record");
        big.setViewVisibility(R.id.chrono, View.GONE);
        big.setTextViewText(R.id.sub, detail);
        return new Notification.Builder(c, CH_TOOLBAR)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFFF4FA8)
                .setContentTitle("Realm Recorder")
                .setContentText(detail)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(openAppPi(c))
                .build();
    }

    static void showToolbar(Context c) {
        if (RecState.isRecording() || !Prefs.toolbarNotification(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        ensureChannels(c);
        try {
            nm.notify(ID_TOOLBAR, toolbar(c));
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS was revoked.
        }
    }

    static void hideToolbar(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_TOOLBAR);
    }

    static void showSaved(Context c, android.net.Uri uri) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 34, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH_TOOLBAR)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFFF4FA8)
                .setContentTitle("Video saved")
                .setContentText("Movies/RealmRecorder \u00B7 tap to play")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        try {
            nm.notify(ID_SAVED, n);
        } catch (SecurityException ignored) {}
    }
}
