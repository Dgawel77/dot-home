package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class ClockFitTest {
    private ClockFitTest() {}

    public static void testMainClock681x220() {
        // width-limited: (681 - 32) / 39 = 16.64; height allows (220 - 32 - 28 - 8) / 7 = 21.7
        T.near(649f / 39f, ClockFit.fitDp(681, 220, ClockFit.SECONDS_COLS), 1e-4, "fit");
        int cell = ClockFit.cellDp(681, 220, ClockFit.SECONDS_COLS);
        T.eq(16, cell, "on the lattice");
        T.eq(160, ClockFit.textSizeDp(cell), "text size");
        T.eq(624, ClockFit.timeWidthDp(cell, ClockFit.SECONDS_COLS), "time width");
        T.eq(112, ClockFit.timeHeightDp(cell), "time height");
    }

    public static void testSecondClock385x212() {
        // (385 - 32) / 39 = 9.05 -> 8 keeps 88% -> lattice
        int cell = ClockFit.cellDp(385, 212, ClockFit.SECONDS_COLS);
        T.eq(8, cell, "lattice");
        T.eq(80, ClockFit.textSizeDp(cell), "text size");
        T.check(ClockFit.timeWidthDp(cell, ClockFit.SECONDS_COLS) <= 385 - 32, "fits the width");
    }

    public static void testDefault250x110() {
        // (250 - 32) / 39 = 5.59 -> 4 keeps only 72% -> exact fit floored to 5
        int cell = ClockFit.cellDp(250, 110, ClockFit.SECONDS_COLS);
        T.eq(5, cell, "exact fit");
        T.eq(50, ClockFit.textSizeDp(cell), "text size");
        int usedH = ClockFit.timeHeightDp(cell) + ClockFit.GAP_DP + ClockFit.ROW_DP + 2 * ClockFit.PAD_DP;
        T.check(usedH <= 110, "fits the height: " + usedH);
    }

    public static void testHeightLimited() {
        // 681 x 120: (120 - 68) / 7 = 7.43 -> 4 keeps 54% -> 7
        T.eq(7, ClockFit.cellDp(681, 120, ClockFit.SECONDS_COLS), "short widget");
    }

    public static void testWholeFitStaysWhole() {
        // 39 x 16 + 32 = 656: the fit is exactly 16
        T.eq(16, ClockFit.cellDp(656, 400, ClockFit.SECONDS_COLS), "exact 16");
        T.eq(15, ClockFit.cellDp(655, 400, ClockFit.SECONDS_COLS), "just under 16, 12 keeps < 85%");
    }

    public static void testMinutesBitmapFallback() {
        T.eq(20, ClockFit.cellDp(681, 220, ClockFit.MINUTES_COLS), "681x220 height-limited 21.7 -> 20");
        T.eq(14, ClockFit.cellDp(385, 212, ClockFit.MINUTES_COLS), "385x212 14.12 -> 12 is < 85% -> 14");
    }

    public static void testTinyNeverZero() {
        T.eq(1, ClockFit.cellDp(40, 40, ClockFit.SECONDS_COLS), "at least 1");
    }

    public static void testColumnsMatchTheFont() {
        // hh:mm:ss = 6 digits + 2 colons + 7 one-cell gaps; hh:mm = 4 digits + 1 colon + 4 gaps
        T.eq(ClockFit.SECONDS_COLS, 6 * DotMatrixFont.GLYPH_COLS + 2 + 7, "seconds");
        T.eq(ClockFit.MINUTES_COLS, DotMatrixFont.render("12:41").cols, "minutes");
        T.eq(ClockFit.SECONDS_COLS, DotMatrixFont.render("12:41:00").cols, "seconds via render");
        T.eq(4, ClockFit.rowStepDp(), "row step");
    }
}
