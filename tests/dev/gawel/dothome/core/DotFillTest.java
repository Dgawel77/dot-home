package dev.gawel.dothome.core;

import java.util.Arrays;

import dev.gawel.dothome.test.T;

public final class DotFillTest {
    private DotFillTest() {}

    private static int[] mask(int w, int h, int fill) {
        int[] a = new int[w * h];
        Arrays.fill(a, fill);
        return a;
    }

    public static void testFilledSquareIsAllLit() {
        DotFill.Dots d = DotFill.sample(mask(40, 40, 255), 40, 40, 10f, 96f);
        T.eq(16, d.litX.length, "lit");
        T.eq(0, d.unlitX.length, "unlit");
        T.eq(16, d.litY.length, "litY");
    }

    public static void testEmptyMaskIsAllUnlit() {
        DotFill.Dots d = DotFill.sample(mask(40, 40, 0), 40, 40, 10f, 96f);
        T.eq(0, d.litX.length, "lit");
        T.eq(16, d.unlitX.length, "unlit");
    }

    public static void testThresholdEdge() {
        T.eq(16, DotFill.sample(mask(40, 40, 96), 40, 40, 10f, 96f).litX.length, "equal to threshold is lit");
        T.eq(0, DotFill.sample(mask(40, 40, 95), 40, 40, 10f, 96f).litX.length, "below threshold is unlit");
    }

    public static void testPointsSitOnPitchGrid() {
        DotFill.Dots d = DotFill.sample(mask(40, 20, 0), 40, 20, 10f, 96f);
        T.near(5, d.unlitX[0], 1e-4, "first x");
        T.near(5, d.unlitY[0], 1e-4, "first y");
        T.eq(8, d.unlitX.length, "4 cols x 2 rows");
    }

    /** A stem about 2.2 pitches wide (Inter Bold "1" at 52dp) must light a dot in every grid row at any offset. */
    public static void testThinStemNeverDropsOut() {
        float pitch = 11.8f;
        int w = 80, h = 200, stem = Math.round(2.2f * pitch);
        for (int off = 0; off < 12; off++) {
            int[] m = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 20 + off; x < 20 + off + stem; x++) m[y * w + x] = 255;
            DotFill.Dots d = DotFill.sample(m, w, h, pitch, 96f);
            int rows = (int) Math.ceil(h / pitch);
            T.check(d.litX.length >= rows, "stem offset " + off + " lit " + d.litX.length + " of " + rows + " rows");
        }
    }

    /** A colon dot about 2 pitches square keeps at least one lit point. */
    public static void testColonBlobStaysLit() {
        float pitch = 11.8f;
        int w = 60, h = 60, s = Math.round(2.2f * pitch);
        for (int off = 0; off < 12; off++) {
            int[] m = new int[w * h];
            for (int y = 10 + off; y < 10 + off + s; y++) for (int x = 10 + off; x < 10 + off + s; x++) m[y * w + x] = 255;
            T.check(DotFill.sample(m, w, h, pitch, 96f).litX.length >= 1, "blob offset " + off);
        }
    }
}
