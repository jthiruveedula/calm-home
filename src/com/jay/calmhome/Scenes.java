package com.jay.calmhome;

/**
 * The three background "photos" of the Liquid Glass design: a dark base with three soft colour
 * blobs, slowly moving and cross-fading. Pure maths so it can be tested without a screen.
 */
public final class Scenes {
    private Scenes() {}

    public static final class Blob {
        public final float l, t, w, h; // fractions of the (oversized) scene box
        public final int color;

        public Blob(float l, float t, float w, float h, int color) {
            this.l = l;
            this.t = t;
            this.w = w;
            this.h = h;
            this.color = color;
        }
    }

    public static final class Scene {
        public final String name;
        public final int base;
        public final Blob[] blobs;

        public Scene(String name, int base, Blob... blobs) {
            this.name = name;
            this.base = base;
            this.blobs = blobs;
        }
    }

    public static final Scene[] ALL = {
            new Scene("Dusk", 0x0B1430,
                    new Blob(0.05f, 0.10f, 0.55f, 0.70f, 0x2E5BFF),
                    new Blob(0.50f, 0.40f, 0.50f, 0.60f, 0xFF7A59),
                    new Blob(0.35f, -0.10f, 0.40f, 0.50f, 0x8A4DFF)),
            new Scene("Forest", 0x07140F,
                    new Blob(0.00f, 0.30f, 0.60f, 0.70f, 0x1F8A5B),
                    new Blob(0.55f, 0.00f, 0.45f, 0.55f, 0xC8E86B),
                    new Blob(0.40f, 0.55f, 0.50f, 0.50f, 0x0E5E6F)),
            new Scene("Desert", 0x1A0F0A,
                    new Blob(0.10f, 0.00f, 0.50f, 0.60f, 0xE0874A),
                    new Blob(0.55f, 0.20f, 0.45f, 0.55f, 0xF2C66D),
                    new Blob(0.20f, 0.55f, 0.55f, 0.50f, 0x7A2E3A)),
    };

    public static final int FADE_MS = 2400;

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** Smooth ease-in-out on [0, 1]. */
    public static float ease(float p) {
        p = clamp01(p);
        return p * p * (3f - 2f * p);
    }

    /** Opacity of scene i while the background changes from scene `from` to scene `to`. */
    public static float opacity(int scene, int from, int to, long elapsedMs) {
        if (from == to) return scene == to ? 1f : 0f;
        float e = ease(elapsedMs / (float) FADE_MS);
        if (scene == to) return e;
        if (scene == from) return 1f - e;
        return 0f;
    }

    /** The scene box is 120% of the screen, centred (the design's `inset: -10%`). out = x, y, w, h. */
    public static void blobRect(Blob b, float w, float h, float[] out) {
        float cw = w * 1.2f, ch = h * 1.2f, x0 = -0.1f * w, y0 = -0.1f * h;
        out[0] = x0 + b.l * cw;
        out[1] = y0 + b.t * ch;
        out[2] = b.w * cw;
        out[3] = b.h * ch;
    }

    /** Ken Burns: a 26 s slow zoom and pan, back and forth. out = scale, tx, ty (fractions of size). */
    public static void kenBurns(double seconds, float[] out) {
        double cyc = ((seconds % 52.0) + 52.0) % 52.0;
        float ph = (float) (cyc < 26.0 ? cyc / 26.0 : (52.0 - cyc) / 26.0);
        float e = ease(ph);
        out[0] = 1.05f + 0.17f * e;
        out[1] = -0.04f * e;
        out[2] = -0.03f * e;
    }

    /** Drift: a 30 s wander through three poses. out = tx, ty (fractions of size), rotation in degrees. */
    public static void drift(double seconds, float[] out) {
        double cyc = ((seconds % 60.0) + 60.0) % 60.0;
        float ph = (float) (cyc < 30.0 ? cyc / 30.0 : (60.0 - cyc) / 30.0);
        float[] a = {0f, 0f, 0f}, b = {0.03f, -0.02f, 2f}, c = {-0.02f, 0.02f, -1f};
        float[] from = ph < 0.5f ? a : b, to = ph < 0.5f ? b : c;
        float e = ease(ph < 0.5f ? ph * 2f : (ph - 0.5f) * 2f);
        for (int i = 0; i < 3; i++) out[i] = from[i] + (to[i] - from[i]) * e;
    }

    /** Next enabled scene in direction dir (+1 or -1), wrapping. Stays put when none is enabled. */
    public static int step(int current, boolean[] enabled, int dir) {
        int n = ALL.length;
        int d = dir < 0 ? -1 : 1;
        for (int k = 1; k <= n; k++) {
            int i = (((current + d * k) % n) + n) % n;
            if (i < enabled.length && enabled[i]) return i;
        }
        return current;
    }
}
