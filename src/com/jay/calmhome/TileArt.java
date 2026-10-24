package com.jay.calmhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;

/** A tile's pre-rendered 16:9 artwork, its shader for rounded drawing, and the glow colour it casts. */
final class TileArt {
    final Bitmap bitmap;
    final BitmapShader shader;
    final int glow;
    final boolean banner;

    TileArt(Bitmap bitmap, int averageRgb, boolean banner) {
        this.bitmap = bitmap;
        this.shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        this.glow = Ambient.glowFrom(averageRgb);
        this.banner = banner;
    }
}
