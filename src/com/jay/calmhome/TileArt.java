package com.jay.calmhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;

/** An app's icon, pre-rendered square, with a shader so tiles can draw it rounded without clipping. */
final class TileArt {
    final Bitmap bitmap;
    final BitmapShader shader;

    TileArt(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }
}
