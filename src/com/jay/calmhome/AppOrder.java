package com.jay.calmhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Which apps go in the favourites dock and in what order. Pure Java, tested on the desktop JVM. */
public final class AppOrder {
    private AppOrder() {}

    /** Netflix, Prime Video, YouTube, Apple TV: the owner's everyday apps. */
    public static final String DEFAULT_PINS =
            "com.netflix.ninja,com.amazon.firebat,com.amazon.firetv.youtube,com.apple.atve.amazon.appletv";

    /** Never shown as tiles: Amazon's own home screen would only bounce back to itself. */
    public static final Set<String> HIDDEN = Collections.unmodifiableSet(new HashSet<String>(
            java.util.Arrays.asList("com.amazon.tv.launcher")));

    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    public static final class Split {
        public final List<AppEntry> dock;
        public final List<AppEntry> grid;

        Split(List<AppEntry> dock, List<AppEntry> grid) {
            this.dock = dock;
            this.grid = grid;
        }
    }

    static final Comparator<AppEntry> BY_LABEL = new Comparator<AppEntry>() {
        @Override
        public int compare(AppEntry a, AppEntry b) {
            int c = a.label.compareToIgnoreCase(b.label);
            return c != 0 ? c : a.pkg.compareTo(b.pkg);
        }
    };

    /** One tile per package (first wins), pinned apps in pin order, the rest by label. */
    public static Split split(List<AppEntry> apps, List<String> pins, int maxDock) {
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        for (AppEntry e : apps) {
            if (e != null && !HIDDEN.contains(e.pkg) && !byPkg.containsKey(e.pkg)) byPkg.put(e.pkg, e);
        }
        List<AppEntry> dock = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String p : pins) {
            if (dock.size() >= maxDock) break;
            AppEntry e = byPkg.get(p);
            if (e != null && used.add(p)) dock.add(e);
        }
        List<AppEntry> grid = new ArrayList<>();
        for (AppEntry e : byPkg.values()) {
            if (!used.contains(e.pkg)) grid.add(e);
        }
        Collections.sort(grid, BY_LABEL);
        return new Split(Collections.unmodifiableList(dock), Collections.unmodifiableList(grid));
    }

    /** Parses the stored pin list, dropping anything that is not a plain package name. */
    public static List<String> parsePins(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (PKG.matcher(p).matches() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    public static String joinPins(List<String> pins) {
        StringBuilder sb = new StringBuilder();
        for (String p : pins) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * Pins pkg, or unpins it when it is already in the dock.
     * @return the new pin list, or null when the dock is full and pkg is not in it.
     */
    public static List<String> togglePin(List<String> pins, List<AppEntry> dock, String pkg, int maxDock) {
        List<String> out = new ArrayList<>(pins);
        boolean inDock = false;
        for (AppEntry e : dock) {
            if (e.pkg.equals(pkg)) inDock = true;
        }
        if (inDock) {
            out.removeAll(Collections.singleton(pkg));
            return out;
        }
        if (dock.size() >= maxDock) return null;
        out.removeAll(Collections.singleton(pkg));
        out.add(pkg);
        return out;
    }
}
