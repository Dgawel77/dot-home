package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class LabelPickerTest {
    private static final float[] Y0 = new float[8];

    private static float[] spread(int n) {
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = i * 200f;
        return x;
    }

    public static void testTakesInPriorityOrderUpToMax() {
        boolean[] k = LabelPicker.pick(spread(8), Y0, null, 6, 120f);
        for (int i = 0; i < 6; i++) T.check(k[i], "kept " + i);
        T.check(!k[6] && !k[7], "cap of 6");
    }

    public static void testSkipsLabelsTooCloseToAKeptOne() {
        float[] x = {0f, 100f, 300f};
        boolean[] k = LabelPicker.pick(x, new float[3], null, 6, 120f);
        T.check(k[0], "first kept");
        T.check(!k[1], "within 120 of a kept label: skipped");
        T.check(k[2], "far enough");
    }

    public static void testSkippedLabelDoesNotBlockOthers() {
        float[] x = {0f, 100f, 200f};
        boolean[] k = LabelPicker.pick(x, new float[3], null, 6, 120f);
        T.check(k[0] && !k[1] && k[2], "100 is skipped, 200 is 200 from the kept 0");
    }

    public static void testOwnPlaceAlwaysKeptAndBlocksNeighbours() {
        float[] x = {0f, 100f, 1000f};
        boolean[] k = LabelPicker.pick(x, new float[3], new boolean[] {false, true, false}, 6, 120f);
        T.check(k[1], "own place kept despite low priority");
        T.check(!k[0], "higher priority but too close to the own place");
        T.check(k[2], "far one kept");
    }

    public static void testOwnPlaceKeptEvenWhenCapIsFull() {
        float[] x = spread(8);
        boolean[] mine = new boolean[8];
        mine[7] = true;
        boolean[] k = LabelPicker.pick(x, Y0, mine, 3, 120f);
        T.check(k[7], "own place");
        int n = 0;
        for (boolean b : k) if (b) n++;
        T.eq(3, n, "own place counts toward the cap");
        T.check(k[0] && k[1], "the rest by priority");
    }
}
