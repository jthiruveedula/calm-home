package com.jay.calmhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Shader;

/**
 * An app's icon, pre-rendered square, with a shader so tiles can draw it rounded without clipping,
 * and the icon's most vivid colour for the hero card's glow.
 */
final class TileArt {
    final Bitmap bitmap;
    final BitmapShader shader;
    final int tint;

    TileArt(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        this.tint = vivid(bitmap);
    }

    /** Average of opaque pixels weighted by saturation, so a logo's colour wins over its background. */
    static int vivid(Bitmap b) {
        double r = 0, g = 0, bl = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < b.getHeight(); y += 4) {
            for (int x = 0; x < b.getWidth(); x += 4) {
                int c = b.getPixel(x, y);
                int a = Color.alpha(c);
                if (a < 128) continue;
                Color.colorToHSV(c, hsv);
                double w = 0.05 + hsv[1] * hsv[1] * hsv[2];
                r += Color.red(c) * w;
                g += Color.green(c) * w;
                bl += Color.blue(c) * w;
                wsum += w;
            }
        }
        if (wsum <= 0) return 0xFF3A4A7A;
        int c = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
