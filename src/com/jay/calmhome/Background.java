package com.jay.calmhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Paints the design's background. Each scene (a dark base with three huge soft colour blobs) is
 * rendered once into a small bitmap, because it is blurry by nature; every frame then draws one
 * scaled bitmap per visible scene. That keeps a 2018 Fire Stick's GPU nearly idle while Ken Burns
 * and Drift move. Draws in 1920x1080 design units.
 */
final class Background {
    static final float W = 1920f, H = 1080f;
    /** The scene box is 120% of the screen; this is its bitmap size (a 3.33x downscale). */
    private static final int BW = 576, BH = 324;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF(-0.1f * W, -0.1f * H, 1.1f * W, 1.1f * H);
    private final float[] tf = new float[3];
    private final Bitmap[] cache = new Bitmap[Scenes.ALL.length];
    private final LinearGradient vignette = new LinearGradient(0, 0, 0, H,
            new int[]{0x40000000, 0x00000000, 0x8C000000}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);

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
        paint.setAlpha(Math.round(opacity * 255f));
        c.drawBitmap(bitmap(s), null, dst, paint);
        c.restore();
    }

    private Bitmap bitmap(int s) {
        if (cache[s] == null) cache[s] = render(Scenes.ALL[s]);
        return cache[s];
    }

    /** One scene's whole 120% box at BWxBH: base colour plus three radial blobs. */
    private static Bitmap render(Scenes.Scene sc) {
        Bitmap b = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF000000 | sc.base);
        // Blob rectangles are in screen units, offset by the box's -10%; the bitmap spans 120%.
        c.scale(BW / (W * 1.2f), BH / (H * 1.2f));
        c.translate(0.1f * W, 0.1f * H);
        float[] rect = new float[4];
        Matrix m = new Matrix();
        for (Scenes.Blob bl : sc.blobs) {
            Scenes.blobRect(bl, W, H, rect);
            int col = 0xFF000000 | bl.color;
            RadialGradient rg = new RadialGradient(0f, 0f, 1f,
                    new int[]{col, (col & 0x00FFFFFF) | 0xC0000000, col & 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            m.setScale(rect[2] / 2f, rect[3] / 2f);
            m.postTranslate(rect[0] + rect[2] / 2f, rect[1] + rect[3] / 2f);
            rg.setLocalMatrix(m);
            p.setShader(rg);
            p.setAlpha(235);
            c.drawRect(rect[0] - rect[2] * 0.1f, rect[1] - rect[3] * 0.1f,
                    rect[0] + rect[2] * 1.1f, rect[1] + rect[3] * 1.1f, p);
        }
        return b;
    }
}
