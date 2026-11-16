package dev.gawel.dothome.core;

import java.util.HashMap;
import java.util.Map;

import dev.gawel.dothome.test.T;

public final class LatticeRasterTest {
    /** Collects every reported cell; counts repeats. */
    private static final class Grid implements LatticeRaster.CoverageSink {
        final Map<Long, Float> cov = new HashMap<Long, Float>();
        int calls;
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE, minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;

        @Override
        public void cell(int col, int row, float c) {
            calls++;
            cov.put(key(col, row), c);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }

        float at(int col, int row) {
            Float f = cov.get(key(col, row));
            return f == null ? 0f : f;
        }

        double total() {
            double s = 0;
            for (float f : cov.values()) s += f;
            return s;
        }

        static long key(int col, int row) { return ((long) col << 32) ^ (row & 0xFFFFFFFFL); }
    }

    private static Grid raster(float[] xy, float cell, float ox, float oy) {
        int n = xy.length / 2;
        float[] px = new float[n], py = new float[n];
        for (int i = 0; i < n; i++) {
            px[i] = xy[2 * i];
            py[i] = xy[2 * i + 1];
        }
        Grid g = new Grid();
        LatticeRaster.coverage(px, py, n, cell, ox, oy, g);
        return g;
    }

    public static void testAlignedSquareIsFullInsideOnly() {
        // 4x4 cells of 10 px, from cell (2,2) to (5,5)
        Grid g = raster(new float[] {20, 20, 60, 20, 60, 60, 20, 60}, 10f, 0f, 0f);
        T.eq(16, g.calls, "one call per bounding-box cell");
        for (int r = 2; r <= 5; r++) {
            for (int c = 2; c <= 5; c++) T.near(1.0, g.at(c, r), 1e-4, "inside " + c + "," + r);
        }
        T.eq(2, g.minCol, "first col");
        T.eq(5, g.maxCol, "last col (an edge on a cell boundary opens no cell)");
    }

