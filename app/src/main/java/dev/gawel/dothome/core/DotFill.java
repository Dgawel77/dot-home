package dev.gawel.dothome.core;

import java.util.Arrays;

/** Samples an alpha mask on a pitch grid: which grid points are lit (glyph ink) and which stay as unlit dots. */
public final class DotFill {
    private DotFill() {}

    /** Default lit threshold (0-255 mean alpha): low enough that bold-glyph strokes about two pitches wide never drop out. */
    public static final float DEFAULT_THRESHOLD = 96f;

    /** Dot centres in mask pixel coordinates. */
    public static final class Dots {
        public final float[] litX, litY, unlitX, unlitY;

        Dots(float[] litX, float[] litY, float[] unlitX, float[] unlitY) {
            this.litX = litX;
            this.litY = litY;
            this.unlitX = unlitX;
            this.unlitY = unlitY;
        }
    }

    /**
     * Grid points at ((i + 0.5) * pitch, (j + 0.5) * pitch) cover the whole w x h mask. A point is lit when the
     * mean alpha over the box of +/- pitch/4 around it (clipped to the mask; a point wholly outside it is unlit) is at least threshold.
     */
    public static Dots sample(int[] alpha, int w, int h, float pitch, float threshold) {
        int cols = Math.max(1, (int) Math.ceil(w / pitch - 1e-4));
        int rows = Math.max(1, (int) Math.ceil(h / pitch - 1e-4));
        float[] lx = new float[cols * rows], ly = new float[cols * rows];
        float[] ux = new float[cols * rows], uy = new float[cols * rows];
        int nl = 0, nu = 0;
        float half = pitch / 4f;
        for (int j = 0; j < rows; j++) {
            float cy = (j + 0.5f) * pitch;
            int y0 = Math.max(0, (int) Math.ceil(cy - half - 0.5f)), y1 = Math.min(h - 1, (int) Math.floor(cy + half - 0.5f));
            for (int i = 0; i < cols; i++) {
                float cx = (i + 0.5f) * pitch;
                int x0 = Math.max(0, (int) Math.ceil(cx - half - 0.5f)), x1 = Math.min(w - 1, (int) Math.floor(cx + half - 0.5f));
                long sum = 0;
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) sum += alpha[y * w + x] & 0xFF;
                int n = (x1 - x0 + 1) * (y1 - y0 + 1);
                float mean = (x1 < x0 || y1 < y0 || n <= 0) ? 0f : sum / (float) n;
                if (mean >= threshold) { lx[nl] = cx; ly[nl++] = cy; } else { ux[nu] = cx; uy[nu++] = cy; }
            }
        }
        return new Dots(Arrays.copyOf(lx, nl), Arrays.copyOf(ly, nl), Arrays.copyOf(ux, nu), Arrays.copyOf(uy, nu));
    }
}
