package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class RadarScaleTest {
    private RadarScaleTest() {}

    private static int colourFor(int dbz) {
        for (int[] e : RadarScale.PALETTE) if (e[1] == dbz) return 0xFF000000 | e[0];
        throw new AssertionError("no palette entry for " + dbz);
    }

    public static void testRadarScaleTransparentZero() {
        T.near(0, RadarScale.intensity(0x00000000), 0, "transparent");
    }

    public static void testRadarScaleMonotonic() {
        float a = RadarScale.intensity(colourFor(20)), b = RadarScale.intensity(colourFor(40)),
            c = RadarScale.intensity(colourFor(60));
        T.check(a < b && b < c, a + " " + b + " " + c);
        T.near(1, c, 1e-6, "60 dBZ is full");
    }

    public static void testRadarScaleLowIsFaint() {
        T.near(0, RadarScale.intensity(colourFor(5)), 0, "5 dBZ");
    }
}
