package dev.gawel.dothome.core;

/**
 * Dot Hue's geometry in dp, shared by the bitmap (HueArt) and the transparent tap cells laid over it (HueWidget):
 * inside a PAD_DP pad, rows of equal height (the height the most rows that fit would have; fewer rows are centred
 * vertically), each a name cell on the left and five DOT_CELL_DP dot cells on the right. Rects are {x, y, w, h}.
 */
public final class HueLayout {
    private HueLayout() {}

    public static final float PAD_DP = 16f, DOT_CELL_DP = 32f, MIN_ROW_DP = 32f, NAME_DP = 13f, DOT_R_DP = 3.5f;

    /** How many of `rows` rows fit a widget hDp tall (at most HueRows.MAX, at least 0). */
    public static int rowsFit(float hDp, int rows) {
        int fit = (int) Math.floor((hDp - 2 * PAD_DP) / MIN_ROW_DP);
        return Math.max(0, Math.min(Math.min(rows, HueRows.MAX), fit));
    }

    /** Row i of `rows` (rows already limited by rowsFit). */
    public static float[] row(float wDp, float hDp, int rows, int i) {
        float avail = hDp - 2 * PAD_DP;
        int full = Math.max(1, rowsFit(hDp, HueRows.MAX));
        float rh = avail / full;
        float top = PAD_DP + (avail - rows * rh) / 2f;
        return new float[] {PAD_DP, top + i * rh, wDp - 2 * PAD_DP, rh};
    }

    public static float[] nameCell(float[] row) {
        return new float[] {row[0], row[1], row[2] - HueRows.DOTS * DOT_CELL_DP, row[3]};
    }

    /** Dot cell d (0..4, left to right). */
    public static float[] dotCell(float[] row, int d) {
        return new float[] {row[0] + row[2] - (HueRows.DOTS - d) * DOT_CELL_DP, row[1], DOT_CELL_DP, row[3]};
    }

    public static float[] dotCentre(float[] row, int d) {
        float[] c = dotCell(row, d);
        return new float[] {c[0] + c[2] / 2f, c[1] + c[3] / 2f};
    }
}
