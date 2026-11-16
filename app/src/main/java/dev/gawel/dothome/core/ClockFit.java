package dev.gawel.dothome.core;

/**
 * Sizing of the clock widget: the dot-bitmap time ("hh:mm", 25 columns by 7 rows, step 12 dp) with small seconds
 * beside it, inside a 16 dp pad, a {@link #GAP_DP} gap below it a 28 dp text row (date and weather), the whole block centred vertically.
 */
public final class ClockFit {
    private ClockFit() {}

    public static final int PAD_DP = 16;
    /** Bottom row height. */
    public static final int ROW_DP = 28;
    /** Space between the time and the bottom row. */
    public static final int GAP_DP = 12;
    /** "hh:mm": 4 x 600 + 200 - 100 = 2500 units = 25 cells. */
    public static final int MINUTES_COLS = 25;
    public static final int SMALL_INNER_DP = 332;
    /** The seconds: "ss" is 11 columns at step 4 dp, 12 dp right of the time, bottom-aligned with it. */
    public static final int SECS_STEP_DP = 4, SECS_GAP_DP = 12;
    public static final int SECS_W_DP = 11 * SECS_STEP_DP, SECS_H_DP = DotMatrixFont.ROWS * SECS_STEP_DP;

    /** Time step in dp: 12 normally, 8 when the card's inner width is under SMALL_INNER_DP (the cover). */
    public static int cellDp(float innerDp) {
        return innerDp < SMALL_INNER_DP ? 8 : 12;
    }

    public static int timeWidthDp(int cellDp, int cols) {
        return cellDp * cols;
    }

    public static int timeHeightDp(int cellDp) {
        return cellDp * DotMatrixFont.ROWS;
    }

    /** Weather block: icon box, its gap to the temperature, the margin to the time column, and the widest reading ("\u221200\u00B0"). */
    public static final int WX_ICON_DP = 32, WX_ICON_GAP_DP = 8, WX_MARGIN_DP = 12, WX_WORST_COLS = 23;

    /** Width the weather block needs at a matrix step: icon, gap and the worst-case temperature. */
    public static int weatherNeedDp(int stepDp) {
        return WX_ICON_DP + WX_ICON_GAP_DP + WX_WORST_COLS * stepDp;
    }

    /** Width left for the weather beside the time column (timeColDp = widest of time+seconds and the date). */
    public static float weatherAvailDp(float innerDp, float timeColDp) {
        return innerDp - timeColDp - WX_MARGIN_DP;
    }

    /** Matrix step of the right-hand weather block for the room it has: 8, else 4, else 0 (it goes under the date). */
    public static int weatherStepDp(float availDp) {
        if (availDp >= weatherNeedDp(8)) return 8;
        if (availDp >= weatherNeedDp(4)) return 4;
        return 0;
    }
}
