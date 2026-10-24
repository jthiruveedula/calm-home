package com.jay.calmhome;

/** The choices on the Customize screen, with safe decoding of whatever was saved. Pure Java. */
public final class Options {
    private Options() {}

    public static final String[] MOTION = {"Ken Burns", "Drift", "Still"};
    public static final String[] INTERVAL_LABELS = {"5 s", "10 s", "30 s", "1 min"};
    public static final long[] INTERVAL_MS = {5000L, 10000L, 30000L, 60000L};
    public static final String[] GLASS = {"Clear", "Regular", "Frosted"};
    public static final float[] GLASS_TINT = {0.03f, 0.05f, 0.09f};
    public static final int[] ACCENT = {0xFF7FD1FF, 0xFFFFB86B, 0xFFB9F27C, 0xFFF59AD0};
    public static final String[] SORT = {"Recently opened", "A–Z", "Pinned first"};

    public static final int MOTION_DEFAULT = 0, INTERVAL_DEFAULT = 1, GLASS_DEFAULT = 1;

    public static int idx(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }

    /** "101" -> {true, false, true}; at least one scene is always on. */
    public static boolean[] sceneMask(String raw, int n) {
        boolean[] m = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            m[i] = raw == null || i >= raw.length() ? true : raw.charAt(i) == '1';
            any |= m[i];
        }
        if (!any && n > 0) m[0] = true;
        return m;
    }

    public static String maskString(boolean[] m) {
        StringBuilder sb = new StringBuilder();
        for (boolean b : m) sb.append(b ? '1' : '0');
        return sb.toString();
    }

    /** Flips scene i, refusing to turn the last one off. */
    public static boolean[] toggleScene(boolean[] m, int i) {
        boolean[] out = m.clone();
        if (i < 0 || i >= out.length) return out;
        int on = 0;
        for (boolean b : out) on += b ? 1 : 0;
        if (out[i] && on <= 1) return out;
        out[i] = !out[i];
        return out;
    }
}
