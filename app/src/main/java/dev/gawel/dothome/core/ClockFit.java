package dev.gawel.dothome.core;

/**
 * Sizing of the clock widget: the time spans the full width (inside a 16 dp pad) above a 28 dp bottom row with
 * the date and the weather. The time is set in the dot-matrix font (res/font, built from {@link DotMatrixFont}),
 * whose em is 1000 units and whose cell is 100, so the text size is 10 cells. All sizes are whole dp.
 */
public final class ClockFit {
    private ClockFit() {}

    public static final int PAD_DP = 16;
    /** Bottom row height: 7 rows of the S4 step, so the matrix temperature fills it exactly. */
    public static final int ROW_DP = 28;
    /** Space between the time and the bottom row. */
    public static final int GAP_DP = 8;
    /** "hh:mm:ss": 6 digits x 600 units + 2 colons x 200 - the trailing 100-unit gap = 3900 units = 39 cells. */
    public static final int SECONDS_COLS = 39;
    /** "hh:mm" (the per-minute bitmap fallback): 4 x 600 + 200 - 100 = 2500 units = 25 cells. */
    public static final int MINUTES_COLS = 25;
    /** Matrix font cells per em (1000 / 100 units). */
    public static final int CELLS_PER_EM = 10;
    /** The 4 dp lattice cell is used while it keeps at least this share of the exact fit. */
    public static final float LATTICE_KEEP = 0.85f;

    /** Exact (unrounded) cell size in dp for a cols x 7 time in a wDp x hDp widget; may be <= 0 for tiny widgets. */
    public static float fitDp(float wDp, float hDp, int cols) {
        float byWidth = (wDp - 2 * PAD_DP) / cols;
        float byHeight = (hDp - 2 * PAD_DP - ROW_DP - GAP_DP) / DotMatrixFont.ROWS;
        return Math.min(byWidth, byHeight);
    }

    /**
     * The cell in whole dp: the fit floored to a multiple of 4 dp (the design lattice) when that keeps at least 85%
     * of the exact fit, otherwise the fit floored to 1 dp. Never below 1.
     */
    public static int cellDp(float wDp, float hDp, int cols) {
        float fit = fitDp(wDp, hDp, cols);
        int exact = (int) Math.floor(fit + 1e-4f); // 16.0 stays 16 despite float division
        int lattice = exact / 4 * 4;
        int cell = lattice > 0 && lattice >= LATTICE_KEEP * fit ? lattice : exact;
        return Math.max(1, cell);
    }

    /** Text size in dp for the matrix font: 10 cells per em. */
    public static int textSizeDp(int cellDp) {
        return cellDp * CELLS_PER_EM;
    }

    public static int timeWidthDp(int cellDp, int cols) {
        return cellDp * cols;
    }

    public static int timeHeightDp(int cellDp) {
        return cellDp * DotMatrixFont.ROWS;
    }

    /** Step of the bottom row's matrix temperature: the largest whole dp whose 7 rows fit the row (4). */
    public static int rowStepDp() {
        return ROW_DP / DotMatrixFont.ROWS;
    }
}
