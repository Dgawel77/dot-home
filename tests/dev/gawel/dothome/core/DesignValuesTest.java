package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.ArrayList;
import java.util.List;

public final class DesignValuesTest {
    private DesignValuesTest() {}

    public static void testGreyLevelsExact() {
        T.eq(0xFF000000, DotStyle.GROUND, "ground");
        T.eq(0xFFDDDDDD, DotStyle.L100, "L100");
        T.eq(0xFFA6A6A6, DotStyle.L65, "L65");
        T.eq(0xFF757575, DotStyle.L46, "L46");
        T.eq(0xFF1A1A1A, DotStyle.L10, "L10");
        T.eq(DotStyle.GROUND, DotStyle.BG, "BG alias");
        T.eq(DotStyle.L100, DotStyle.FG, "FG alias");
        T.eq(DotStyle.L65, DotStyle.FG2, "FG2 alias");
        T.eq(DotStyle.L10, DotStyle.FAINT, "FAINT alias");
    }

    public static void testLatticeRadius() {
        T.near(9.45, Lattice.radiusPx(Lattice.Step.S12, Lattice.Weight.REGULAR, 2.625f), 1e-4, "S12 regular");
        T.near(4 * 0.3f / 2, Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.LIGHT, 1f), 1e-5, "S4 light");
        T.near(16 * 0.85f / 2, Lattice.radiusPx(Lattice.Step.S16, Lattice.Weight.HEAVY, 1f), 1e-5, "S16 heavy");
        T.near(21f, Lattice.stepPx(Lattice.Step.S8, 2.625f), 1e-4, "S8 stepPx");
    }

    public static void testLatticeSteps() {
        T.eq(4f, Lattice.STEP_DP, "step dp");
        Lattice.Step[] s = Lattice.Step.values();
        T.eq(4, s.length, "four steps");
        int[] dp = {4, 8, 12, 16};
        for (int i = 0; i < 4; i++) T.eq(dp[i], s[i].dp, "step " + i);
        T.eq(3, Lattice.Weight.values().length, "three weights");
    }

    public static void testLatticeSnap() {
        T.near(10.5f, Lattice.snap(11f, 2.625f), 1e-4, "snap down");
        T.near(10.5f, Lattice.snap(10.5f, 2.625f), 1e-4, "snap exact");
        T.near(21f, Lattice.snap(17f, 2.625f), 1e-4, "snap up");
        T.near(0f, Lattice.snap(2f, 2.625f), 1e-4, "snap zero");
    }

    public static void testNewGlyphs() {
        char[] cs = {'°', '−', '.', '%'};
        for (char c : cs) {
            T.eq(5, DotMatrixFont.width(c), "width " + c);
            T.eq(35, DotMatrixFont.glyph(c).length, "size " + c);
        }
        boolean any = false;
        for (boolean b : DotMatrixFont.glyph('%')) any |= b;
        T.check(any, "% has lit cells");
        boolean[] dot = DotMatrixFont.glyph('.');
        T.check(dot[6 * 5 + 2], "dot bottom centre");
        boolean[] minus = DotMatrixFont.glyph('−');
        T.check(minus[3 * 5] && minus[3 * 5 + 4], "minus mid row");
    }

    public static void testClockText() {
        T.eq("09:05", DotMatrixFont.clockText(9, 5, false), "09:05");
        T.eq("01:05", DotMatrixFont.clockText(13, 5, false), "01:05");
        T.eq("12:00", DotMatrixFont.clockText(0, 0, false), "midnight 12h");
        T.eq("12:30", DotMatrixFont.clockText(12, 30, false), "noon 12h");
        T.eq("13:05", DotMatrixFont.clockText(13, 5, true), "24h");
        T.eq("00:07", DotMatrixFont.clockText(0, 7, true), "24h midnight");
    }

    public static void testFixedAdvance() {
        T.eq(DotMatrixFont.render("00:00").cols, DotMatrixFont.render("11:11").cols, "same width");
        T.eq(25, DotMatrixFont.render("12:41").cols, "12:41 cols");
        DotMatrixFont.Grid g = DotMatrixFont.render("1:");
        T.eq(7, g.cols, "1 + gap + colon");
        T.eq(DotMatrixFont.cells("12:41").length, 25 * 7, "cells size");
    }

    public static void testCellsAllCells() {
        byte[] c = DotMatrixFont.cells("1");
        T.eq(35, c.length, "cells");
        T.eq(1, (int) c[2], "lit");
        T.eq(0, (int) c[0], "unlit");
        T.eq(-1, (int) DotMatrixFont.cells("1.")[5], "gap column absent");
    }

    private static int[] solid(int w, int h, int v) {
        int[] p = new int[w * h];
        java.util.Arrays.fill(p, 0xFF000000 | (v << 16) | (v << 8) | v);
        return p;
    }

    public static void testHalftoneTransparentNoDots() {
        int[] p = new int[16 * 16];
        java.util.Arrays.fill(p, 0x00FFFFFF);
        T.eq(0, Halftone.dots(p, 16, 16, 4).length, "transparent white");
    }

    private static String bitmap(char c) {
        boolean[] g = DotMatrixFont.glyph(c);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 7; r++) {
            if (r > 0) sb.append(',');
            for (int x = 0; x < 5; x++) sb.append(g[r * 5 + x] ? '1' : '0');
        }
        return sb.toString();
    }

    public static void testDegreeAndPercentBitmaps() {
        T.eq("01110,01010,01110,00000,00000,00000,00000", bitmap('\u00B0'), "degree");
        T.eq("11000,11001,00010,00100,01000,10011,00011", bitmap('%'), "percent");
    }

    public static void testDigitsShareAdvance() {
        T.eq(DotMatrixFont.render("00:00").cols, DotMatrixFont.render("11:11").cols, "digit advance");
    }

    public static void testHalftoneBlackNoDots() {
        T.eq(0, Halftone.dots(solid(16, 16, 0), 16, 16, 4).length, "black");
    }

    public static void testHalftoneWhiteFull() {
        float[] d = Halftone.dots(solid(16, 8, 255), 16, 8, 4);
        T.eq(8 * 3, d.length, "8 cells");
        for (int i = 0; i < d.length; i += 3) T.near(0.85 * 4, d[i + 2], 1e-4, "diameter");
        T.near(2, d[0], 1e-4, "cx");
        T.near(2, d[1], 1e-4, "cy");
    }

    public static void testHalftoneSquareLattice() {
        float[] d = Halftone.dots(solid(16, 16, 255), 16, 16, 4);
        for (int i = 0; i < d.length; i += 3) {
            T.near(0, (d[i] - 2) % 4, 1e-4, "x on lattice");
            T.near(0, (d[i + 1] - 2) % 4, 1e-4, "y on lattice, no row offset");
        }
    }

    /** Left half value a, right half value b, plus a few extremes so the stretch is identity. */
    private static int[] halves(int w, int h, int a, int b) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = x < w / 2 ? a : b;
                p[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return p;
    }

    public static void testHalftoneSkipsBelowTenPercent() {
        // 100 cells at step 1: 4 black, 4 white, then 96 split across 9% / 50%
        int w = 100, h = 1;
        int[] p = new int[w];
        for (int i = 0; i < w; i++) {
            int v = i < 4 ? 0 : i < 8 ? 255 : i < 50 ? 23 : 128; // 23/255 = 0.090
            p[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        float[] d = Halftone.dots(p, w, h, 1);
        // stretch lo=0 hi=255 (2% of 100 = index 2 -> lo is value at sorted[2]); 9% cells must be absent
        boolean found9 = false;
        for (int i = 0; i < d.length; i += 3) if (d[i] > 8 && d[i] < 50) found9 = true;
        T.check(!found9, "9% cells skipped");
        boolean has50 = false;
        for (int i = 0; i < d.length; i += 3) if (d[i] > 50) has50 = true;
        T.check(has50, "50% cells kept");
    }

    public static void testHalftoneContrastStretch() {
        // flat mid-grey 40..120 gradient: 2nd percentile maps to 0 (no dot), 98th to full
        int w = 100, h = 1;
        int[] p = new int[w];
        for (int i = 0; i < w; i++) {
            int v = 40 + i * 80 / 99;
            p[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        float[] d = Halftone.dots(p, w, h, 1);
        boolean first = false, last = false;
        float maxDia = 0;
        for (int i = 0; i < d.length; i += 3) {
            if (d[i] < 1) first = true;
            if (d[i] > 98) last = true;
            maxDia = Math.max(maxDia, d[i + 2]);
        }
        T.check(!first, "darkest cell stretched to 0 and skipped");
        T.check(last, "brightest cell present");
        T.near(0.85, maxDia, 1e-4, "98th percentile maps to full");
    }

    public static void testHalftoneHalfLit() {
        float[] d = Halftone.dots(halves(4, 4, 0, 255), 4, 4, 2);
        T.eq(2 * 2 / 2 * 3, d.length, "half the cells lit");
    }

    private static ClearZones zone() {
        List<float[]> r = new ArrayList<float[]>();
        r.add(new float[] {100, 100, 200, 100}); // x,y,w,h
        return ClearZones.of(r, 40f, 4f);
    }

    public static void testZonesInsideAndFar() {
        ClearZones z = zone();
        T.check(z.inside(150, 150), "inside");
        T.check(!z.inside(50, 50), "outside");
        T.eq(0f, z.keep(150, 150), "inside keep");
        T.eq(1f, z.keep(10, 10), "far keep");
        T.eq(1f, z.keep(100 - 41, 150), "just beyond edge");
    }

    public static void testZonesBandDeterministicAndBinary() {
        ClearZones z = zone();
        for (float x = 60; x < 100; x += 3) {
            float a = z.keep(x, 150), b = z.keep(x, 150);
            T.eq(a, b, "deterministic");
            T.check(a == 0f || a == 1f, "binary");
        }
    }

    public static void testZonesBandThinsTowardZone() {
        ClearZones z = zone();
        // sample cells (step 4 px) in far-third vs near-third of the band left of the zone
        int nearKept = 0, farKept = 0, n = 0;
        for (int cy = 25; cy < 50; cy++) {
            for (int cx = 0; cx < 10; cx++) {
                float yy = cy * 4 + 2;
                float xFar = 62 + cx * 1.0f;   // 38..28 px from edge? distance = 100 - x
                float xNear = 90 + cx * 0.9f;  // ~10..2 px from edge
                farKept += z.keep(xFar, yy) > 0 ? 1 : 0;
                nearKept += z.keep(xNear, yy) > 0 ? 1 : 0;
                n++;
            }
        }
        T.check(farKept > nearKept, "kept share rises with distance: far " + farKept + " near " + nearKept);
        T.check(nearKept < n, "near thinned");
    }
}
