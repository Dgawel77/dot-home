package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class ShakeDetectorTest {
    private ShakeDetectorTest() {}

    private static final long MS = 1_000_000L, T0 = 1_000_000_000L;

    private static boolean s(ShakeDetector d, float v, long ms) { return d.onSample(v, 0, 0, T0 + ms * MS); }

    public static void testSingleImpulseWithRingingDoesNotShake() {
        ShakeDetector d = new ShakeDetector();
        T.eq(false, s(d, 16, 0), "+16");
        T.eq(false, s(d, 2, 20), "dip");
        T.eq(false, s(d, -15, 40), "-15 ring");
        T.eq(false, s(d, 0, 80), "settle");
    }

    public static void testTwoSeparatedImpulsesShakeThenRefractory() {
        ShakeDetector d = new ShakeDetector();
        T.eq(false, s(d, 16, 0), "first");
        T.eq(false, s(d, 1, 50), "gap");
        T.eq(true, s(d, 16, 250), "second fires");
        T.eq(false, s(d, 1, 300), "gap");
        T.eq(false, s(d, 16, 400), "third blocked");
        T.eq(false, s(d, 1, 450), "gap");
        T.eq(false, s(d, 16, 650), "still blocked");
    }
}
