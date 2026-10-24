package com.jay.calmhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Paints the design's background: each scene is a dark base with three huge soft colour blobs.
 * Blobs are radial gradients (the design blurs ellipses by 110px; a gradient looks the same and
 * costs almost nothing). Draws in 1920x1080 design units.
 */
final class Background {
    static final float W = 1920f, H = 1080f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final float[] rect = new float[4];
    private final float[] tf = new float[3];
    private final RadialGradient[][] blob = new RadialGradient[Scenes.ALL.length][];
    private final LinearGradient vignette = new LinearGradient(0, 0, 0, H,
            new int[]{0x40000000, 0x00000000, 0x8C000000}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);

    Background() {
        for (int s = 0; s < Scenes.ALL.length; s++) {
            Scenes.Blob[] bl = Scenes.ALL[s].blobs;
            blob[s] = new RadialGradient[bl.length];
            for (int i = 0; i < bl.length; i++) {
                int c = 0xFF000000 | bl[i].color;
                blob[s][i] = new RadialGradient(0f, 0f, 1f,
                        new int[]{c, (c & 0x00FFFFFF) | 0xC0000000, c & 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
        }
    }

    /**
     * @param seconds animation clock
     * @param motion  index into Options.MOTION
     * @param from    scene fading out
     * @param to      scene fading in
     * @param fadeMs  time since the scene changed
     */
    void draw(Canvas c, double seconds, int motion, int from, int to, long fadeMs) {
        c.drawColor(0xFF05070D);
        for (int s = 0; s < Scenes.ALL.length; s++) {
            float op = Scenes.opacity(s, from, to, fadeMs);
            if (op <= 0.003f) continue;
            drawScene(c, s, op, seconds, motion);
        }
        paint.setShader(vignette);
        paint.setAlpha(255);
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }

    private void drawScene(Canvas c, int s, float opacity, double seconds, int motion) {
        Scenes.Scene sc = Scenes.ALL[s];
        int alpha = Math.round(opacity * 255f);
        c.save();
        c.translate(W / 2f, H / 2f);
        if (motion == 0) {
            Scenes.kenBurns(seconds, tf);
            c.scale(tf[0], tf[0]);
            c.translate(tf[1] * W * 1.2f, tf[2] * H * 1.2f);
        } else if (motion == 1) {
            Scenes.drift(seconds, tf);
            c.translate(tf[0] * W * 1.2f, tf[1] * H * 1.2f);
            c.rotate(tf[2]);
        }
        c.translate(-W / 2f, -H / 2f);
        paint.setShader(null);
        paint.setColor(0xFF000000 | sc.base);
        paint.setAlpha(alpha);
        c.drawRect(-0.1f * W, -0.1f * H, 1.1f * W, 1.1f * H, paint);
        for (int i = 0; i < sc.blobs.length; i++) {
            Scenes.blobRect(sc.blobs[i], W, H, rect);
            m.setScale(rect[2] / 2f, rect[3] / 2f);
            m.postTranslate(rect[0] + rect[2] / 2f, rect[1] + rect[3] / 2f);
            blob[s][i].setLocalMatrix(m);
            paint.setShader(blob[s][i]);
            paint.setAlpha(Math.round(alpha * 0.92f));
            c.drawRect(rect[0] - rect[2] * 0.1f, rect[1] - rect[3] * 0.1f,
                    rect[0] + rect[2] * 1.1f, rect[1] + rect[3] * 1.1f, paint);
        }
        paint.setShader(null);
        c.restore();
    }
}
