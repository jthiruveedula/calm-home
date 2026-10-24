package com.jay.calmhome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Remembers which apps were opened from Calm Home, newest first, so "Recently opened" works without any permission. */
public final class LaunchLog {
    private LaunchLog() {}

    public static final int MAX = 100;
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    /** Newest first, no duplicates. Anything malformed is dropped. */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (PKG.matcher(p).matches() && !out.contains(p)) out.add(p);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    public static String record(String raw, String pkg) {
        List<String> list = parse(raw);
        if (pkg == null || !PKG.matcher(pkg).matches()) return join(list);
        list.remove(pkg);
        list.add(0, pkg);
        while (list.size() > MAX) list.remove(list.size() - 1);
        return join(list);
    }

    /** pkg -> position (0 = most recent). */
    public static Map<String, Integer> ranks(String raw) {
        Map<String, Integer> m = new HashMap<>();
        List<String> list = parse(raw);
        for (int i = 0; i < list.size(); i++) m.put(list.get(i), i);
        return m;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String p : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }
}
