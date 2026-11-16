package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class CardShapeTest {
    private CardShapeTest() {}

    public static void testLauncherRadiusWins() {
        T.near(40f, CardShape.radiusDp(24f, 681, 220), 1e-6, "smaller launcher radius is raised to 40");
        T.near(40f, CardShape.radiusDp(39.6f, 681, 95), 1e-6, "1-row music");
        T.near(48f, CardShape.radiusDp(48f, 681, 220), 1e-6, "larger wins");
        T.near(40f, CardShape.radiusDp(0f, 681, 95), 1e-6, "zero is raised to 40");
    }

    public static void testAbsentOrNonsenseFallsBackTo40() {
        T.near(40f, CardShape.radiusDp(Float.NaN, 681, 470), 1e-6, "absent");
        T.near(40f, CardShape.radiusDp(-3f, 681, 470), 1e-6, "negative");
        T.near(40f, CardShape.radiusDp(Float.POSITIVE_INFINITY, 681, 470), 1e-6, "infinite");
        T.eq("semShapeRadius", CardShape.OPTION_KEY, "key");
    }

    public static void testNeverMoreThanHalfTheShorterSide() {
        T.near(30f, CardShape.radiusDp(39.6f, 681, 60), 1e-6, "clamped");
        T.near(40f, CardShape.radiusDp(39.6f, 0, 0), 1e-6, "unknown size: no clamp");
    }

    public static void testRimInsetIsHalfTheStroke() {
        T.near(1.5f, CardShape.rimInset(3f), 1e-6, "inset");
    }
}
