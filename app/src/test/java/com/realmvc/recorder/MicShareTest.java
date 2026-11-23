package com.realmvc.recorder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks the in-game mic policy: always try to record the voice, and when Android
 * silences that capture, say why and what to do.
 */
public class MicShareTest {
    @Test
    public void opensTheMicWhileAnotherAppHoldsIt() {
        assertTrue(MicShare.shouldOpenMic(true));
        // A Realm VC call and Minecraft voice chat use a private source.
        // That used to make the recorder skip the mic entirely.
        assertTrue(MicShare.captureIsPrivate(MicShare.SOURCE_VOICE_COMMUNICATION));
        assertTrue(MicShare.captureIsPrivate(MicShare.SOURCE_CAMCORDER));
        assertTrue(MicShare.shouldOpenMic(true));
    }

    @Test
    public void staysClosedWhenTheUserTurnedTheMicOff() {
        assertFalse(MicShare.shouldOpenMic(false));
    }

    @Test
    public void ourCaptureDoesNotTakeTheCallMic() {
        assertEquals(MicShare.SOURCE_MIC, MicShare.OUR_SOURCE);
        assertFalse(MicShare.ourCaptureIsPrivate());
        assertFalse(MicShare.captureIsPrivate(MicShare.SOURCE_MIC));
        assertFalse(MicShare.captureIsPrivate(MicShare.SOURCE_VOICE_RECOGNITION));
        assertFalse(MicShare.captureIsPrivate(MicShare.SOURCE_UNPROCESSED));
    }

    @Test
    public void hotwordAndPlaybackCaptureAreNotACall() {
        assertTrue(MicShare.ignorableCapture(MicShare.SOURCE_HOTWORD));
        assertTrue(MicShare.ignorableCapture(MicShare.SOURCE_REMOTE_SUBMIX));
        assertFalse(MicShare.ignorableCapture(MicShare.SOURCE_MIC));
        assertFalse(MicShare.ignorableCapture(MicShare.SOURCE_VOICE_COMMUNICATION));
    }

    @Test
    public void sourceConstantsMatchThePlatform() {
        MicShare.checkSources(
                MicShare.SOURCE_MIC,
                MicShare.SOURCE_CAMCORDER,
                MicShare.SOURCE_VOICE_COMMUNICATION,
                MicShare.SOURCE_REMOTE_SUBMIX);
    }

    @Test
    public void driftedSourceConstantsFailLoudly() {
        try {
            MicShare.checkSources(1, 5, 7, 99);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected a mismatch");
    }

    @Test
    public void silencedCallExplainsTheWorkaroundAndKeepsGameAudioHonest() {
        String withGame = MicShare.blocked(true);
        String micOnly = MicShare.blocked(false);
        for (String msg : new String[]{withGame, micOnly}) {
            assertTrue(MicShare.isBlocked(msg));
            assertTrue(msg.contains("holding the microphone"));
            assertTrue(msg.contains("will not let a second app"));
            assertTrue(msg.contains("still be heard in the call"));
            assertTrue(msg.contains("leave the voice call"));
            assertTrue(msg.contains("turn off Minecraft's voice chat"));
            assertTrue(msg.contains("Internal + Mic"));
            assertTrue(msg.contains("Recording mode"));
            assertTrue(msg.contains("headphones"));
            assertTrue(MicShare.explainsMissingVoice(msg));
        }
        assertTrue(withGame.contains("Game audio is still recording."));
        assertFalse(micOnly.contains("Game audio is still recording."));
        assertTrue(MicShare.ONGOING.contains("Leave the call"));
        assertTrue(MicShare.TOAST.contains("holding the mic"));
    }

    @Test
    public void silenceTransitionsNotifyOnceAndClearWhenTheCallLetsGo() {
        assertNull(MicShare.messageForTransition(false, false, true));
        String blocked = MicShare.messageForTransition(true, false, true);
        assertTrue(MicShare.isBlocked(blocked));
        assertNull(MicShare.messageForTransition(true, true, true));
        assertEquals(MicShare.RESUMED, MicShare.messageForTransition(false, true, true));
        assertNull(MicShare.messageForTransition(false, false, false));
        assertFalse(MicShare.blocked(false).contains("Game audio is still recording."));
        assertTrue(MicShare.messageForTransition(true, false, false).startsWith(MicShare.BLOCKED_LEAD));
    }

    @Test
    public void otherMicFailuresAreShownInTheApp() {
        assertTrue(MicShare.explainsMissingVoice(MicShare.MIC_UNAVAILABLE));
        assertTrue(MicShare.explainsMissingVoice(MicShare.MIC_DROPPED));
        assertTrue(MicShare.explainsMissingVoice(MicShare.FGS_BLOCKED));
        assertFalse(MicShare.isBlocked(MicShare.FGS_BLOCKED));
        assertFalse(MicShare.explainsMissingVoice(MicShare.RESUMED));
        assertTrue(MicShare.FGS_BLOCKED.contains("while you're in another app"));
        assertTrue(MicShare.MIC_UNAVAILABLE.contains("Allow the microphone permission"));
    }
}
