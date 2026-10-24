package com.jay.calmhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything chosen on the Customize screen, saved on the TV. Reads are range-checked. */
final class Settings {
    static final String CLOCK = "f_clock", DATE = "f_date", GREETING = "f_greeting",
            PROFILE = "f_profile", PHOTO = "f_photo", QUIET = "f_quiet", AMBIENT = "f_ambient";

    private final SharedPreferences p;

    Settings(Context c) {
        p = c.getSharedPreferences("calm", Context.MODE_PRIVATE);
    }

    String pins() { return p.getString("pins", AppOrder.DEFAULT_PINS); }

    void pins(String v) { p.edit().putString("pins", v).apply(); }

    String log() { return p.getString("log", ""); }

    void log(String v) { p.edit().putString("log", v).apply(); }

    String times() { return p.getString("times", ""); }

    void times(String v) { p.edit().putString("times", v).apply(); }

    String name() {
        String n = p.getString("name", "Jagadeesh");
        return n == null || n.trim().isEmpty() ? "Guest" : n.trim();
    }

    int motion() { return Options.idx(p.getInt("motion", Options.MOTION_DEFAULT), Options.MOTION.length); }

    int interval() { return Options.idx(p.getInt("interval", Options.INTERVAL_DEFAULT), Options.INTERVAL_MS.length); }

    int glass() { return Options.idx(p.getInt("glass", Options.GLASS_DEFAULT), Options.GLASS.length); }

    int accentIndex() { return Options.idx(p.getInt("accent", 0), Options.ACCENT.length); }

    int accent() { return Options.ACCENT[accentIndex()]; }

    int sort() { return Options.idx(p.getInt("sort", 0), Options.SORT.length); }

    void put(String key, int v) { p.edit().putInt(key, v).apply(); }

    boolean[] scenes() { return Options.sceneMask(p.getString("scenes", null), Scenes.ALL.length); }

    void scenes(boolean[] m) { p.edit().putString("scenes", Options.maskString(m)).apply(); }

    boolean flag(String key) { return p.getBoolean(key, true); }

    void flag(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
}
