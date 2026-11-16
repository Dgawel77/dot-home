package dev.gawel.dothome.core;

import java.util.Arrays;

/** Halftone: grey, contrast stretch, square lattice, dot diameter from brightness. */
public final class Halftone {
    private Halftone() {}

    private static final float MAX_FILL = 0.85f;
    private static final float SKIP_BELOW = 0.10f;

    /**
     * Converts an ARGB image (alpha is composited onto black) to (cx, cy, diameter) triples on a square lattice of stepPx cells.
     * Luminance is stretched between its 2nd and 98th percentile; diameter = sqrt(b) x 0.85 x step;
     * cells with b below 10% are skipped. Cells are the mean luminance of their pixels.
     */
    public static float[] dots(int[] argb, int w, int h, int stepPx) {
        float[] lum = new float[w * h];
        for (int i = 0; i < lum.length; i++) {
            int p = argb[i];
            lum[i] = ((p >>> 24) / 255f) * (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
        }
        float[] sorted = lum.clone();
        Arrays.sort(sorted);
        float lo = sorted.length == 0 ? 0f : sorted[(int) Math.floor(0.02 * (sorted.length - 1))];
        float hi = sorted.length == 0 ? 1f : sorted[(int) Math.floor(0.98 * (sorted.length - 1))];
        float span = hi - lo;

        int cols = w / stepPx, rows = h / stepPx;
        float[] out = new float[cols * rows * 3];
        int n = 0;
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                float sum = 0;
                for (int y = 0; y < stepPx; y++) {
                    for (int x = 0; x < stepPx; x++) sum += lum[(cy * stepPx + y) * w + cx * stepPx + x];
                }
                float mean = sum / (stepPx * stepPx);
                float b = span <= 1e-6f ? (mean >= 0.5f ? 1f : 0f) : Math.min(1f, Math.max(0f, (mean - lo) / span));
                if (b < SKIP_BELOW) continue;
                out[n++] = (cx + 0.5f) * stepPx;
                out[n++] = (cy + 0.5f) * stepPx;
                out[n++] = diameter(b, stepPx);
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** Dot diameter for brightness b in 0..1: sqrt(b) x 0.85 x step. */
    public static float diameter(float b, float stepPx) {
        return (float) Math.sqrt(b) * MAX_FILL * stepPx;
    }

    /**
     * Calibrated data (radar intensity): brightness b (0..1, row-major cols x rows) goes straight to dots on a
     * square lattice of stepPx cells, with no contrast stretch, so a value always gives the same dot.
     * Cells below 10% are skipped. Returns (cx, cy, diameter) triples.
     */
    public static float[] dotsAbsolute(float[] b, int cols, int rows, float stepPx) {
        float[] out = new float[cols * rows * 3];
        int n = 0;
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                float v = Math.min(1f, Math.max(0f, b[cy * cols + cx]));
                if (v < SKIP_BELOW) continue;
                out[n++] = (cx + 0.5f) * stepPx;
                out[n++] = (cy + 0.5f) * stepPx;
                out[n++] = diameter(v, stepPx);
            }
        }
        return Arrays.copyOf(out, n);
    }
}
