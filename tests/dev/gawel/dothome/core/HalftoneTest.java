package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class HalftoneTest {
    private HalftoneTest() {}

    public static void testHalftoneThresholdAndMax() {
        T.near(0, Halftone.radius(0.05f, 8f), 0, "below threshold");
        T.near(3.68, Halftone.radius(1f, 8f), 1e-4, "max");
    }
}