    public static void testAlignedLShapeIsZeroOutside() {
        // the 4x4-cell box minus its bottom-right 2x2 cells: those are inside the bounding box but outside the shape
        Grid g = raster(new float[] {0, 0, 40, 0, 40, 20, 20, 20, 20, 40, 0, 40}, 10f, 0f, 0f);
        T.eq(16, g.calls, "bounding box cells");
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                boolean out = c >= 2 && r >= 2;
                T.near(out ? 0.0 : 1.0, g.at(c, r), 1e-4, (out ? "outside " : "inside ") + c + "," + r);
            }
        }
    }

    public static void testHalfOffsetSquareHalfAtEdges() {
        // the same 4x4-cell square moved half a cell right and down: edges cover half a cell, corners a quarter
        Grid g = raster(new float[] {5, 5, 45, 5, 45, 45, 5, 45}, 10f, 0f, 0f);
        T.eq(25, g.calls, "5x5 cells under the bounding box");
        for (int r = 0; r <= 4; r++) {
            for (int c = 0; c <= 4; c++) {
                boolean edgeC = c == 0 || c == 4, edgeR = r == 0 || r == 4;
                double want = edgeC && edgeR ? 0.25 : edgeC || edgeR ? 0.5 : 1.0;
                T.near(want, g.at(c, r), 1e-4, "cell " + c + "," + r);
            }
        }
    }

    public static void testTriangleTotalIsArea() {
        float[] tri = {3, 7, 97, 21, 40, 88};
        double area = Math.abs((tri[2] - tri[0]) * (tri[5] - tri[1]) - (tri[4] - tri[0]) * (tri[3] - tri[1])) / 2.0;
        Grid g = raster(tri, 10f, 0f, 0f);
        double want = area / (10 * 10);
        T.check(Math.abs(g.total() - want) <= 0.02 * want, "total " + g.total() + " vs area/cell^2 " + want);
        // either winding gives the same coverage
        Grid back = raster(new float[] {40, 88, 97, 21, 3, 7}, 10f, 0f, 0f);
        T.near(g.total(), back.total(), 1e-3, "winding");
    }

    public static void testSinkOnlyForBoundingBoxCells() {
        // bbox x 12.5..37.5, y 3..21 on a 10 px lattice: cols 1..3, rows 0..2, each reported once
        Grid g = raster(new float[] {12.5f, 3, 37.5f, 12, 20, 21}, 10f, 0f, 0f);
        T.eq(9, g.calls, "calls");
        T.eq(9, g.cov.size(), "distinct cells");
        T.eq(1, g.minCol, "min col");
        T.eq(3, g.maxCol, "max col");
        T.eq(0, g.minRow, "min row");
        T.eq(2, g.maxRow, "max row");
        for (float f : g.cov.values()) T.check(f >= 0f && f <= 1f, "coverage in 0..1: " + f);
    }

    public static void testOriginShiftsTheLattice() {
        // a square aligned to a lattice anchored at (3, 7)
        Grid g = raster(new float[] {23, 27, 43, 27, 43, 47, 23, 47}, 10f, 3f, 7f);
        T.eq(4, g.calls, "2x2 cells");
        T.near(1.0, g.at(2, 2), 1e-4, "cell 2,2");
        T.near(1.0, g.at(3, 3), 1e-4, "cell 3,3");
    }

    public static void testEdgeCoverageMovesSmoothly() {
        // sliding a square right by a tenth of a cell at a time: its left edge cell loses exactly a tenth each time
        float prev = -1f;
        for (int i = 0; i <= 10; i++) {
            float dx = i * 1f;
            Grid g = raster(new float[] {20 + dx, 20, 60 + dx, 20, 60 + dx, 60, 20 + dx, 60}, 10f, 0f, 0f);
            float c = g.at(2, 3);
            T.near(1.0 - i / 10.0, c, 1e-4, "left edge at shift " + dx);
            if (prev >= 0) T.check(prev - c < 0.11f, "no jump " + prev + " -> " + c);
            prev = c;
        }
    }

    /** Point-in-polygon (even-odd) for the brute-force reference. */
    private static boolean inside(float[] xy, double x, double y) {
        boolean in = false;
        int n = xy.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xy[2 * i], yi = xy[2 * i + 1], xj = xy[2 * j], yj = xy[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    public static void testRockMatchesSupersampledReference() {
        // a rock-like star-shaped 11-gon, rotated and off the grid
        Rng rng = new Rng(7);
        int n = 11;
        float[] xy = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n + 0.3, rr = 37 * rng.range(0.72, 1.12);
            xy[2 * i] = (float) (113.3 + Math.cos(a) * rr);
            xy[2 * i + 1] = (float) (71.9 + Math.sin(a) * rr);
        }
        float cell = 10.5f;
        Grid g = raster(xy, cell, 0f, 0f);
        int s = 32;
        double worst = 0;
        for (Map.Entry<Long, Float> e : g.cov.entrySet()) {
            int col = (int) (e.getKey() >> 32), row = (int) (long) e.getKey();
            int hits = 0;
            for (int sy = 0; sy < s; sy++) {
                for (int sx = 0; sx < s; sx++) {
                    if (inside(xy, (col + (sx + 0.5) / s) * cell, (row + (sy + 0.5) / s) * cell)) hits++;
                }
            }
            worst = Math.max(worst, Math.abs(hits / (double) (s * s) - e.getValue()));
        }
        T.check(worst < 0.03, "worst cell differs from a 32x32 supersample by " + worst);
    }

    public static void testDegenerateInputReportsNothing() {
        Grid g = new Grid();
        LatticeRaster.coverage(new float[] {0, 10}, new float[] {0, 10}, 2, 10f, 0f, 0f, g);
        T.eq(0, g.calls, "two points are not a polygon");
        LatticeRaster.coverage(new float[] {0, 10, 5}, new float[] {0, 0, 9}, 3, 0f, 0f, 0f, g);
        T.eq(0, g.calls, "zero cell size");
    }

    public static void testCellIndexHelpers() {
        T.eq(2, LatticeRaster.firstCell(20f, 10f, 0f), "first on a boundary");
        T.eq(-1, LatticeRaster.firstCell(-0.5f, 10f, 0f), "first below the origin");
        T.eq(4, LatticeRaster.cellCount(20f, 60f, 10f, 0f), "count stops at a boundary");
        T.eq(5, LatticeRaster.cellCount(5f, 45f, 10f, 0f), "count across a half-offset span");
        T.eq(1, LatticeRaster.cellCount(7f, 7f, 10f, 0f), "a point still has its cell");
    }

    public static void testRejectsNonFiniteOrRunawayInput() {
        Grid g = new Grid();
        T.eq(false, LatticeRaster.coverage(new float[] {0, 10, Float.NaN}, new float[] {0, 0, 9}, 3, 10f, 0f, 0f, g), "NaN x");
        T.eq(false, LatticeRaster.coverage(new float[] {0, 10, 5}, new float[] {0, Float.POSITIVE_INFINITY, 9}, 3, 10f, 0f, 0f, g), "infinite y");
        T.eq(false, LatticeRaster.coverage(new float[] {0, 1e9f, 5}, new float[] {0, 0, 9}, 3, 10f, 0f, 0f, g), "runaway span");
        T.eq(0, g.calls, "nothing reported for rejected input");
        T.eq(false, LatticeRaster.accepts(new float[] {Float.NaN, 1, 2}, new float[] {0, 1, 2}, 3, 10f, 0f, 0f), "accepts: NaN");
        T.eq(true, LatticeRaster.accepts(new float[] {0, 10, 5}, new float[] {0, 0, 9}, 3, 10f, 0f, 0f), "accepts: a triangle");
        T.eq(true, LatticeRaster.coverage(new float[] {0, 10, 5}, new float[] {0, 0, 9}, 3, 10f, 0f, 0f, g), "a triangle rasterises");
        T.check(g.calls > 0, "and reports cells");
    }

    public static void testDotRadiusEndpoints() {
        float rl = 1f, rr = 3f;
        T.near(rl, LatticeRaster.dotRadius(1f, 1f, rl, rr), 1e-6, "body cell (all neighbours full): light");
        T.near(rr, LatticeRaster.dotRadius(1f, 0f, rl, rr), 1e-6, "full cell next to an empty one: regular");
        T.near(0, LatticeRaster.dotRadius(0f, 0f, rl, rr), 1e-6, "empty cell");
        // half-covered edge cell next to the outside: regular weight, ink by the square root of coverage
        T.near(rr * Math.sqrt(0.5) * 1, LatticeRaster.dotRadius(0.5f, 0f, rl, rr), 1e-5, "half rim cell");
        // a partial cell's own coverage also counts as its weakest side
        T.near((rl + (rr - rl) * 0.5) * Math.sqrt(0.5), LatticeRaster.dotRadius(0.5f, 1f, rl, rr), 1e-5, "partial cell, full neighbours");
    }

    public static void testDotRadiusIsContinuous() {
        float rl = 1.2f, rr = 3.15f, d = 0.001f;
        float worst = 0;
        for (float cov = 0; cov <= 1f; cov += 0.01f) {
            for (float nb = 0; nb <= 1f; nb += 0.01f) {
                float r = LatticeRaster.dotRadius(cov, nb, rl, rr);
                worst = Math.max(worst, Math.abs(LatticeRaster.dotRadius(Math.min(1f, cov + d), nb, rl, rr) - r));
                worst = Math.max(worst, Math.abs(LatticeRaster.dotRadius(cov, Math.min(1f, nb + d), rl, rr) - r));
                T.check(r >= 0 && r <= rr + 1e-6, "radius in range " + r);
            }
        }
        // sqrt has its steepest step at 0: sqrt(0.001) x rReg is about 0.1 px here
        T.check(worst < 0.04f * rr, "a 0.001 input step moves the radius by at most " + worst);
    }
}
