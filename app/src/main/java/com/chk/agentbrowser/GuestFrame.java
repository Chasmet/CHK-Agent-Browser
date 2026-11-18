package com.chk.agentbrowser;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** QEMU's P6 screen dump is available even when its optional PNG encoder is absent. */
final class GuestFrame {
    private static final long MAX_PIXELS = 10_000_000L;

    private GuestFrame() { }

    static Bitmap read(File file) throws IOException {
        if (file.length() > MAX_PIXELS * 3 + 128) throw new IOException("Capture Android trop grande.");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (!"P6".equals(token(input))) throw new IOException("Format d’écran Android invalide.");
            int width, height, max;
            try {
                width = Integer.parseInt(token(input));
                height = Integer.parseInt(token(input));
                max = Integer.parseInt(token(input));
            } catch (NumberFormatException invalid) {
                throw new IOException("Dimensions d’écran Android invalides.", invalid);
            }
            if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS || max != 255) {
                throw new IOException("Dimensions d’écran Android invalides.");
            }
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                byte[] rgb = new byte[width * 3];
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    input.readFully(rgb);
                    for (int x = 0, i = 0; x < width; x++, i += 3) {
                        row[x] = 0xff000000 | ((rgb[i] & 255) << 16) | ((rgb[i + 1] & 255) << 8) | (rgb[i + 2] & 255);
                    }
                    image.setPixels(row, 0, width, 0, y, width, 1);
                }
                return image;
            } catch (IOException | RuntimeException error) {
                image.recycle();
                throw error;
            }
        }
    }

    /** Conservative visual hint, not proof that Android itself finished booting. */
    static boolean looksLikeBootAnimation(Bitmap bitmap) {
        if (bitmap == null) return false;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 64 || height < 64) return false;
        int step = Math.max(1, Math.min(width, height) / 160);
        int sampled = 0, brightCenter = 0, brightOutside = 0, notDark = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int color = bitmap.getPixel(x, y);
                int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                sampled++;
                if (Math.max(r, Math.max(g, b)) > 45) notDark++;
                if (r > 195 && g > 195 && b > 195) {
                    if (x >= width / 4 && x < width * 3 / 4
                            && y >= height / 4 && y < height * 3 / 4) brightCenter++;
                    else brightOutside++;
                }
            }
        }
        return brightCenter >= 8 && brightCenter < sampled / 100
                && brightOutside < 4 && notDark < sampled / 40;
    }

    private static String token(DataInputStream input) throws IOException {
        int next;
        do { next = input.readUnsignedByte(); }
        while (next == ' ' || next == '\n' || next == '\r' || next == '\t');
        StringBuilder word = new StringBuilder();
        do {
            if (word.length() == 12) throw new IOException("En-tête de capture Android invalide.");
            word.append((char) next);
            next = input.readUnsignedByte();
        } while (next != ' ' && next != '\n' && next != '\r' && next != '\t');
        return word.toString();
    }
}
