package com.realmvc.recorder;

/**
 * Rules for recording the user's voice while another app may already hold the mic.
 *
 * <p>Android 10+ (this app's minimum) does not fail the second {@code AudioRecord}.
 * It starts the capture and, when the two apps cannot share, feeds silence to the
 * loser. A privacy-sensitive client ({@code VOICE_COMMUNICATION} or {@code CAMCORDER},
 * the default for a Realm VC call and for Minecraft voice chat) always wins, so the
 * recorder keeps receiving zeros until that call lets go. Giving up before
 * {@code startRecording} is what left the voice out of in-game videos.
 *
 * <p>Source values match {@code android.media.MediaRecorder.AudioSource}.
 */
final class MicShare {
    static final int SOURCE_MIC = 1;
    static final int SOURCE_CAMCORDER = 5;
    static final int SOURCE_VOICE_RECOGNITION = 6;
    static final int SOURCE_VOICE_COMMUNICATION = 7;
    static final int SOURCE_REMOTE_SUBMIX = 8;
    static final int SOURCE_UNPROCESSED = 9;
    /** Hidden {@code MediaRecorder.AudioSource.HOTWORD}. Always-on listeners, not a call. */
    static final int SOURCE_HOTWORD = 1999;

    /** What we open. Not privacy-sensitive, so we never take a call's exclusive mic. */
    static final int OUR_SOURCE = SOURCE_MIC;

    static final String BLOCKED_LEAD = "Your voice isn't in this recording.";

    static final String TOAST =
            "Your voice isn't in this recording. A call is holding the mic \u2014 open Realm Recorder or the notification for what to do.";

    static final String ONGOING =
            "A call is holding the mic, so your voice is silence. Leave the call and turn off Minecraft voice chat, then record again. Or use Realm VC \u2192 Me \u2192 Recording mode with headphones to capture the other voices.";

    static final String RESUMED =
            "The call let go of the microphone. Your voice is being recorded now.";

    static final String MIC_UNAVAILABLE =
            "The microphone couldn't be opened, so your voice won't be in this video. Game audio is still recorded if internal audio is on. Allow the microphone permission, then start the recording again.";

    static final String MIC_DROPPED =
            "The microphone stopped, so the rest of this video won't have your voice. Game audio keeps recording.";

    static final String FGS_BLOCKED =
            "Android wouldn't let Realm Recorder use the microphone while you're in another app, so your voice won't be in this video. Allow the microphone permission and start the recording again from Realm Recorder.";

    private MicShare() {}

    /**
     * Open the mic whenever the user asked for it. A busy microphone (Minecraft,
     * Realm VC, even {@code VOICE_COMMUNICATION}) is not a reason to skip: the
     * capture still starts, and {@code isClientSilenced()} tells us if Android
     * kept the mic for the call.
     */
    static boolean shouldOpenMic(boolean requested) {
        return requested;
    }

    /** Sources that make Android silence every other ordinary capture. */
    static boolean captureIsPrivate(int source) {
        return source == SOURCE_VOICE_COMMUNICATION || source == SOURCE_CAMCORDER;
    }

    /** Our capture must not set that flag, or it would silence the call. */
    static boolean ourCaptureIsPrivate() {
        return false;
    }

    /** Hotword detectors and playback-capture sessions are not "someone is on a call". */
    static boolean ignorableCapture(int source) {
        return source == SOURCE_HOTWORD || source == SOURCE_REMOTE_SUBMIX;
    }

    static void checkSources(int mic, int camcorder, int voiceCommunication, int remoteSubmix) {
        if (mic != SOURCE_MIC || camcorder != SOURCE_CAMCORDER
                || voiceCommunication != SOURCE_VOICE_COMMUNICATION
                || remoteSubmix != SOURCE_REMOTE_SUBMIX) {
            throw new IllegalStateException("audio source constants don't match Android");
        }
    }

    /** Full in-app explanation. {@code gameAudio} is true when app playback is in the file. */
    static String blocked(boolean gameAudio) {
        String msg = BLOCKED_LEAD
                + " Android is holding the microphone for a call \u2014 Realm VC or Minecraft voice chat \u2014"
                + " and will not let a second app record that mic at the same time."
                + " You can still be heard in the call.";
        if (gameAudio) msg += " Game audio is still recording.";
        msg += "\n\nTo put your voice in the video: leave the voice call, turn off Minecraft's voice chat,"
                + " then stop and start the recording again with Internal + Mic."
                + "\n\nTo record everyone else instead: in Realm VC open Me \u2192 Recording mode, turn it on,"
                + " and wear headphones. That records their voices and the game."
                + " Android still won't add your own mic on top of the call.";
        return msg;
    }

    static boolean isBlocked(String message) {
        return message != null && message.startsWith(BLOCKED_LEAD);
    }

    static boolean explainsMissingVoice(String message) {
        return isBlocked(message) || MIC_UNAVAILABLE.equals(message) || MIC_DROPPED.equals(message)
                || FGS_BLOCKED.equals(message);
    }

    /** @return the notice to show, or null when the state did not change */
    static String messageForTransition(boolean silenced, boolean alreadyBlocked, boolean gameAudio) {
        if (silenced && !alreadyBlocked) return blocked(gameAudio);
        if (!silenced && alreadyBlocked) return RESUMED;
        return null;
    }
}
