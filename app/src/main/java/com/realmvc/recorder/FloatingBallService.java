package com.realmvc.recorder;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Draggable floating ball shown over other apps ("Display over other apps" permission).
 * Tap to expand: Record/Stop, Screenshot, Home, Exit. Shows the timer while recording.
 */
public class FloatingBallService extends Service implements RecState.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context wctx;
    private WindowManager wm;
    private LinearLayout root;
    private FrameLayout ball;
    private ImageView ballIcon;
    private TextView ballTimer;
    private LinearLayout panel;
    private ImageView recIcon;
    private TextView recLabel;
    private WindowManager.LayoutParams lp;
    private boolean onRight;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateState();
            if (RecState.isRecording()) handler.postDelayed(this, 500);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            DisplayManager dm = getSystemService(DisplayManager.class);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            wctx = createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } else {
            wctx = this;
        }
        wm = wctx.getSystemService(WindowManager.class);
        try {
            buildViews();
        } catch (RuntimeException e) {
            // Overlay permission was revoked between the check and addView.
            root = null;
            stopSelf();
            return;
        }
        RecState.add(this);
        updateState();
        if (RecState.isRecording()) handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void buildViews() {
        Context c = wctx;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        ball = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.PINK, Ui.HOT});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(Ui.dp(c, 2), 0xCCFFD1EA);
        ball.setBackground(bg);
        ball.setElevation(Ui.dp(c, 6));
        ballIcon = new ImageView(c);
        ballIcon.setImageResource(R.drawable.ic_launcher_foreground);
        ball.addView(ballIcon, new FrameLayout.LayoutParams(-1, -1));
        ballTimer = Ui.text(c, "", 11, Ui.TEXT, true);
        ballTimer.setGravity(Gravity.CENTER);
        ball.addView(ballTimer, new FrameLayout.LayoutParams(-1, -1));
        ball.setContentDescription("Realm Recorder controls");
        int size = Ui.dp(c, 54);
        root.addView(ball, new LinearLayout.LayoutParams(size, size));

        panel = Ui.row(c);
        panel.setBackground(Ui.round(0xF0140A1C, Ui.dp(c, 999), 0xAAFF4FA8, Ui.dp(c, 1)));
        panel.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        panel.setVisibility(View.GONE);
        View rec = action(c, R.drawable.ic_rec, "Record", v -> onRecord());
        recIcon = (ImageView) ((LinearLayout) rec).getChildAt(0);
        recLabel = (TextView) ((LinearLayout) rec).getChildAt(1);
        panel.addView(rec);
        panel.addView(action(c, R.drawable.ic_shot, "Shot", v -> onShot()));
        panel.addView(action(c, R.drawable.ic_home, "Home", v -> onHome()));
        panel.addView(action(c, R.drawable.ic_exit, "Exit", v -> onExit()));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.setMargins(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        root.addView(panel, plp);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = Prefs.ballY(this);
        wm.addView(root, lp);
        attachDrag();
    }

    private View action(Context c, int icon, String label, View.OnClickListener l) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
        col.addView(Ui.icon(c, icon, Ui.PINK, 24));
        TextView t = Ui.text(c, label, 10, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        col.setBackground(Ui.ripple(null));
        col.setOnClickListener(l);
        Ui.clickable(col);
        return col;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachDrag() {
        final int slop = ViewConfiguration.get(wctx).getScaledTouchSlop();
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            panel.setVisibility(View.GONE);
                        }
                        if (dragging) {
                            lp.x = Math.max(0, (int) (startX + dx));
                            lp.y = Math.max(0, (int) (startY + dy));
                            safeUpdate();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) snapToEdge();
                        else {
                            v.performClick();
                            togglePanel();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) snapToEdge();
                        return true;
                    default:
                        return false;
                }
            }
        });
        ball.setOnClickListener(v -> { /* handled in the touch listener */ });
    }

    @SuppressWarnings("deprecation")
    private int screenWidth() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return b.width();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private void snapToEdge() {
        int w = screenWidth();
        int viewW = root.getWidth();
        onRight = lp.x + viewW / 2 > w / 2;
        lp.x = onRight ? Math.max(0, w - viewW) : 0;
        root.setLayoutDirection(onRight ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        safeUpdate();
        Prefs.setBallY(this, lp.y);
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (onRight) {
            // Keep the ball pinned to the right edge after the width changes.
            root.post(() -> {
                lp.x = Math.max(0, screenWidth() - root.getWidth());
                safeUpdate();
            });
        }
    }

    private void safeUpdate() {
        try {
            if (root != null && root.isAttachedToWindow()) wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {}
    }

    // ------------------------------------------------------------- actions

    private void onRecord() {
        panel.setVisibility(View.GONE);
        if (RecState.isRecording()) {
            RecorderService.sendAction(this, RecorderService.ACTION_STOP);
        } else {
            openProjection(ProjectionActivity.MODE_RECORD);
        }
    }

    private void onShot() {
        panel.setVisibility(View.GONE);
        if (RecState.isRecording()) {
            // Let the panel disappear before the frame is grabbed.
            handler.postDelayed(() -> RecorderService.sendAction(this, RecorderService.ACTION_SHOT), 250);
        } else {
            openProjection(ProjectionActivity.MODE_SHOT);
        }
    }

    private void openProjection(String mode) {
        Intent i = new Intent(this, ProjectionActivity.class)
                .putExtra(ProjectionActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i); // allowed from the background because we hold "Display over other apps"
        } catch (RuntimeException ignored) {}
    }

    private void onHome() {
        panel.setVisibility(View.GONE);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (RuntimeException ignored) {}
    }

    private void onExit() {
        ExitReceiver.exitAll(this);
        stopSelf();
    }

    // --------------------------------------------------------------- state

    @Override public void onRecStateChanged() {
        updateState();
        handler.removeCallbacks(tick);
        if (RecState.isRecording()) handler.post(tick);
    }

    private void updateState() {
        if (root == null) return;
        boolean rec = RecState.isRecording();
        ballIcon.setVisibility(rec ? View.GONE : View.VISIBLE);
        ballTimer.setVisibility(rec ? View.VISIBLE : View.GONE);
        if (rec) ballTimer.setText(RecState.format(RecState.elapsedMs()));
        recIcon.setImageResource(rec ? R.drawable.ic_stop : R.drawable.ic_rec);
        recLabel.setText(rec ? "Stop" : "Record");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        RecState.remove(this);
        if (root != null) {
            try { wm.removeView(root); } catch (RuntimeException ignored) {}
            root = null;
        }
        super.onDestroy();
    }
}
