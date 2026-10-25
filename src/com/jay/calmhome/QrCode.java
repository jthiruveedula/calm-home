package com.jay.calmhome;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder: byte mode, error correction level M, versions 1 to 6 (up to 106 bytes,
 * plenty for a LAN URL). Follows ISO/IEC 18004 and the structure of Project Nayuki's reference
 * implementation. Pure Java; tested on the desktop and decoded with an independent reader.
 */
public final class QrCode {
    public final int version, size, mask;
    private final boolean[][] modules; // [y][x], true = dark
    private final boolean[][] isFunction;

    private static final int[] ECC_PER_BLOCK_M = {-1, 10, 16, 26, 18, 24, 16};
    private static final int[] EC_BLOCKS_M = {-1, 1, 1, 1, 2, 2, 4};
    public static final int MAX_VERSION = 6;

    public boolean dark(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && modules[y][x];
    }

    /** Encodes text as UTF-8; throws IllegalArgumentException when it does not fit version 6. */
    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacityBits = dataCodewords(v) * 8;
            int used = 4 + 8 + data.length * 8;
            if (used <= capacityBits) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("Text too long for a version " + MAX_VERSION + " QR code");
    }

    private QrCode(int ver, byte[] data) {
        version = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns();
        byte[] all = addEcc(codewords(ver, data));
        drawCodewords(all);
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < bestPenalty) {
                best = m;
                bestPenalty = p;
            }
            applyMask(m); // XOR undoes it
        }
        mask = best;
        applyMask(best);
        drawFormatBits(best);
    }

    // ---- data -----------------------------------------------------------------------------

    static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * EC_BLOCKS_M[ver];
    }

    /** Mode, count, bytes, terminator and padding, as data codewords. */
    static byte[] codewords(int ver, byte[] data) {
        int cap = dataCodewords(ver);
        BitBuf bb = new BitBuf(cap * 8);
        bb.append(0x4, 4);
        bb.append(data.length, 8);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, cap * 8 - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < cap * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.bytes();
    }

    private static final class BitBuf {
        final byte[] buf;
        int len;

        BitBuf(int bits) {
            buf = new byte[(bits + 7) / 8];
        }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--, len++) {
                if (((val >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
            }
        }

        byte[] bytes() {
            return buf;
        }
    }

    /** Splits into blocks, appends Reed-Solomon codewords and interleaves. */
    private byte[] addEcc(byte[] data) {
        int ver = version, blocks = EC_BLOCKS_M[ver], ecc = ECC_PER_BLOCK_M[ver];
        int raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] div = rsDivisor(ecc);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int dlen = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[dlen];
            System.arraycopy(data, k, dat, 0, dlen);
            k += dlen;
            byte[] rem = rsRemainder(dat, div);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, dlen);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            out[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, k = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                // Short blocks have no codeword at index shortLen - ecc (their "padding" slot).
                if (i != shortLen - ecc || j >= shortBlocks) result[k++] = out[j][i];
            }
        }
        return result;
    }

    static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(div[i] & 0xFF, f);
        }
        return r;
    }

    static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ---- layout ---------------------------------------------------------------------------

    private void set(int x, int y, boolean d) {
        modules[y][x] = d;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(pos[i], pos[j]);
            }
        }
        drawFormatBits(0); // reserve the format areas; real bits are drawn per mask
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
        }
    }

    private void alignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    /** Level M format bits (00) with the mask, BCH-protected and XOR-masked. */
    static int formatBits(int mask) {
        int data = mask; // M = 0b00 in the top two bits
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return ((data << 10) | rem) ^ 0x5412;
    }

    private void drawFormatBits(int mask) {
        int bits = formatBits(mask);
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= inv && !isFunction[y][x];
            }
        }
    }

    /** The standard's four penalty rules, used only to pick the most readable mask. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                boolean prev = cell(pass, a, 0);
                for (int b = 1; b <= size; b++) {
                    boolean cur = b < size && cell(pass, a, b);
                    if (b < size && cur == prev) {
                        run++;
                    } else {
                        if (run >= 5) p += 3 + (run - 5);
                        run = 1;
                        prev = cur;
                    }
                }
                for (int b = 0; b + 10 < size + 1; b++) {
                    if (finderLike(pass, a, b)) p += 40;
                }
            }
        }
        int darkCount = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) darkCount++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    private boolean cell(int pass, int a, int b) {
        return pass == 0 ? modules[a][b] : modules[b][a];
    }

    private static final boolean[] PAT1 = {true, false, true, true, true, false, true, false, false, false, false};
    private static final boolean[] PAT2 = {false, false, false, false, true, false, true, true, true, false, true};

    private boolean finderLike(int pass, int a, int b) {
        if (b + 11 > size) return false;
        boolean m1 = true, m2 = true;
        for (int i = 0; i < 11; i++) {
            boolean c = cell(pass, a, b + i);
            m1 &= c == PAT1[i];
            m2 &= c == PAT2[i];
        }
        return m1 || m2;
    }
}
