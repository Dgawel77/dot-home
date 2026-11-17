package dev.gawel.dothome.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure helpers for the music widget's album art: crop maths, cache key, resampling and an LRU map. */
public final class ArtMath {
    private ArtMath() {}

    /** Side of the stored square art, px. */
    public static final int STORE_PX = 96;
    /** Side of the image fed to the halftone (art dp at 1 px per dp, so step 4 gives 16 x 16 cells). */
    public static final int HALFTONE_PX = 64;
    /** Tracks whose art is kept. */
    public static final int MAX_TRACKS = 4;

    /** The centred square of a w x h image as {x, y, side}; {0, 0, 0} for an empty image. */
    public static int[] cropSquare(int w, int h) {
        if (w <= 0 || h <= 0) return new int[] {0, 0, 0};
        int side = Math.min(w, h);
        return new int[] {(w - side) / 2, (h - side) / 2, side};
    }

    /** Largest power-of-two inSampleSize that keeps both dimensions at least targetPx (1 when already small). */
    public static int sampleSize(int w, int h, int targetPx) {
        int s = 1;
        if (w <= 0 || h <= 0 || targetPx <= 0) return 1;
        while (w / (s * 2) >= targetPx && h / (s * 2) >= targetPx) s *= 2;
        return s;
    }

    /** Cache key for a track's art: package, title, artist and album (nulls count as empty). */
    public static String key(String pkg, String title, String artist, String album) {
        return n(pkg) + "\u0001" + n(title) + "\u0001" + n(artist) + "\u0001" + n(album);
    }

    private static String n(String s) { return s == null ? "" : s; }

    /** Box-average resample of a square-or-not ARGB image; returns src when the size is unchanged. */
    public static int[] resample(int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src;
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = y * sh / dh, y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * sw / dw, x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                long a = 0, r = 0, g = 0, b = 0;
                int cnt = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src[yy * sw + xx];
                        a += p >>> 24; r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF;
                        cnt++;
                    }
                }
                out[y * dw + x] = ((int) (a / cnt) << 24) | ((int) (r / cnt) << 16) | ((int) (g / cnt) << 8) | (int) (b / cnt);
            }
        }
        return out;
    }

    /** An access-ordered map holding at most max entries; the least recently used one is dropped first. */
    public static final class Lru<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final int max;

        public Lru(int max) {
            super(8, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
        }
    }
}
