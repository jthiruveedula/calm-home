package com.jay.calmhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Photos dropped from a phone, kept in Calm Home's private storage. Every upload is decoded and
 * re-encoded here (at most 1920x1080 worth of pixels), which strips metadata and rejects anything
 * that is not really an image. Thread-safe: methods are synchronized.
 */
final class PhotoStore {
    private final File dir;

    PhotoStore(File filesDir) {
        dir = new File(filesDir, "photos");
    }

    synchronized List<String> list() {
        String[] names = dir.list();
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) if (n.endsWith(".jpg")) out.add(n);
        }
        Collections.sort(out);
        return out;
    }

    synchronized int count() {
        return list().size();
    }

    /** Re-encodes an uploaded file into the store. @return false when it is not a decodable image. */
    synchronized boolean add(File upload) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(upload.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 120_000_000L) return false;
            Bitmap b = decode(upload, 1920, 1080, Bitmap.Config.ARGB_8888);
            if (b == null) return false;
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File out = new File(dir, String.format(java.util.Locale.US, "p%013d.jpg", System.currentTimeMillis()));
            try (OutputStream os = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.JPEG, 88, os);
            } finally {
                b.recycle();
            }
            for (String old : DropHttp.prune(list(), DropHttp.MAX_PHOTOS)) {
                if (!new File(dir, old).delete()) break;
            }
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return false;
        } finally {
            if (!upload.delete()) upload.deleteOnExit();
        }
    }

    synchronized void clear() {
        for (String n : list()) {
            if (!new File(dir, n).delete()) break;
        }
    }

    /**
     * A photo decoded for the screen, or null. Full colour for the slideshow (565 bands visibly on
     * skies and sunsets); thumbnails use RGB_565, half the memory.
     */
    synchronized Bitmap load(String name, int maxW, int maxH, boolean fullColour) {
        File f = new File(dir, name);
        return f.isFile() ? decode(f, maxW, maxH, fullColour ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565) : null;
    }

    private static Bitmap decode(File f, int maxW, int maxH, Bitmap.Config config) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = DropHttp.sampleSize(o.outWidth, o.outHeight, maxW, maxH);
        o.inPreferredConfig = config;
        // Scale down by the remaining factor while decoding (no second full-size bitmap).
        int sw = o.outWidth / o.inSampleSize, sh = o.outHeight / o.inSampleSize;
        float k = Math.max((float) maxW / sw, (float) maxH / sh);
        if (k < 0.99f && sw > 0) {
            o.inScaled = true;
            o.inDensity = sw;
            o.inTargetDensity = Math.max(1, Math.round(sw * k));
        }
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The newest n names, newest first. */
    synchronized List<String> newest(int n) {
        List<String> all = list();
        Collections.reverse(all);
        return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
    }

    @Override
    public String toString() {
        return "PhotoStore" + Arrays.toString(new Object[]{dir, count()});
    }
}
