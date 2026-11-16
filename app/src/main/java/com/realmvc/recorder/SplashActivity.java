package com.realmvc.recorder;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pink Realm VC splash: neon grid, glowing "Realm VC" title, "Screen + the voices". */
public class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        NeonGridView grid = new NeonGridView(this);
        grid.setHorizon(0.62f);
        grid.setAnimated(true);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setBackground(Ui.round(0x33FF4FA8, Ui.dp(this, 999), 0x99FF4FA8, Ui.dp(this, 1)));
        col.addView(logo, Ui.lp(Ui.dp(this, 112), Ui.dp(this, 112)));

        TextView title = Ui.brand(this, "Realm VC", 52);
        col.addView(title, Ui.lpMargins(this, -2, -2, 0, 18, 0, 0));

        TextView sub = Ui.text(this, getString(R.string.tagline), 18, Ui.LAVENDER, false);
        sub.setLetterSpacing(0.08f);
        sub.setShadowLayer(Ui.dp(this, 8), 0, 0, 0x99D9B8FF);
        col.addView(sub, Ui.lpMargins(this, -2, -2, 0, 4, 0, 0));

        TextView cap = Ui.pill(this, "REALM RECORDER");
        cap.setLetterSpacing(0.2f);
        col.addView(cap, Ui.lpMargins(this, -2, -2, 0, 22, 0, 0));

        View bar = new View(this);
        bar.setBackground(Ui.gradient(new int[]{Ui.PINK, Ui.HOT}, Ui.dp(this, 3),
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT));
        bar.setPivotX(0);
        bar.setScaleX(0f);
        col.addView(bar, Ui.lpMargins(this, Ui.dp(this, 160), Ui.dp(this, 4), 0, 30, 0, 0));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        lp.bottomMargin = Ui.dp(this, 60);
        root.addView(col, lp);
        setContentView(root);

        // Entrance animation.
        logo.setScaleX(0.6f); logo.setScaleY(0.6f); logo.setAlpha(0f);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
        title.setAlpha(0f); title.setTranslationY(Ui.dp(this, 24));
        title.animate().alpha(1f).translationY(0).setStartDelay(250).setDuration(650).setInterpolator(new DecelerateInterpolator()).start();
        sub.setAlpha(0f);
        sub.animate().alpha(1f).setStartDelay(600).setDuration(500).start();
        cap.setAlpha(0f);
        cap.animate().alpha(1f).setStartDelay(800).setDuration(500).start();
        ObjectAnimator grow = ObjectAnimator.ofFloat(bar, View.SCALE_X, 0f, 1f);
        grow.setStartDelay(300);
        grow.setDuration(1500);
        grow.start();

        handler.postDelayed(this::openHome, 1900);
        root.setOnClickListener(v -> openHome());
    }

    private void openHome() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
