package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class FoldBurstTest {
    public static void testUnfoldStartsCoverSized() {
        float[] s = FoldBurst.startScale(968, 2376, 1856, 2160);
        T.near(0.5216f, s[0], 1e-3f, "v");
        T.near(1f, s[1], 1e-6f, "v");
    }

    public static void testFoldStartsOutside() {
        float[] s = FoldBurst.startScale(1856, 2160, 968, 2376);
        T.near(1.917f, s[0], 1e-3f, "v");
        T.near(1f, s[1], 1e-6f, "v");
    }

    public static void testRotationNoBurst() {
        float[] s = FoldBurst.startScale(1856, 2160, 2160, 1856);
        T.near(1f, s[0], 1e-6f, "v");
        T.near(1f, s[1], 1e-6f, "v");
    }

    public static void testCoverRotationNoBurst() {
        float[] s = FoldBurst.startScale(968, 2376, 2376, 968);
        T.near(1f, s[0], 1e-6f, "cover->landscape x");
        T.near(1f, s[1], 1e-6f, "cover->landscape y");
        s = FoldBurst.startScale(2376, 968, 968, 2376);
        T.near(1f, s[0], 1e-6f, "landscape->cover x");
        T.near(1f, s[1], 1e-6f, "landscape->cover y");
    }

    public static void testScaleEndsAtOne() {
        T.near(1f, FoldBurst.scaleAt(0.52f, FoldBurst.SECONDS), 1e-6f, "v");
        T.near(1f, FoldBurst.scaleAt(1.9f, FoldBurst.SECONDS), 1e-6f, "v");
    }

    public static void testUnfoldOvershootSmall() {
        float max = 0;
        for (int i = 0; i <= 600; i++) max = Math.max(max, FoldBurst.scaleAt(0.52f, i / 1000f));
        T.check(max > 1f && max <= 1.08f, "overshoot " + max);
    }

    public static void testFoldNoOvershoot() {
        float prev = Float.MAX_VALUE;
        for (int i = 0; i <= 700; i++) {
            float v = FoldBurst.scaleAt(1.9f, i / 1000f);
            T.check(v >= 1f && v <= prev + 1e-6f, "fold monotone at " + i);
            prev = v;
        }
    }

    public static void testWorldBurst() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(968, 2376);
        T.near(1f, g.burstScaleX(), 1e-6f, "v"); // the first size arms nothing
        g.resize(1856, 2160);
        T.check(g.burstScaleX() < 0.6f, "starts small " + g.burstScaleX());
        for (int i = 0; i < 44; i++) g.step(0.016f);
        T.near(1f, g.burstScaleX(), 1e-6f, "v");
        T.near(1f, g.burstScaleY(), 1e-6f, "v");
    }
}
