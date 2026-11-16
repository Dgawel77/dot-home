package dev.gawel.dothome.core;

import java.util.Arrays;

/** Turns launcher icons into white-on-transparent glyphs and grey tone layers. Pure Java; pixels are ARGB ints. */
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

    /** Composites unpremultiplied ARGB over opaque black (what drawing onto a black canvas gives). */
    public static int[] overBlack(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i], a = p >>> 24;
            int r = (((p >> 16) & 0xFF) * a + 127) / 255;
            int g = (((p >> 8) & 0xFF) * a + 127) / 255;
            int b = ((p & 0xFF) * a + 127) / 255;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    /**
     * Tone layer: each pixel becomes its Rec. 709 luma (0.2126 R + 0.7152 G + 0.0722 B on the sRGB
     * values) as grey, keeping alpha as the colour icon's coverage. Uncovered pixels are transparent.
     */
    public static int[] tone(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i], a = p >>> 24;
            if (a == 0) continue;
            int y = (2126 * ((p >> 16) & 0xFF) + 7152 * ((p >> 8) & 0xFF) + 722 * (p & 0xFF) + 5000) / 10000;
            out[i] = (a << 24) | (y << 16) | (y << 8) | y;
        }
        return out;
    }

    /** Shown when an export finds no monochrome layers at all: a launcher icon theme is likely in the way. */
    public static final String THEME_WARNING =
        "icon theme may be overriding icons \u2014 set Theme Park icons to Default and re-run";

    /** Export summary: mono, auto and failed counts, loadIcon fallbacks, and the icon-theme warning. */
    public static String exportStatus(int mono, int auto, int failed, int fallback) {
        int total = mono + auto;
        StringBuilder s = new StringBuilder();
        s.append("Exported ").append(total).append(" icons (").append(mono).append(" mono, ")
            .append(auto).append(" auto, ").append(failed).append(" failed)");
        if (fallback > 0) s.append("\n").append(fallback).append(" via loadIcon (themed) fallback");
        if (mono == 0 && total > 20) s.append("\n\n").append(THEME_WARNING);
        return s.toString();
    }

    static int lum(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000;
    }
}
