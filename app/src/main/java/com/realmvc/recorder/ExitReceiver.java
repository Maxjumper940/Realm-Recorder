package com.realmvc.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Exit" from the shade: stop (and save) any recording, remove the toolbar and the floating ball. */
public class ExitReceiver extends BroadcastReceiver {
    static final String ACTION_EXIT = "com.realmvc.recorder.ACTION_EXIT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_EXIT.equals(intent.getAction())) return;
        exitAll(context);
    }

    static void exitAll(Context c) {
        Context app = c.getApplicationContext();
        if (RecState.isRecording()) {
            // The recording service is in the foreground, so this start is allowed.
            RecorderService.sendAction(app, RecorderService.ACTION_EXIT);
        } else {
            Notifs.hideToolbar(app);
            app.stopService(new android.content.Intent(app, FloatingBallService.class));
        }
    }
}
