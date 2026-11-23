package com.realmvc.recorder;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide recording state, observed by the UI, notification and floating ball. */
public final class RecState {
    public interface Listener { void onRecStateChanged(); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean recording;
    private static volatile long startedElapsed;
    private static volatile String lastMessage;
    /** Stays on screen until the next recording starts or the mic starts working. */
    private static volatile String banner;

    private RecState() {}

    public static boolean isRecording() { return recording; }

    /** Elapsed recording time in ms, 0 when idle. */
    public static long elapsedMs() {
        return recording ? SystemClock.elapsedRealtime() - startedElapsed : 0;
    }

    public static long startedElapsed() { return startedElapsed; }

    public static String lastMessage() { return lastMessage; }

    /** Why the microphone is missing, or null. Shown on the home screen. */
    public static String banner() { return banner; }

    static void setRecording(boolean r) {
        recording = r;
        if (r) startedElapsed = SystemClock.elapsedRealtime();
        notifyListeners();
    }

    static void setMessage(String msg) {
        lastMessage = msg;
        notifyListeners();
    }

    static void setBanner(String msg) {
        banner = msg;
        notifyListeners();
    }

    public static void add(Listener l) { LISTENERS.addIfAbsent(l); }
    public static void remove(Listener l) { LISTENERS.remove(l); }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onRecStateChanged();
        });
    }

    public static String format(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }
}
