package com.jay.calmhome;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Photo Drop's web server. It listens on the local network only while the Photo Drop screen is
 * open, serves one upload page and accepts images on a path that contains a fresh 6-digit code.
 * One connection at a time, on its own thread; uploads stream to a temp file, never into memory.
 */
final class PhotoServer {
    interface Listener {
        void onPhotoAdded(int count);
    }

    static final int PORT = 8765;

    private final PhotoStore store;
    private final File tmpDir;
    private final String page;
    private final Listener listener;
    private volatile ServerSocket socket;
    private String code = "";

    PhotoServer(PhotoStore store, File tmpDir, String pageTemplate, Listener listener) {
        this.store = store;
        this.tmpDir = tmpDir;
        this.page = pageTemplate;
        this.listener = listener;
    }

    /** Starts listening. @return the URL to show, or null when the TV has no LAN address. */
    synchronized String start() {
        stop();
        String ip = DropHttp.pickLanAddress(lanAddresses());
        if (ip == null) return null;
        code = DropHttp.newCode(new SecureRandom());
        ServerSocket s = null;
        for (int port : new int[]{PORT, 0}) {
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port), 4);
                break;
            } catch (IOException e) {
                close(s);
                s = null;
            }
        }
        if (s == null) return null;
        socket = s;
        final ServerSocket server = s;
        final String key = code;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(server, key);
            }
        }, "photo-drop");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + s.getLocalPort() + "/" + key;
    }

    synchronized void stop() {
        close(socket);
        socket = null;
    }

    private void loop(ServerSocket server, String key) {
        while (!server.isClosed()) {
            try (Socket c = server.accept()) {
                c.setSoTimeout(20000);
                handle(c, key);
            } catch (IOException | RuntimeException ignored) {
                // a broken client must never take the server down
            }
        }
    }

    private void handle(Socket c, String key) throws IOException {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();
        String head = readHead(in);
        DropHttp.Request r = DropHttp.parse(head);
        switch (DropHttp.route(r, key)) {
            case DropHttp.PAGE:
                send(out, 200, "text/html; charset=utf-8", page.replace("{{CODE}}", key), !"HEAD".equals(r.method));
                break;
            case DropHttp.STATUS:
                send(out, 200, "application/json", "{\"count\":" + store.count() + "}", true);
                break;
            case DropHttp.UPLOAD:
                upload(r, in, out);
                break;
            case DropHttp.FORBIDDEN:
                send(out, 403, "text/plain", "This Photo Drop link has expired. Scan the code on the TV again.", true);
                break;
            case DropHttp.NOT_FOUND:
                send(out, 404, "text/plain", "Not found", true);
                break;
            default:
                send(out, 400, "text/plain", "Bad request", true);
                break;
        }
    }

    private void upload(DropHttp.Request r, InputStream in, OutputStream out) throws IOException {
        long len = DropHttp.uploadLength(r);
        if (len < 0) {
            send(out, 413, "application/json", "{\"ok\":false,\"error\":\"too large\"}", true);
            return;
        }
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("no temp dir");
        File tmp = File.createTempFile("drop", ".bin", tmpDir);
        byte[] first = new byte[12];
        int firstN = 0;
        try (OutputStream fo = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("short body");
                if (firstN < 12) {
                    int k = Math.min(12 - firstN, n);
                    System.arraycopy(buf, 0, first, firstN, k);
                    firstN += k;
                }
                fo.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e;
        }
        if (DropHttp.imageKind(first, firstN) == null) {
            if (!tmp.delete()) tmp.deleteOnExit();
            send(out, 415, "application/json", "{\"ok\":false,\"error\":\"not a photo\"}", true);
            return;
        }
        boolean ok = store.add(tmp);
        int count = store.count();
        send(out, ok ? 200 : 415, "application/json", "{\"ok\":" + ok + ",\"count\":" + count + "}", true);
        if (ok) listener.onPhotoAdded(count);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() <= DropHttp.MAX_HEADER) {
            int ch = in.read();
            if (ch < 0) break;
            b.write(ch);
            // Stop at the blank line: \r\n\r\n (or a bare \n\n).
            if (ch == '\n') {
                state = state == 1 || state == 3 ? 4 : 1;
                if (state == 4) break;
            } else if (ch == '\r') {
                state = state == 1 ? 3 : 2;
            } else {
                state = 0;
            }
        }
        return b.toString("ISO-8859-1").trim();
    }

    private static void send(OutputStream out, int status, String type, String body, boolean withBody) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(DropHttp.responseHead(status, type, b.length).getBytes(StandardCharsets.ISO_8859_1));
        if (withBody) out.write(b);
        out.flush();
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no network
        }
        return out;
    }

    private static void close(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
