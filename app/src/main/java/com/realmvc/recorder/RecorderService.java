package com.realmvc.recorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that owns the MediaProjection. Android 14 order is respected:
 * consent (ProjectionActivity) -> startForeground(type mediaProjection) -> getMediaProjection
 * -> registerCallback -> createVirtualDisplay (once per projection).
 */
public class RecorderService extends Service implements ScreenRecorder.Listener {
    private static final String TAG = "RealmRec.Service";

    static final String ACTION_START = "com.realmvc.recorder.START";
    static final String ACTION_SHOT_ONCE = "com.realmvc.recorder.SHOT_ONCE";
    static final String ACTION_STOP = "com.realmvc.recorder.STOP";
    static final String ACTION_SHOT = "com.realmvc.recorder.SHOT";
    static final String ACTION_EXIT = "com.realmvc.recorder.EXIT";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private MediaProjection projection;      // recording projection
    private ScreenRecorder recorder;
    private boolean stopping;
    private boolean exitRequested;
    private boolean micHeldByCall;
    private String detail = "";

    private MediaProjection shotProjection;  // one-off screenshot projection
    private VirtualDisplay shotDisplay;
    private ImageReader shotReader;

    private final MediaProjection.Callback recordCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            // The user stopped sharing from the status bar / system dialog.
            main.post(() -> stopRecording(false));
        }
    };

    private final MediaProjection.Callback shotCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            main.post(RecorderService.this::releaseShot);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    static void startForegroundAction(Context c, String action, int code, Intent data) {
        Intent i = new Intent(c, RecorderService.class).setAction(action)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data);
        c.startForegroundService(i);
    }

    /** For control actions; only safe while the service is already in the foreground or the app is visible. */
    static void sendAction(Context c, String action) {
        try {
            c.startService(new Intent(c, RecorderService.class).setAction(action));
        } catch (RuntimeException e) {
            Log.w(TAG, "startService(" + action + ") refused", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            // Restarted by the system without a projection token: nothing we can do.
            maybeStopSelf();
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_START: handleStart(intent); break;
            case ACTION_SHOT_ONCE: handleShotOnce(intent); break;
            case ACTION_STOP: stopRecording(false); break;
            case ACTION_SHOT: shotWhileRecording(); break;
            case ACTION_EXIT: exit(); break;
            default: maybeStopSelf();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ foreground

    private int currentType;

    private boolean goForeground(Notification n, int type) {
        try {
            startForeground(Notifs.ID_RECORDING, n, type);
            currentType = type;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground type=" + type + " failed", e);
            return false;
        }
    }

    /** Called when a startForegroundService() arrives while we are already busy. */
    private void reassertForeground() {
        Notification n = recorder != null ? Notifs.recording(this, detail) : Notifs.working(this, "Working\u2026");
        int type = currentType != 0 ? currentType : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        goForeground(n, type);
    }

    // ------------------------------------------------------------- recording

    private void handleStart(Intent intent) {
        Notifs.ensureChannels(this);
        if (recorder != null || stopping) {
            reassertForeground();
            toast(stopping ? "Still saving the last video\u2026" : "Already recording");
            return;
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = getDataExtra(intent);

        RecState.setBanner(null);
        micHeldByCall = false;
        Notifs.hideMicBlocked(this);

        String mode = Prefs.audioMode(this);
        boolean micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean wantInternal = Prefs.audioUsesInternal(mode) && micGranted;
        boolean wantMic = Prefs.audioUsesMic(mode) && micGranted;
        if (!micGranted && !Prefs.AUDIO_MUTE.equals(mode)) {
            toast("Audio permission is off, so this video will have no sound.");
        }

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if ((wantInternal || wantMic) && Build.VERSION.SDK_INT >= 30) {
            // Android 11+ needs the microphone foreground-service type, or the mic
            // (and playback capture) is silenced while Minecraft is in front.
            // The manifest declares mediaProjection|microphone and FOREGROUND_SERVICE_MICROPHONE.
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        boolean micTypeRefused = false;
        Notification starting = Notifs.working(this, "Starting recording\u2026");
        if (!goForeground(starting, type)) {
            if (wantMic) micTypeRefused = true;
            wantMic = false; // the microphone type was refused; internal capture may still work
            if (!goForeground(starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) {
                fail("Android didn't allow screen recording to start. Open Realm Recorder and try again.");
                return;
            }
        }

        if (data == null) {
            fail("Screen capture permission was missing. Try again.");
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            Log.w(TAG, "getMediaProjection", e);
            projection = null;
        }
        if (projection == null) {
            fail("Screen capture permission expired. Tap Record again.");
            return;
        }
        projection.registerCallback(recordCallback, main);

        ScreenRecorder.Config cfg = buildConfig(wantInternal, wantMic);
        ScreenRecorder r = new ScreenRecorder(this, projection, cfg, this);
        try {
            r.start();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            releaseProjection();
            fail("Couldn't start recording: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return;
        }
        recorder = r;
        detail = Math.min(r.width(), r.height()) + "p \u00B7 " + cfg.fps + "fps \u00B7 " + audioLabel(cfg)
                + (cfg.internalAudio ? " (" + Prefs.targetLabel(Prefs.audioTarget(this)) + ")" : "");
        RecState.setRecording(true);
        Notifs.hideToolbar(this);
        notifyRecording();
        toast("Recording started");
        if (micTypeRefused) onNotice(MicShare.FGS_BLOCKED);
    }

    private static String audioLabel(ScreenRecorder.Config cfg) {
        if (cfg.internalAudio && cfg.micAudio) return "Internal + Mic";
        if (cfg.internalAudio) return "Internal audio";
        if (cfg.micAudio) return "Microphone";
        return "No audio";
    }

    @SuppressWarnings("deprecation")
    private static Intent getDataExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        DisplayManager dmgr = getSystemService(DisplayManager.class);
        Display d = dmgr == null ? null : dmgr.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) d.getRealMetrics(dm);
        else dm.setTo(getResources().getDisplayMetrics());
        return dm;
    }

    private ScreenRecorder.Config buildConfig(boolean internal, boolean mic) {
        DisplayMetrics dm = realMetrics();
        int dw = dm.widthPixels, dh = dm.heightPixels;
        String o = Prefs.orientation(this);
        if (Prefs.ORIENT_PORTRAIT.equals(o) && dw > dh || Prefs.ORIENT_LANDSCAPE.equals(o) && dh > dw) {
            int t = dw; dw = dh; dh = t;
        }
        int shortSide = Math.min(dw, dh);
        int target = Math.min(Prefs.resolution(this), shortSide);
        float k = target / (float) shortSide;
        ScreenRecorder.Config c = new ScreenRecorder.Config();
        c.width = Math.round(dw * k) / 2 * 2;
        c.height = Math.round(dh * k) / 2 * 2;
        c.dpi = Math.max(120, Math.round(dm.densityDpi * k));
        c.fps = Prefs.fps(this);
        c.bitrate = Prefs.bitrateMbps(this) * 1_000_000;
        c.internalAudio = internal;
        c.micAudio = mic;
        c.matchUids = internal ? targetUids() : null;
        return c;
    }

    private int[] targetUids() {
        String t = Prefs.audioTarget(this);
        List<String> pkgs = new ArrayList<>();
        if (Prefs.TARGET_REALMVC.equals(t)) pkgs.add(Prefs.PKG_REALMVC);
        else if (Prefs.TARGET_MINECRAFT.equals(t)) pkgs.add(Prefs.PKG_MINECRAFT);
        else return null;
        List<Integer> uids = new ArrayList<>();
        for (String p : pkgs) {
            int uid = uidOf(this, p);
            if (uid >= 0) uids.add(uid);
        }
        if (uids.isEmpty()) {
            onNotice(Prefs.targetLabel(t) + " isn't installed, so all compatible app audio is recorded.");
            return null;
        }
        int[] out = new int[uids.size()];
        for (int i = 0; i < out.length; i++) out[i] = uids.get(i);
        return out;
    }

    static int uidOf(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private void notifyRecording() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(Notifs.ID_RECORDING, Notifs.recording(this, detail, micHeldByCall));
        } catch (SecurityException ignored) {}
    }

    private void stopRecording(boolean exitAfter) {
        if (recorder == null) {
            if (exitAfter) finishExit();
            else maybeStopSelf();
            return;
        }
        final ScreenRecorder r = recorder;
        recorder = null;
        stopping = true;
        micHeldByCall = false;
        Notifs.hideMicBlocked(this);
        RecState.setRecording(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(Notifs.ID_RECORDING, Notifs.working(this, "Saving video\u2026"));
        } catch (SecurityException ignored) {}
        worker.execute(() -> {
            Uri uri = null;
            try {
                uri = r.stop();
            } catch (RuntimeException e) {
                Log.e(TAG, "stop failed", e);
            }
            final Uri saved = uri;
            main.post(() -> {
                stopping = false;
                releaseProjection();
                if (saved != null) {
                    toast("Saved to Movies/RealmRecorder");
                    Notifs.showSaved(this, saved);
                    RecState.setMessage("saved");
                } else {
                    toast("Nothing was recorded");
                }
                if (exitAfter || exitRequested) finishExit();
                else {
                    Notifs.showToolbar(this);
                    maybeStopSelf();
                }
            });
        });
    }

    private void releaseProjection() {
        if (projection == null) return;
        try { projection.unregisterCallback(recordCallback); } catch (RuntimeException ignored) {}
        try { projection.stop(); } catch (RuntimeException ignored) {}
        projection = null;
    }

    private void fail(String msg) {
        toast(msg);
        RecState.setMessage(msg);
        maybeStopSelf();
    }

    // ------------------------------------------------------------ screenshots

    private void shotWhileRecording() {
        if (recorder == null) {
            // Not recording: a fresh consent is needed (Android 14 tokens are single-use).
            Intent i = new Intent(this, ProjectionActivity.class)
                    .putExtra(ProjectionActivity.EXTRA_MODE, ProjectionActivity.MODE_SHOT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (RuntimeException e) { Log.w(TAG, "open consent", e); }
            maybeStopSelf();
            return;
        }
        boolean ok = recorder.screenshot(this::saveShotAsync);
        if (!ok) toast("Screenshot is busy, try again");
    }

    private void saveShotAsync(Bitmap bmp) {
        worker.execute(() -> {
            Uri u = Media.saveScreenshot(this, bmp);
            bmp.recycle();
            main.post(() -> toast(u != null ? "Screenshot saved to Pictures/RealmRecorder" : "Couldn't save the screenshot"));
        });
    }

    private void handleShotOnce(Intent intent) {
        Notifs.ensureChannels(this);
        if (recorder != null) {
            reassertForeground();
            shotWhileRecording();
            return;
        }
        if (shotProjection != null || stopping) {
            reassertForeground();
            return;
        }
        if (!goForeground(Notifs.working(this, "Taking screenshot\u2026"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) {
            fail("Android didn't allow the screenshot.");
            return;
        }
        Intent data = getDataExtra(intent);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            shotProjection = data == null ? null : mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
        } catch (RuntimeException e) {
            shotProjection = null;
        }
        if (shotProjection == null) {
            fail("Screen capture permission expired. Try again.");
            return;
        }
        shotProjection.registerCallback(shotCallback, main);
        DisplayMetrics dm = realMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        try {
            shotReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            shotDisplay = shotProjection.createVirtualDisplay("RealmShot", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, shotReader.getSurface(), null, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "shot display", e);
            releaseShot();
            fail("Couldn't take the screenshot.");
            return;
        }
        // Give the consent dialog time to disappear before grabbing a frame.
        grabShot(0);
    }

    private void grabShot(int attempt) {
        main.postDelayed(() -> {
            if (shotReader == null) return;
            Bitmap bmp = null;
            try (Image img = shotReader.acquireLatestImage()) {
                if (img != null) bmp = ScreenRecorder.imageToBitmap(img);
            } catch (RuntimeException e) {
                Log.w(TAG, "acquire", e);
            }
            if (bmp == null && attempt < 6) {
                grabShot(attempt + 1);
                return;
            }
            releaseShot();
            if (bmp != null) saveShotAsync(bmp);
            else toast("Couldn't capture the screen");
            maybeStopSelf();
        }, attempt == 0 ? 700 : 250);
    }

    private void releaseShot() {
        if (shotDisplay != null) { try { shotDisplay.release(); } catch (RuntimeException ignored) {} shotDisplay = null; }
        if (shotReader != null) { try { shotReader.close(); } catch (RuntimeException ignored) {} shotReader = null; }
        if (shotProjection != null) {
            try { shotProjection.unregisterCallback(shotCallback); } catch (RuntimeException ignored) {}
            try { shotProjection.stop(); } catch (RuntimeException ignored) {}
            shotProjection = null;
        }
    }

    // ------------------------------------------------------------------ exit

    private void exit() {
        exitRequested = true;
        if (recorder != null || stopping) {
            if (recorder != null) stopRecording(true);
            return;
        }
        finishExit();
    }

    private void finishExit() {
        exitRequested = false;
        releaseShot();
        Notifs.hideToolbar(this);
        stopService(new Intent(this, FloatingBallService.class));
        maybeStopSelf();
    }

    private void maybeStopSelf() {
        if (recorder != null || stopping || shotProjection != null) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        currentType = 0;
        stopSelf();
    }

    // ------------------------------------------------------- engine listener

    @Override public void onFatal(String message) {
        main.post(() -> {
            toast(message);
            stopRecording(false);
        });
    }

    @Override public void onNotice(String message) {
        main.post(() -> {
            if (MicShare.RESUMED.equals(message)) {
                micHeldByCall = false;
                RecState.setBanner(null);
                Notifs.hideMicBlocked(this);
            } else if (MicShare.isBlocked(message)) {
                micHeldByCall = true;
                RecState.setBanner(message);
                Notifs.showMicBlocked(this, message);
            } else if (MicShare.explainsMissingVoice(message)) {
                RecState.setBanner(message);
            }
            toast(MicShare.isBlocked(message) ? MicShare.TOAST : message);
            RecState.setMessage(message);
            if (recorder != null) notifyRecording();
        });
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            // Killed while recording: finish the file in the background as best we can.
            final ScreenRecorder r = recorder;
            recorder = null;
            new Thread(() -> {
                try { r.stop(); } catch (RuntimeException ignored) {}
            }, "RealmRec-final").start();
            RecState.setRecording(false);
        }
        micHeldByCall = false;
        Notifs.hideMicBlocked(this);
        releaseProjection();
        releaseShot();
        worker.shutdown();
        super.onDestroy();
    }
}
