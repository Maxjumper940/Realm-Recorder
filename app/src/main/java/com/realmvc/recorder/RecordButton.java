package com.realmvc.recorder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Big round pink record button with a neon glow; turns into a stop square while recording. */
public class RecordButton extends View {
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean recording;
    private float pulse;
    private ValueAnimator anim;

    public RecordButton(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter needs software rendering
        glow.setColor(0xAAFF1F7A);
        glow.setMaskFilter(new BlurMaskFilter(Ui.dp(c, 18), BlurMaskFilter.Blur.NORMAL));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 3));
        ring.setColor(0xFFFFB3DA);
        inner.setColor(0xFFFFFFFF);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Record");
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    public void setRecording(boolean r) {
        if (recording == r) return;
        recording = r;
        setContentDescription(r ? "Stop recording" : "Record");
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float r = Math.min(w, h) / 2f;
        fill.setShader(new RadialGradient(w / 2f, h / 2f - r * 0.3f, Math.max(1, r),
                new int[]{0xFFFF7CC0, 0xFFFF4FA8, 0xFFFF1F7A}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.72f;
        float glowR = r * (1.06f + 0.08f * pulse);
        c.drawCircle(cx, cy, glowR, glow);
        c.drawCircle(cx, cy, r, fill);
        c.drawCircle(cx, cy, r + Ui.dp(getContext(), 6), ring);
        if (recording) {
            float s = r * 0.38f;
            rect.set(cx - s, cy - s, cx + s, cy + s);
            c.drawRoundRect(rect, s * 0.25f, s * 0.25f, inner);
        } else {
            c.drawCircle(cx, cy, r * 0.32f, inner);
        }
    }
}
