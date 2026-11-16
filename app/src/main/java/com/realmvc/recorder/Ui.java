package com.realmvc.recorder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers for the pink Realm VC look. */
final class Ui {
    static final int BG = 0xFF07040A;
    static final int SURFACE = 0xFF140A1C;
    static final int SURFACE2 = 0xFF1E1029;
    static final int STROKE = 0xFF33204A;
    static final int PINK = 0xFFFF4FA8;
    static final int HOT = 0xFFFF1F7A;
    static final int LAVENDER = 0xFFD9B8FF;
    static final int PURPLE = 0xFF9B5CFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFB9A6C9;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static GradientDrawable gradient(int[] colors, float radiusPx, GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x55FF4FA8), content, null);
    }

    static Drawable card(Context c) {
        return round(SURFACE, dp(c, 20), STROKE, dp(c, 1));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** The "𝑴𝒊𝒔𝒔 𝑽𝒆𝒚𝒍𝒊𝒏" style: bold italic serif, pink-to-lavender fill with a soft glow. */
    static TextView brand(Context c, String s, float sp) {
        TextView t = new TextView(c) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                getPaint().setShader(new LinearGradient(0, 0, Math.max(1, w), 0,
                        new int[]{0xFFFFE3F3, PINK, LAVENDER}, null, Shader.TileMode.CLAMP));
            }
        };
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(dp(c, 14), 0, 0, 0xCCFF4FA8);
        int pad = dp(c, 6);
        t.setPadding(pad, 0, pad + dp(c, 4), 0); // room for the italic overhang and glow
        return t;
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    static TextView pill(Context c, String s) {
        TextView t = text(c, s, 12, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setBackground(round(0x33FF4FA8, dp(c, 999), 0x88FF4FA8, dp(c, 1)));
        return t;
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 14, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        Drawable bg = primary
                ? gradient(new int[]{PINK, HOT}, dp(c, 999), GradientDrawable.Orientation.LEFT_RIGHT)
                : round(SURFACE2, dp(c, 999), 0x88FF4FA8, dp(c, 1));
        b.setBackground(ripple(bg));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpMargins(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static void clickable(View v) {
        v.setClickable(true);
        v.setFocusable(true);
    }
}
