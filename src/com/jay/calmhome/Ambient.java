package com.jay.calmhome;

/** Colour and time maths for the room's light. Plain ints (0xRRGGBB), tested on the desktop JVM. */
public final class Ambient {
    private Ambient() {}

    /** Sky keyframes: minute of day -> tint. The glow drifts between them all day. */
    private static final int[][] SKY = {
            {0, 0x2A1F5C},    // midnight indigo
            {300, 0x241B4F},  // 05:00 deep night
            {390, 0x7A4A3A},  // 06:30 dawn ember
            {540, 0x35577A},  // 09:00 morning blue
            {960, 0x2F5A6E},  // 16:00 afternoon teal
            {1110, 0x8A4B2E}, // 18:30 dusk amber
            {1260, 0x3A2466}, // 21:00 evening violet
            {1440, 0x2A1F5C}, // back to midnight
    };

    public static final float QUIET_MAX = 0.35f;

    public static int skyTint(int minuteOfDay) {
        int m = wrap(minuteOfDay);
        for (int k = 0; k + 1 < SKY.length; k++) {
            int a = SKY[k][0], b = SKY[k + 1][0];
            if (m >= a && m < b) return mix(SKY[k][1], SKY[k + 1][1], (m - a) / (float) (b - a));
        }
        return SKY[0][1];
    }

    /** Linear blend of two 0xRRGGBB colours; t is clamped to [0, 1]. */
    public static int mix(int a, int b, float t) {
        if (t <= 0f) return a & 0xFFFFFF;
        if (t >= 1f) return b & 0xFFFFFF;
        return (ch(a >> 16, b >> 16, t) << 16) | (ch(a >> 8, b >> 8, t) << 8) | ch(a, b, t);
    }

    /** Quiet hours: the screen dims from 23:00 to 23:30 and brightens again from 06:00 to 06:30. */
    public static float quietDim(int minuteOfDay) {
        int m = wrap(minuteOfDay);
        if (m >= 1380) return QUIET_MAX * Math.min(1f, (m - 1380) / 30f);
        if (m < 360) return QUIET_MAX;
        if (m < 390) return QUIET_MAX * (1f - (m - 360) / 30f);
        return 0f;
    }

    public static String greeting(int minuteOfDay) {
        int h = wrap(minuteOfDay) / 60;
        if (h >= 5 && h < 12) return "Good morning";
        if (h >= 12 && h < 17) return "Good afternoon";
        if (h >= 17 && h < 22) return "Good evening";
        return "Good night";
    }

    /** Turns an app's average colour into a usable glow: never black, never neon. Greys stay grey. */
    public static int glowFrom(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float h = 0f, s = max == 0f ? 0f : (max - min) / max, v = max;
        if (max != min) {
            float d = max - min;
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h *= 60f;
            if (h < 0f) h += 360f;
        }
        if (s >= 0.08f) s = Math.max(0.35f, Math.min(0.85f, s));
        v = Math.max(0.55f, Math.min(0.9f, v));
        return hsv(h, s, v);
    }

    static int hsv(float h, float s, float v) {
        float c = v * s, x = c * (1f - Math.abs((h / 60f) % 2f - 1f)), m = v - c;
        float r, g, b;
        switch (((int) (h / 60f)) % 6) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return (to8(r + m) << 16) | (to8(g + m) << 8) | to8(b + m);
    }

    private static int to8(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255f)));
    }

    private static int ch(int a, int b, float t) {
        a &= 0xFF;
        b &= 0xFF;
        return Math.round(a + (b - a) * t);
    }

    private static int wrap(int minuteOfDay) {
        return ((minuteOfDay % 1440) + 1440) % 1440;
    }
}
