package com.jay.calmhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Drawing helpers for the Liquid Glass look. Everything draws in the design's 1920x1080 units.
 * Glass is a translucent diagonal gradient, a thin border and a bright top-left inner edge. There
 * is no real backdrop blur: the scenes behind are already soft, and a blur per tile would crawl
 * on a 2018 Fire Stick.
 */
final class Gfx {
    static final int ICON_GEAR = 0, ICON_SLIDERS = 1, ICON_PREV = 2, ICON_NEXT = 3, ICON_GRID = 4;

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Matrix m = new Matrix();
    private final RectF r = new RectF();
    private final Path path = new Path();
    private LinearGradient glassFill;
    private float glassTint = -1f;
    private final LinearGradient edge = new LinearGradient(0, 0, 1, 1,
            new int[]{0xA6FFFFFF, 0x14FFFFFF, 0x2EFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

    Gfx() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** A scratch matrix for callers that scale a shader to a rectangle. */
    Matrix matrix() {
        return m;
    }

    /** The design's glass tint: a = t+.08 at the top-left down to .4t, then back up to t+.05. */
    void setTint(float t) {
        if (t == glassTint) return;
        glassTint = t;
        glassFill = new LinearGradient(0, 0, 1, 1,
                new int[]{a(t + 0.08f), a(t), a(t * 0.4f), a(t + 0.05f)},
                new float[]{0f, 0.4f, 0.7f, 1f}, Shader.TileMode.CLAMP);
    }

    private static int a(float alpha) {
        return (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xFFFFFF;
    }

    /** A glass panel. `lift` 0..1 adds the brighter, higher look of a focused tile. */
    void glass(Canvas c, float l, float t, float rt, float b, float radius, float lift) {
        r.set(l, t, rt, b);
        fill.setShader(null);
        fill.setColor(0x000000);
        for (int k = 2; k >= 1; k--) {
            float grow = k * (6f + 8f * lift), drop = 10f + 14f * lift;
            fill.setColor(((int) ((k == 1 ? 0x14 : 0x0A) + 0x10 * lift) << 24));
            c.drawRoundRect(l - grow, t - grow + drop, rt + grow, b + grow + drop, radius + grow, radius + grow, fill);
        }
        m.setScale(rt - l, b - t);
        m.postTranslate(l, t);
        glassFill.setLocalMatrix(m);
        fill.setShader(glassFill);
        c.drawRoundRect(r, radius, radius, fill);
        fill.setShader(null);
        if (lift > 0f) {
            fill.setColor(((int) (0x38 * lift) << 24) | 0xFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
        }
        line.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(0x52FFFFFF);
        r.inset(0.5f, 0.5f);
        c.drawRoundRect(r, radius, radius, line);
        edge.setLocalMatrix(m);
        line.setShader(edge);
        line.setStrokeWidth(1.5f);
        r.inset(0.75f, 0.75f);
        c.drawRoundRect(r, radius - 1f, radius - 1f, line);
        line.setShader(null);
    }

    /** White ring around the focused item, 3 units wide, as in the design. */
    void ring(Canvas c, float l, float t, float rt, float b, float radius) {
        line.setShader(null);
        line.setStrokeWidth(3f);
        line.setColor(0xFFFFFFFF);
        r.set(l - 1.5f, t - 1.5f, rt + 1.5f, b + 1.5f);
        c.drawRoundRect(r, radius + 1.5f, radius + 1.5f, line);
    }

    void label(Canvas c, String s, float x, float y, float size, Typeface face, int argb, Paint.Align align) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setColor(argb);
        text.setTextAlign(align);
        text.setLetterSpacing(0f);
        c.drawText(s, x, y, text);
    }

    float measure(String s, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setLetterSpacing(0f);
        return text.measureText(s);
    }

    /** Stroke icons on the design's 24-unit grid, centred at (cx, cy) and drawn `size` units wide. */
    void icon(Canvas c, int id, float cx, float cy, float size, int argb, float stroke) {
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(size / 24f, size / 24f);
        line.setShader(null);
        line.setColor(argb);
        line.setStrokeWidth(stroke);
        path.reset();
        switch (id) {
            case ICON_SLIDERS:
                path.moveTo(4, 7);
                path.lineTo(14, 7);
                path.moveTo(18, 7);
                path.lineTo(20, 7);
                path.moveTo(4, 17);
                path.lineTo(8, 17);
                path.moveTo(12, 17);
                path.lineTo(20, 17);
                path.addCircle(16, 7, 2, Path.Direction.CW);
                path.addCircle(10, 17, 2, Path.Direction.CW);
                break;
            case ICON_GEAR:
                path.addCircle(12, 12, 6.2f, Path.Direction.CW);
                path.addCircle(12, 12, 2.4f, Path.Direction.CW);
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI / 4 * k;
                    path.moveTo(12 + (float) Math.cos(ang) * 6.2f, 12 + (float) Math.sin(ang) * 6.2f);
                    path.lineTo(12 + (float) Math.cos(ang) * 9.2f, 12 + (float) Math.sin(ang) * 9.2f);
                }
                break;
            case ICON_PREV:
                path.moveTo(15, 6);
                path.lineTo(9, 12);
                path.lineTo(15, 18);
                break;
            case ICON_NEXT:
                path.moveTo(9, 6);
                path.lineTo(15, 12);
                path.lineTo(9, 18);
                break;
            default:
                float[] o = {3f, 14f};
                for (float y : o) {
                    for (float x : o) path.addRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, Path.Direction.CW);
                }
                break;
        }
        c.drawPath(path, line);
        c.restore();
    }
}
