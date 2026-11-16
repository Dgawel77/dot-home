package dev.gawel.dothome.core;

import java.util.List;

/**
 * Clear space behind map labels: each text rect (x0, y0, x1, y1) grows by a pad on every side, and a lattice dot
 * whose disc touches any padded rect is dropped. A hard cut, no dither, so the text reads cleanly.
 */
public final class LabelClear {
    private final float[][] rects; // padded x0, y0, x1, y1; an array so hits() allocates nothing

    private LabelClear(List<float[]> text, float pad) {
        rects = new float[text.size()][];
        for (int i = 0; i < rects.length; i++) {
            float[] t = text.get(i);
            rects[i] = new float[] {t[0] - pad, t[1] - pad, t[2] + pad, t[3] + pad};
        }
    }

    public static LabelClear of(List<float[]> textRects, float padPx) {
        return new LabelClear(textRects, padPx);
    }

    /** True when the disc at (x, y) with radius r intersects a padded label rect. */
    public boolean hits(float x, float y, float r) {
        for (float[] q : rects) {
            float dx = Math.max(Math.max(q[0] - x, x - q[2]), 0f);
            float dy = Math.max(Math.max(q[1] - y, y - q[3]), 0f);
            if (dx * dx + dy * dy < r * r || (dx == 0f && dy == 0f)) return true;
        }
        return false;
    }
}
