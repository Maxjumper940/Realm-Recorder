package com.realmvc.recorder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Neon purple-pink 3D grid floor, like Veylin's Realm VC banner. */
public class NeonGridView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private float phase;
    private ValueAnimator anim;
    private boolean animate;
    private float horizon = 0.55f;

    public NeonGridView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 1.2f));
    }

    public void setAnimated(boolean on) {
        animate = on;
        if (on && isAttachedToWindow()) startAnim();
        if (!on && anim != null) anim.cancel();
    }

    public void setHorizon(float fraction) {
        horizon = fraction;
        invalidate();
    }

    private void startAnim() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1600);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animate) startAnim();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        sky.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF07040A, 0xFF1A0726, 0xFF2A0B3A, 0xFF07040A},
                new float[]{0f, horizon * 0.8f, horizon, 1f}, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(w / 2f, h * horizon, Math.max(1, w * 0.7f),
                new int[]{0x66FF4FA8, 0x229B5CFF, 0x00000000}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        c.drawRect(0, 0, w, h, sky);
        c.drawRect(0, 0, w, h, glow);
        float hy = h * horizon;
        float cx = w / 2f;
        // Converging vertical lines.
        line.setColor(0x88C86BFF);
        int n = 14;
        for (int i = -n; i <= n; i++) {
            float bottomX = cx + i * (w / 5f);
            c.drawLine(cx + i * (w / 60f), hy, bottomX, h, line);
        }
        // Horizontal lines, spaced to look like perspective, scrolling toward the viewer.
        for (int i = 0; i < 12; i++) {
            float t = (i + phase) / 12f;
            float y = hy + (h - hy) * t * t;
            int a = (int) (40 + 180 * t);
            line.setColor((a << 24) | 0x00FF4FA8);
            c.drawLine(0, y, w, y, line);
        }
        // Horizon glow line.
        line.setColor(0xFFFF4FA8);
        c.drawLine(0, hy, w, hy, line);
    }
}
