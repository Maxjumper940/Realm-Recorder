package com.realmvc.recorder;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings, stored in one SharedPreferences file. */
public final class Prefs {
    public static final String AUDIO_INTERNAL = "internal";
    public static final String AUDIO_MIC = "mic";
    public static final String AUDIO_BOTH = "both";
    public static final String AUDIO_MUTE = "mute";

    public static final String TARGET_REALMVC = "realmvc";
    public static final String TARGET_MINECRAFT = "minecraft";
    public static final String TARGET_ALL = "all";

    public static final String ORIENT_AUTO = "auto";
    public static final String ORIENT_PORTRAIT = "portrait";
    public static final String ORIENT_LANDSCAPE = "landscape";

    public static final String PKG_REALMVC = "com.realmvc.app";
    public static final String PKG_MINECRAFT = "com.mojang.minecraftpe";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("realm_recorder", Context.MODE_PRIVATE);
    }

    public static String audioMode(Context c) { return sp(c).getString("audioMode", AUDIO_BOTH); }
    public static void setAudioMode(Context c, String v) { sp(c).edit().putString("audioMode", v).apply(); }

    public static String audioTarget(Context c) { return sp(c).getString("audioTarget", TARGET_ALL); }
    public static void setAudioTarget(Context c, String v) { sp(c).edit().putString("audioTarget", v).apply(); }

    /** Short side of the video in pixels (720 or 1080). */
    public static int resolution(Context c) { return sp(c).getInt("resolution", 1080); }
    public static void setResolution(Context c, int v) { sp(c).edit().putInt("resolution", v).apply(); }

    public static int fps(Context c) { return sp(c).getInt("fps", 30); }
    public static void setFps(Context c, int v) { sp(c).edit().putInt("fps", v).apply(); }

    /** Video bitrate in Mbps. */
    public static int bitrateMbps(Context c) { return sp(c).getInt("bitrate", 8); }
    public static void setBitrateMbps(Context c, int v) { sp(c).edit().putInt("bitrate", v).apply(); }

    public static String orientation(Context c) { return sp(c).getString("orientation", ORIENT_AUTO); }
    public static void setOrientation(Context c, String v) { sp(c).edit().putString("orientation", v).apply(); }

    public static boolean toolbarNotification(Context c) { return sp(c).getBoolean("toolbarNotif", true); }
    public static void setToolbarNotification(Context c, boolean v) { sp(c).edit().putBoolean("toolbarNotif", v).apply(); }

    public static boolean floatingBall(Context c) { return sp(c).getBoolean("floatingBall", false); }
    public static void setFloatingBall(Context c, boolean v) { sp(c).edit().putBoolean("floatingBall", v).apply(); }

    public static int ballY(Context c) { return sp(c).getInt("ballY", 400); }
    public static void setBallY(Context c, int v) { sp(c).edit().putInt("ballY", v).apply(); }

    public static boolean audioUsesMic(String mode) { return AUDIO_MIC.equals(mode) || AUDIO_BOTH.equals(mode); }
    public static boolean audioUsesInternal(String mode) { return AUDIO_INTERNAL.equals(mode) || AUDIO_BOTH.equals(mode); }

    public static String audioModeLabel(String m) {
        switch (m) {
            case AUDIO_INTERNAL: return "Internal audio";
            case AUDIO_MIC: return "Microphone";
            case AUDIO_MUTE: return "Mute";
            default: return "Internal + Mic";
        }
    }

    public static String targetLabel(String t) {
        switch (t) {
            case TARGET_REALMVC: return "Realm VC";
            case TARGET_MINECRAFT: return "Minecraft";
            default: return "All compatible apps";
        }
    }

    public static String orientationLabel(String o) {
        switch (o) {
            case ORIENT_PORTRAIT: return "Portrait";
            case ORIENT_LANDSCAPE: return "Landscape";
            default: return "Auto (current)";
        }
    }
}
