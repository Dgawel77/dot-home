package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class ClockFitTest {
    private ClockFitTest() {}

    public static void testStep12MainAnd8Cover() {
        T.eq(12, ClockFit.cellDp(681 - 32), "main");
        T.eq(12, ClockFit.cellDp(332), "332 is still the main step");
        T.eq(8, ClockFit.cellDp(331), "under 332");
        T.eq(8, ClockFit.cellDp(300), "small inner");
    }

    public static void testSecondsGeometry() {
        T.eq(44, ClockFit.SECS_W_DP, "width");
        T.eq(28, ClockFit.SECS_H_DP, "height");
        T.eq(ClockFit.SECS_W_DP, DotMatrixFont.render("59").cols * ClockFit.SECS_STEP_DP, "ss is 11 columns");
        T.check(ClockFit.timeWidthDp(12, ClockFit.MINUTES_COLS) + ClockFit.SECS_GAP_DP + ClockFit.SECS_W_DP > 0, "sum");
    }

    public static void testColumnsMatchTheFont() {
        T.eq(ClockFit.MINUTES_COLS, DotMatrixFont.render("12:41").cols, "minutes");
        T.eq(84, ClockFit.timeHeightDp(12), "height");
    }

    public static void testWeatherStepByRoom() {
        T.eq(224, ClockFit.weatherNeedDp(8), "S8 worst case");
        T.eq(132, ClockFit.weatherNeedDp(4), "S4 worst case");
        T.eq(8, ClockFit.weatherStepDp(ClockFit.weatherAvailDp(649, 356)), "main fits S8");
        T.eq(4, ClockFit.weatherStepDp(200), "narrow: S4");
        T.eq(0, ClockFit.weatherStepDp(ClockFit.weatherAvailDp(300, 256)), "cover: under the date");
    }
}
