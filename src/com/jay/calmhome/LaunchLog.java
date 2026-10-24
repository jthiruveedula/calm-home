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

    /** "pkg=millis,..." newest first; records when each app was last opened. */
    public static String recordTime(String raw, String pkg, long now) {
        List<String> keep = new ArrayList<>();
        if (pkg != null && PKG.matcher(pkg).matches() && now > 0) keep.add(pkg + "=" + now);
        if (raw != null) {
            for (String part : raw.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String p = part.substring(0, eq).trim();
                if (p.equals(pkg) || !PKG.matcher(p).matches() || parseTime(part.substring(eq + 1)) < 0) continue;
                boolean dup = false;
                for (String k : keep) dup |= k.startsWith(p + "=");
                if (!dup) keep.add(p + "=" + part.substring(eq + 1).trim());
                if (keep.size() >= MAX) break;
            }
        }
        return join(keep);
    }

    /** When pkg was last opened, or -1. */
    public static long timeOf(String raw, String pkg) {
        if (raw == null || pkg == null) return -1;
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(pkg)) return parseTime(part.substring(eq + 1));
        }
        return -1;
    }

    private static long parseTime(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago"; "" when unknown or in the future. */
    public static String ago(long then, long now) {
        if (then <= 0 || now < then - 60000L) return "";
        long min = Math.max(0L, now - then) / 60000L;
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        if (d == 1) return "Yesterday";
        if (d < 30) return d + " days ago";
        return "A while ago";
    }
}
