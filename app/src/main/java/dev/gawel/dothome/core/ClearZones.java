package dev.gawel.dothome.core;

import java.util.List;

/**
 * Where widgets sit, the dot field is thinned: nothing inside a zone, full density far away, and an
 * ordered-dither band in between so the field fades toward the zone without flicker.
 */
public final class ClearZones {
    private static final int[][] BAYER = {
        {0, 8, 2, 10},
        {12, 4, 14, 6},
        {3, 11, 1, 9},
        {15, 7, 13, 5},
    };

    private final float[][] rects; // x, y, w, h; an array so keep() and inside() allocate no iterator per call
    private final float edge;
    private final float cell;

    private ClearZones(List<float[]> rects, float edgePx, float cellPx) {
        this.rects = rects.toArray(new float[0][]);
        this.edge = Math.max(edgePx, 0f);
        this.cell = Math.max(cellPx, 1f);
    }

    /** cellPx is the lattice step: the dither runs per lattice cell, so whole dots drop out. */
    public static ClearZones of(List<float[]> rects, float edgePx, float cellPx) {
        return new ClearZones(rects, edgePx, cellPx);
    }

    public boolean inside(float x, float y) {
        for (float[] r : rects) {
            if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return true;
        }
        return false;
    }

    private float distance(float x, float y) {
        float best = Float.MAX_VALUE;
        for (float[] r : rects) {
            float dx = Math.max(Math.max(r[0] - x, x - (r[0] + r[2])), 0f);
            float dy = Math.max(Math.max(r[1] - y, y - (r[1] + r[3])), 0f);
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }

    /** 0 inside a zone, 1 beyond edgePx; between, 0 or 1 by a fixed 4x4 Bayer threshold on the cell index. */
    public float keep(float x, float y) {
        if (inside(x, y)) return 0f;
        float d = distance(x, y);
        if (d >= edge) return 1f;
        int cx = (int) Math.floor(x / cell), cy = (int) Math.floor(y / cell);
        float threshold = (BAYER[cy & 3][cx & 3] + 0.5f) / 16f;
        return d / edge > threshold ? 1f : 0f;
    }
}
