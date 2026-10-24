package com.jay.calmhome;

/**
 * Where the Home row goes, in 1920x1080 units: a large hero card for the app to continue, the
 * other favourites beside it, then All apps. Pure Java so the layout is tested for fit and reach.
 */
public final class HomeLayout {
    private HomeLayout() {}

    public static final float LEFT = 96f, RIGHT = 1824f, BOTTOM = 1006f;
    public static final float HERO_W = 560f, HERO_H = 316f;
    public static final float TILE_W = 176f, TILE_H = 196f, GAP = 16f;
    public static final int MAX_DOCK = 6;

    /**
     * Boxes for n favourites (index 0 is the hero) followed by the All apps tile, so the array has
     * n + 1 entries. n is clamped to [0, MAX_DOCK].
     */
    public static SpatialNav.Box[] row(int n) {
        n = Math.max(0, Math.min(MAX_DOCK, n));
        SpatialNav.Box[] out = new SpatialNav.Box[n + 1];
        float x = LEFT;
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                out[0] = new SpatialNav.Box(x, BOTTOM - HERO_H, x + HERO_W, BOTTOM);
                x += HERO_W + 2f * GAP;
            } else {
                out[i] = new SpatialNav.Box(x, BOTTOM - TILE_H, x + TILE_W, BOTTOM);
                x += TILE_W + GAP;
            }
        }
        out[n] = new SpatialNav.Box(x, BOTTOM - TILE_H, x + TILE_W, BOTTOM);
        return out;
    }

    /** Top of the row, where its heading sits above. */
    public static float top(int n) {
        return n > 0 ? BOTTOM - HERO_H : BOTTOM - TILE_H;
    }
}
