package com.jay.calmhome;

/**
 * D-pad focus by geometry, one engine for every screen. Left/Right only move along the same row;
 * Up/Down prefer what is straight above or below and fall back to the nearest tile, keeping the
 * column the user was in (prefX). Pure Java, tested for reachability and fuzzed.
 */
public final class SpatialNav {
    private SpatialNav() {}

    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    public static final class Box {
        public final float l, t, r, b;

        public Box(float l, float t, float r, float b) {
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }

        public float cx() { return (l + r) / 2f; }

        public float cy() { return (t + b) / 2f; }
    }

    /** @return the index to focus next, or -1 when there is nothing in that direction. */
    public static int find(Box[] boxes, int cur, int dir, float prefX) {
        if (boxes == null || cur < 0 || cur >= boxes.length) return -1;
        Box s = boxes[cur];
        boolean vertical = dir == UP || dir == DOWN;
        // Vertical moves aim at the remembered column: a beam as wide as the source, centred on prefX.
        float half = (s.r - s.l) / 2f;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        boolean bestBeam = false;
        for (int i = 0; i < boxes.length; i++) {
            if (i == cur) continue;
            Box c = boxes[i];
            if (!ahead(s, c, dir)) continue;
            boolean beam = vertical ? overlap(prefX - half, prefX + half, c.l, c.r) : overlap(s.t, s.b, c.t, c.b);
            if (!vertical && !beam) continue;
            float major = gap(s, c, dir);
            float minor = vertical ? Math.abs(c.cx() - prefX) : Math.abs(c.cy() - s.cy());
            float score = 13f * major * major + minor * minor;
            if (best < 0 || (beam && !bestBeam) || (beam == bestBeam && score < bestScore)) {
                best = i;
                bestScore = score;
                bestBeam = beam;
            }
        }
        return best;
    }

    private static boolean ahead(Box s, Box c, int dir) {
        switch (dir) {
            case DOWN: return c.cy() > s.cy() && c.t > s.t;
            case UP: return c.cy() < s.cy() && c.t < s.t;
            case RIGHT: return c.cx() > s.cx() && c.l > s.l;
            case LEFT: return c.cx() < s.cx() && c.l < s.l;
            default: return false;
        }
    }

    private static float gap(Box s, Box c, int dir) {
        switch (dir) {
            case DOWN: return Math.max(0f, c.t - s.b);
            case UP: return Math.max(0f, s.t - c.b);
            case RIGHT: return Math.max(0f, c.l - s.r);
            default: return Math.max(0f, s.l - c.r);
        }
    }

    private static boolean overlap(float a0, float a1, float b0, float b1) {
        return Math.min(a1, b1) - Math.max(a0, b0) > 1f;
    }

    /**
     * Scroll offset (added to y, so 0 or negative) that keeps the box [top, bottom] on screen with
     * the given margins, never scrolling past the content.
     */
    public static float keepVisible(float top, float bottom, float cam, float viewH,
                                    float marginTop, float marginBottom, float contentH) {
        float min = Math.min(0f, viewH - contentH);
        float c = cam;
        if (top + c < marginTop) c = marginTop - top;
        if (bottom + c > viewH - marginBottom) c = viewH - marginBottom - bottom;
        return Math.max(min, Math.min(0f, c));
    }
}
