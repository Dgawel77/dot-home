package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class DotScaleTest {
    private DotScaleTest() {}

    public static void testScaleAtFold6Density() {
        T.near(3.54375, DotScale.radiusPx(2.625f), 1e-4, "radius");
        T.near(11.8125, DotScale.pitchPx(2.625f), 1e-4, "pitch");
    }

    public static void testMatchesLegacyWallpaper() {
        T.near(1.2 * 2.95, DotScale.radiusPx(2.625f), 0.01, "radius vs legacy");
        T.near(4 * 2.95, DotScale.pitchPx(2.625f), 0.02, "pitch vs legacy");
    }

    public static void testGlyphRows() {
        T.near(14, DotScale.glyphPitchRows(63 * 2.625f, 2.625f), 1e-3, "rows");
    }

    public static void testPaletteHasNoPureWhite() {
        T.check(DotStyle.FG != 0xFFFFFFFF, "FG is pure white");
    }
}
