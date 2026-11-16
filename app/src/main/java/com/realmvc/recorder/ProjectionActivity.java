package com.realmvc.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible activity that asks for runtime permissions and the Android screen-capture
 * consent, then hands the result to RecorderService. A new consent is requested for
 * every recording or one-off screenshot (required on Android 14).
 */
public class ProjectionActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_RECORD = "record";
    static final String MODE_SHOT = "shot";
    private static final int REQ_PERMS = 1;
    private static final int REQ_CAPTURE = 2;

    private String mode;
    private boolean asked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_RECORD;
        if (savedInstanceState != null) {
            asked = savedInstanceState.getBoolean("asked");
            return; // a request is already on screen
        }
        if (MODE_RECORD.equals(mode) && RecState.isRecording()) {
            finish();
            return;
        }
        if (MODE_SHOT.equals(mode) && RecState.isRecording()) {
            RecorderService.sendAction(this, RecorderService.ACTION_SHOT);
            finish();
            return;
        }
        requestPermsThenConsent();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    private void requestPermsThenConsent() {
        List<String> need = new ArrayList<>();
        if (MODE_RECORD.equals(mode) && !Prefs.AUDIO_MUTE.equals(Prefs.audioMode(this))
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            askConsent();
        } else {
            asked = true;
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])
                    && (i >= grantResults.length || grantResults[i] != PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "No microphone permission: the video will be silent. You can allow it in Settings.",
                        Toast.LENGTH_LONG).show();
            }
        }
        askConsent();
    }

    private void askConsent() {
        asked = true;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "This phone doesn't allow screen capture.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            String action = MODE_SHOT.equals(mode) ? RecorderService.ACTION_SHOT_ONCE : RecorderService.ACTION_START;
            try {
                RecorderService.startForegroundAction(this, action, resultCode, data);
            } catch (RuntimeException e) {
                Toast.makeText(this, "Couldn't start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Screen capture cancelled", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
