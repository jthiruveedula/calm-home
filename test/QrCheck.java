import com.jay.calmhome.QrCode;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * Writes QR codes for many inputs to build/qr/ as PNGs plus an expected.txt list, so test.sh can
 * decode them with an independent reader (zbarimg) and compare.
 */
public final class QrCheck {
    public static void main(String[] args) throws Exception {
        File dir = new File("build/qr");
        dir.mkdirs();
        String[] cases = {
                "http://192.168.1.146:8765/4821",
                "http://10.0.0.5:8765/0000",
                "A",
                "hello world",
                "http://172.20.10.3:65535/abcdefghijklmnop",
                "Calm Home photo drop: photos go straight to your TV over Wi-Fi.",
                "https://jthiruveedula.github.io/calm-home/?from=tv&code=73915&lang=en-US&v=0.3.0",
                "émoji ✓ unicode 日本",
                repeat('x', 106),
        };
        try (PrintWriter w = new PrintWriter(new File(dir, "expected.txt"), "UTF-8")) {
            for (int i = 0; i < cases.length; i++) {
                QrCode q = QrCode.encode(cases[i]);
                int scale = 8, border = 4, n = (q.size + border * 2) * scale;
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        boolean d = q.dark(x / scale - border, y / scale - border);
                        img.setRGB(x, y, d ? 0x000000 : 0xFFFFFF);
                    }
                }
                ImageIO.write(img, "png", new File(dir, "qr" + i + ".png"));
                w.println("qr" + i + ".png\tv" + q.version + "\tmask" + q.mask + "\t"
                        + new String(cases[i].getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            }
        }
        boolean threw = false;
        try {
            QrCode.encode(repeat('y', 107));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (!threw) throw new AssertionError("107 bytes must not fit version 6-M");
        System.out.println("wrote " + cases.length + " QR codes");
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
