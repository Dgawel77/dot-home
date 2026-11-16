package dev.gawel.dothome.core;

import java.util.Arrays;

/**
 * Rasterises a polygon onto a square lattice: for each cell under the polygon's bounding box, the share of the
 * cell's area inside the polygon. The area is exact (signed-area accumulation along each cell row, the font
 * rasteriser method), so a shape sliding across the fixed lattice changes its edge cells continuously and edge
 * dots grow and shrink smoothly instead of popping. Either winding works; the polygon must not self-intersect.
 * Nothing is allocated per call once the per-thread row buffer is big enough.
 */
public final class LatticeRaster {
    private LatticeRaster() {}

    /** Receives one call per bounding-box cell, row by row: lattice column and row, coverage 0..1. */
    public interface CoverageSink {
        void cell(int col, int row, float cov);
    }

    private static final ThreadLocal<float[][]> SCRATCH = new ThreadLocal<float[][]>() {
        @Override
        protected float[][] initialValue() {
            return new float[][] {new float[64]};
        }
    };

    /** Index of the lattice cell holding coordinate v, for a lattice of cellPx anchored at origin. */
    public static int firstCell(float v, float cellPx, float origin) {
        return (int) Math.floor((v - origin) / cellPx);
    }

    /** Cells spanned by min..max; a max on a cell boundary does not open the next cell. At least 1. */
    public static int cellCount(float min, float max, float cellPx, float origin) {
        int first = firstCell(min, cellPx, origin);
        int last = (int) Math.ceil((max - origin) / cellPx) - 1;
        return Math.max(1, last - first + 1);
    }

    /**
     * Reports (col, row, coverage) for every lattice cell whose square meets the polygon's bounding box. Columns
     * and rows count cellPx steps from (originX, originY). Polygons with fewer than 3 points, or a cell size that
     * is not positive, report nothing.
     */
    public static void coverage(float[] polyX, float[] polyY, int n, float cellPx, float originX, float originY,
                                CoverageSink sink) {
        if (n < 3 || !(cellPx > 0f) || polyX.length < n || polyY.length < n) return;
        float minX = polyX[0], maxX = minX, minY = polyY[0], maxY = minY;
        for (int i = 1; i < n; i++) {
            float x = polyX[i], y = polyY[i];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        if (!finite(minX) || !finite(maxX) || !finite(minY) || !finite(maxY)) return;
        int col0 = firstCell(minX, cellPx, originX), row0 = firstCell(minY, cellPx, originY);
        int cols = cellCount(minX, maxX, cellPx, originX), rows = cellCount(minY, maxY, cellPx, originY);
        float[][] holder = SCRATCH.get();
        if (holder[0].length < cols + 2) holder[0] = new float[Math.max(cols + 2, 2 * holder[0].length)];
        float[] acc = holder[0];
        for (int row = 0; row < rows; row++) {
            Arrays.fill(acc, 0, cols + 2, 0f);
            float top = row, bottom = row + 1;
            for (int i = 0; i < n; i++) {
                int j = i + 1 < n ? i + 1 : 0;
                // grid units, relative to the bounding box's first cell
                float y0 = (polyY[i] - originY) / cellPx - row0, y1 = (polyY[j] - originY) / cellPx - row0;
                if (y0 == y1) continue; // horizontal edges add no area
                float x0 = (polyX[i] - originX) / cellPx - col0, x1 = (polyX[j] - originX) / cellPx - col0;
                float dir = 1f;
                if (y0 > y1) {
                    float t = y0; y0 = y1; y1 = t;
                    t = x0; x0 = x1; x1 = t;
                    dir = -1f;
                }
                if (y1 <= top || y0 >= bottom) continue;
                float dxdy = (x1 - x0) / (y1 - y0);
                float ya = Math.max(y0, top), yb = Math.min(y1, bottom);
                float xa = x0 + (ya - y0) * dxdy, xb = x0 + (yb - y0) * dxdy;
                accumulate(acc, clamp(xa, cols), clamp(xb, cols), (yb - ya) * dir);
            }
            float sum = 0f;
            for (int col = 0; col < cols; col++) {
                sum += acc[col];
                float cov = Math.abs(sum);
                sink.cell(col0 + col, row0 + row, cov > 1f ? 1f : cov);
            }
        }
    }

    /**
     * Adds one edge piece inside a cell row: it runs from x0 to x1 (grid units) over a height d (signed by
     * direction). Each a[i] is the change in coverage from cell i - 1 to cell i, so a running sum along the row
     * gives the area to the right of the edge in each cell.
     */
    private static void accumulate(float[] a, float x0, float x1, float d) {
        float xa = Math.min(x0, x1), xb = Math.max(x0, x1);
        float xaFloor = (float) Math.floor(xa);
        int ia = (int) xaFloor;
        float xbCeil = (float) Math.ceil(xb);
        int ib = (int) xbCeil;
        if (ib <= ia + 1) {
            // within one cell: the area right of the edge is 1 - (its mean x within the cell)
            float mid = 0.5f * (x0 + x1) - xaFloor;
            a[ia] += d - d * mid;
            a[ia + 1] += d * mid;
            return;
        }
        float s = 1f / (xb - xa);
        float fa = xa - xaFloor;
        float first = 0.5f * s * (1f - fa) * (1f - fa);
        float fb = xb - xbCeil + 1f;
        float last = 0.5f * s * fb * fb;
        a[ia] += d * first;
        if (ib == ia + 2) {
            a[ia + 1] += d * (1f - first - last);
        } else {
            float second = s * (1.5f - fa);
            a[ia + 1] += d * (second - first);
            for (int i = ia + 2; i < ib - 1; i++) a[i] += d * s;
            float upTo = second + (ib - ia - 3) * s;
            a[ib - 1] += d * (1f - upTo - last);
        }
        a[ib] += d * last;
    }

    private static float clamp(float v, int cols) { return v < 0f ? 0f : v > cols ? cols : v; }

    private static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }
}
