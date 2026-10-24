package com.jay.calmhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds launchable apps and renders their tile art. Runs on the loader thread, never the UI thread. */
final class AppRepo {
    static final int ART_W = 320, ART_H = 180;

    private AppRepo() {}

    /** TV (leanback) entries first; phone-style launcher entries only for apps without one. */
    static List<AppEntry> query(PackageManager pm, String self) {
        List<AppEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(pm, out, seen, self, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        add(pm, out, seen, self, Intent.CATEGORY_LAUNCHER, false);
        return out;
    }

    private static void add(PackageManager pm, List<AppEntry> out, Set<String> seen, String self,
                            String category, boolean leanback) {
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo r : found) {
            ActivityInfo a = r.activityInfo;
            if (a == null || a.packageName.equals(self) || !seen.add(a.packageName)) continue;
            CharSequence label = null;
            try {
                label = r.loadLabel(pm);
            } catch (RuntimeException ignored) {
                // A broken label resource must not hide the app; AppEntry falls back to the package.
            }
            out.add(new AppEntry(a.packageName, a.name, label == null ? null : label.toString(), leanback));
        }
    }

    /** Banner if the app has one, otherwise its icon on a card tinted with the icon's own colour. */
    static TileArt makeArt(PackageManager pm, AppEntry e) {
        ComponentName cn = new ComponentName(e.pkg, e.activity);
        Drawable banner = null, icon = null;
        try {
            banner = pm.getActivityBanner(cn);
        } catch (Exception ignored) {
            // fall through to the application banner
        }
        if (banner == null) {
            try {
                banner = pm.getApplicationBanner(e.pkg);
            } catch (Exception ignored) {
                // fall through to the icon
            }
        }
        if (banner == null) {
            try {
                icon = pm.getActivityIcon(cn);
            } catch (Exception ignored) {
                // try the application icon next
            }
            if (icon == null) {
                try {
                    icon = pm.getApplicationIcon(e.pkg);
                } catch (Exception ignored) {
                    // no art at all: a plain labelled card
                }
            }
        }
        Bitmap b = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int glowSource;
        if (banner != null) {
            c.drawColor(0xFF15161C);
            banner.setBounds(0, 0, ART_W, ART_H);
            banner.draw(c);
            glowSource = average(b);
        } else {
            glowSource = drawIconCard(c, icon, e.label);
        }
        return new TileArt(b, glowSource, banner != null);
    }

    private static int drawIconCard(Canvas c, Drawable icon, String label) {
        int tint = 0x3A3A46;
        Bitmap ib = null;
        if (icon != null) {
            ib = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 96, 96);
            icon.draw(new Canvas(ib));
            tint = average(ib);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new LinearGradient(0, 0, ART_W, ART_H,
                0xFF000000 | Ambient.mix(tint, 0x000000, 0.62f),
                0xFF000000 | Ambient.mix(tint, 0x000000, 0.86f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, ART_W, ART_H, p);
        p.setShader(null);
        if (ib != null) {
            c.drawBitmap(ib, null, new RectF(ART_W / 2f - 38, 28, ART_W / 2f + 38, 104), p);
            ib.recycle();
        }
        p.setColor(0xEBFFFFFF);
        p.setTextSize(23);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.drawText(fit(label, p, ART_W - 36), ART_W / 2f, 148, p);
        return tint;
    }

    /** Average visible colour, sampled on a grid and weighted by alpha. */
    static int average(Bitmap b) {
        long r = 0, g = 0, bl = 0, w = 0;
        int sx = Math.max(1, b.getWidth() / 32), sy = Math.max(1, b.getHeight() / 18);
        for (int y = sy / 2; y < b.getHeight(); y += sy) {
            for (int x = sx / 2; x < b.getWidth(); x += sx) {
                int p = b.getPixel(x, y);
                int a = (p >>> 24);
                if (a < 32) continue;
                r += ((p >> 16) & 0xFF) * a;
                g += ((p >> 8) & 0xFF) * a;
                bl += (p & 0xFF) * a;
                w += a;
            }
        }
        if (w == 0) return 0x3A3A46;
        return (int) ((r / w) << 16 | (g / w) << 8 | (bl / w));
    }

    /** Shortens text with an ellipsis so it fits max pixels. */
    static String fit(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String dots = "…";
        float dw = p.measureText(dots);
        int n = s.length();
        while (n > 1 && p.measureText(s, 0, n) + dw > max) n--;
        return s.substring(0, n).trim() + dots;
    }
}
