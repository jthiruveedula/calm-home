package com.jay.calmhome;

/**
 * Animation maths. A damped spring gives focus a small, settled overshoot instead of a plain
 * ease; ease-out drives entrances. Pure Java, tested for stability at any frame time.
 */
public final class Motion {
    private Motion() {}

    /** About 0.35 s to settle with a few percent of overshoot. */
    public static final float FREQ_HZ = 3.4f, DAMPING = 0.74f;
    private static final float SUBSTEP_MS = 4f;

    /**
     * Advances a spring. xv = {position, velocity per ms}.
     * @return true while it is still moving
     */
    public static boolean spring(float[] xv, float target, float dtMs) {
        if (Float.isNaN(xv[0]) || Float.isNaN(xv[1]) || Float.isInfinite(xv[0]) || Float.isInfinite(xv[1])) {
            xv[0] = target;
            xv[1] = 0f;
            return false;
        }
        float w = (float) (2.0 * Math.PI * FREQ_HZ / 1000.0);
        float left = Math.max(0f, Math.min(dtMs, 100f));
        while (left > 0f) {
            float h = Math.min(SUBSTEP_MS, left);
            float a = -w * w * (xv[0] - target) - 2f * DAMPING * w * xv[1];
            xv[1] += a * h;
            xv[0] += xv[1] * h;
            left -= h;
        }
        if (Math.abs(xv[0] - target) < 0.25f && Math.abs(xv[1]) < 0.002f) {
            xv[0] = target;
            xv[1] = 0f;
            return false;
        }
        return true;
    }

    /** Cubic ease-out of t clamped to [0, 1]. */
    public static float easeOut(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Entrance progress of the i-th item, staggered by stepMs, each lasting durMs. */
    public static float stagger(long sinceMs, int i, long stepMs, long durMs) {
        if (durMs <= 0) return 1f;
        return easeOut((sinceMs - i * stepMs) / (float) durMs);
    }
}
