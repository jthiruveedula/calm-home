package com.jay.calmhome;

/** Time-of-day and colour maths. Plain ints (0xRRGGBB), tested on the desktop JVM. */
public final class Ambient {
    private Ambient() {}

    public static final float QUIET_MAX = 0.35f;

    /** Linear blend of two 0xRRGGBB colours; t is clamped to [0, 1]. Alpha bits are always dropped. */
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

    /** The design's three greetings: morning before noon, afternoon before 18:00, evening after. */
    public static String greeting(int minuteOfDay) {
        int h = wrap(minuteOfDay) / 60;
        if (h < 12) return "Good morning";
        if (h < 18) return "Good afternoon";
        return "Good evening";
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
