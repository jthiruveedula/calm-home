package com.jay.calmhome;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pure parts of Photo Drop's tiny web server: request parsing, the secret-code check, upload
 * limits, image sniffing, storage pruning and decode sizing. Tested on the desktop JVM.
 */
public final class DropHttp {
    private DropHttp() {}

    public static final int MAX_UPLOAD = 15 * 1024 * 1024;
    public static final int MAX_HEADER = 8 * 1024;
    public static final int MAX_PHOTOS = 200;

    public static final int PAGE = 0, UPLOAD = 1, STATUS = 2, NOT_FOUND = 3, FORBIDDEN = 4, BAD = 5;

    public static final class Request {
        public final String method, path, query;
        public final Map<String, String> headers;

        Request(String method, String path, String query, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    /** Parses the request line and headers (CRLF or LF). Returns null for anything malformed. */
    public static Request parse(String head) {
        if (head == null || head.length() > MAX_HEADER) return null;
        String[] lines = head.split("\r?\n");
        if (lines.length == 0) return null;
        String[] parts = lines[0].split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) return null;
        String method = parts[0];
        if (!method.equals("GET") && !method.equals("POST") && !method.equals("HEAD")) return null;
        String target = parts[1];
        if (!target.startsWith("/") || target.contains("..")) return null;
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        String query = q >= 0 ? target.substring(q + 1) : "";
        Map<String, String> h = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
        }
        return new Request(method, path, query, Collections.unmodifiableMap(h));
    }

    /** Which handler a request goes to. Every path must start with the secret code. */
    public static int route(Request r, String code) {
        if (r == null) return BAD;
        String[] seg = r.path.split("/", -1); // "", code, rest...
        if (seg.length < 2 || !sameCode(seg[1], code)) return seg.length >= 2 && seg[1].isEmpty() ? NOT_FOUND : FORBIDDEN;
        String rest = seg.length > 2 ? seg[2] : "";
        if (seg.length > 3) return NOT_FOUND;
        if (rest.isEmpty() && (r.method.equals("GET") || r.method.equals("HEAD"))) return PAGE;
        if (rest.equals("upload") && r.method.equals("POST")) return UPLOAD;
        if (rest.equals("status") && r.method.equals("GET")) return STATUS;
        return NOT_FOUND;
    }

    /** Constant-time comparison so the code cannot be guessed by timing. */
    public static boolean sameCode(String a, String b) {
        if (a == null || b == null || b.isEmpty()) return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < b.length(); i++) diff |= (i < a.length() ? a.charAt(i) : 0) ^ b.charAt(i);
        return diff == 0;
    }

    /** Content-Length if it is a sane upload size, else -1. */
    public static long uploadLength(Request r) {
        String v = r == null ? null : r.header("content-length");
        if (v == null || !v.matches("\\d{1,9}")) return -1;
        long n = Long.parseLong(v);
        return n > 0 && n <= MAX_UPLOAD ? n : -1;
    }

    /** "jpeg", "png" or "webp" from the first bytes, else null. */
    public static String imageKind(byte[] b, int n) {
        if (b == null || n < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** A fresh 6-digit code. */
    public static String newCode(SecureRandom rnd) {
        return String.format(Locale.US, "%06d", rnd.nextInt(1_000_000));
    }

    /** Power-of-two sample size so a decoded photo is at least the target but not much bigger. */
    public static int sampleSize(int w, int h, int maxW, int maxH) {
        int s = 1;
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return 1;
        while (w / (s * 2) >= maxW && h / (s * 2) >= maxH) s *= 2;
        return s;
    }

    /** Names to delete so at most max remain, oldest first (names sort by time). */
    public static List<String> prune(List<String> names, int max) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        int extra = sorted.size() - Math.max(0, max);
        return extra > 0 ? new ArrayList<>(sorted.subList(0, extra)) : new ArrayList<String>();
    }

    /** The address to show: the first private IPv4, preferring 192.168, then 10, then 172.16-31. */
    public static String pickLanAddress(List<String> addresses) {
        String best = null;
        int bestRank = 9;
        for (String a : addresses) {
            int r = a.startsWith("192.168.") ? 0 : a.startsWith("10.") ? 1 : isPrivate172(a) ? 2 : 9;
            if (r < bestRank) {
                best = a;
                bestRank = r;
            }
        }
        return best;
    }

    private static boolean isPrivate172(String a) {
        if (!a.startsWith("172.")) return false;
        String[] p = a.split("\\.");
        if (p.length != 4) return false;
        try {
            int b = Integer.parseInt(p[1]);
            return b >= 16 && b <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Minimal HTTP response head. */
    public static String responseHead(int status, String type, long length) {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : status == 413 ? "Payload Too Large" : status == 415 ? "Unsupported Media Type"
                : "Error";
        return "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
    }
}
