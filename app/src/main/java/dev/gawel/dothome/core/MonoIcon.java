package dev.gawel.dothome.core;

import java.util.Arrays;

/** Turns launcher icons into white-on-transparent glyphs. Pure Java; pixels are ARGB ints. */
public final class MonoIcon {
    private MonoIcon() {}

    /** The monochrome layer already encodes the glyph in its alpha: keep alpha, force white. */
    public static int[] fromMonochromeLayer(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) out[i] = (argb[i] & 0xFF000000) | 0x00FFFFFF;
        return out;
    }

    /** Full-colour icon: anything that differs enough from the background becomes white. */
    public static int[] fromFullIcon(int[] argb, int w, int h) {
        int[] out = new int[argb.length];
        if (w <= 0 || h <= 0) return out;
        int[] corners = {
            lum(argb[0]), lum(argb[w - 1]), lum(argb[(h - 1) * w]), lum(argb[h * w - 1])
        };
        Arrays.sort(corners);
        int bg = (corners[1] + corners[2]) / 2;
        for (int i = 0; i < argb.length; i++) {
            int d = Math.abs(lum(argb[i]) - bg);
            if (d > 60) out[i] = (Math.min(255, 4 * d) << 24) | 0x00FFFFFF;
        }
        return out;
    }

    /** Share of pixels with alpha >= 128. */
    public static float fillRatio(int[] argb) {
        if (argb.length == 0) return 0f;
        int n = 0;
        for (int p : argb) if ((p >>> 24) >= 128) n++;
        return n / (float) argb.length;
    }

    /**
     * Recolours every pixel to fg and hardens alpha: below 90 gone, above 165 solid, linear between.
     * Removes the grey shading that soft alpha gradients leave on a flat glyph.
     */
    public static int[] flatten(int[] argb, int fg) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int a = argb[i] >>> 24;
            int na = a < 90 ? 0 : a > 165 ? 255 : (a - 90) * 255 / 75;
            out[i] = (na << 24) | (fg & 0x00FFFFFF);
        }
        return out;
    }

    static int lum(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000;
    }
}
