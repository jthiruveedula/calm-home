package com.jay.calmhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds launchable apps and renders their icons. Runs on the loader thread, never the UI thread. */
final class AppRepo {
    static final int ICON = 128;
    private static final int[] TINTS = {0xFF2E5BFF, 0xFFFF7A59, 0xFF8A4DFF, 0xFF1F8A5B, 0xFFE0874A, 0xFF0E9FB0};

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

    /** The app's icon, or a coloured square with its initials when it has none. */
    static TileArt makeArt(PackageManager pm, AppEntry e) {
        Drawable icon = null;
        try {
            icon = pm.getActivityIcon(new ComponentName(e.pkg, e.activity));
        } catch (Exception ignored) {
            // try the application icon next
        }
        if (icon == null) {
            try {
                icon = pm.getApplicationIcon(e.pkg);
            } catch (Exception ignored) {
                // initials card below
            }
        }
        Bitmap b = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (icon != null) {
            icon.setBounds(0, 0, ICON, ICON);
            icon.draw(c);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(TINTS[Math.abs(e.pkg.hashCode()) % TINTS.length]);
            c.drawRoundRect(new RectF(0, 0, ICON, ICON), ICON * 0.26f, ICON * 0.26f, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(ICON * 0.38f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            c.drawText(initials(e.label), ICON / 2f, ICON / 2f + ICON * 0.13f, p);
        }
        return new TileArt(b);
    }

    /** Up to two capital letters from a label's words. */
    static String initials(String label) {
        StringBuilder sb = new StringBuilder();
        for (String w : label.trim().split("\\s+")) {
            if (w.isEmpty() || !Character.isLetterOrDigit(w.codePointAt(0))) continue;
            sb.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
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
