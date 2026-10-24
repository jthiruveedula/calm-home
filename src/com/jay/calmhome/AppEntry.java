package com.jay.calmhome;

/** One launchable app. Plain data with no Android types, so the ordering logic runs in desktop tests. */
public final class AppEntry {
    public final String pkg;
    public final String activity;
    public final String label;
    public final boolean leanback;

    public AppEntry(String pkg, String activity, String label, boolean leanback) {
        this.pkg = pkg;
        this.activity = activity;
        this.label = label == null || label.trim().isEmpty() ? pkg : label.trim();
        this.leanback = leanback;
    }

    public String key() {
        return pkg + "/" + activity;
    }

    @Override
    public String toString() {
        return label + " (" + pkg + ")";
    }
}
