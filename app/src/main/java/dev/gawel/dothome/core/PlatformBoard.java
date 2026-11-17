package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/** Text model and layout arithmetic for the platform board rows (BoardArt and the picker preview draw from it). */
public final class PlatformBoard {
    private PlatformBoard() {}

    public static final int ROWS = 3;

    /** Board layout (dp): pad, the label and header line boxes, the gap between blocks and rows, the digit step. */
    public static final float PAD_DP = 16f, LABEL_LINE_DP = 20f, HEAD_LINE_DP = 16f, GAP_DP = 4f;
    public static final int STEP_DP = 4;
    /** A row is one matrix digit tall: DotMatrixFont.ROWS steps. */
    public static final float ROW_DP = DotMatrixFont.ROWS * STEP_DP;
    /** Top of the first row: pad, label, gap, headers, gap. */
    public static final float ROWS_TOP_DP = PAD_DP + LABEL_LINE_DP + GAP_DP + HEAD_LINE_DP + GAP_DP;

    public static String text(Arrival a) {
        return a.due ? "DUE" : Integer.toString(a.minutes);
    }

    /** Rows that fit in a widget hDp tall above the bottom pad, 0 to ROWS: 1 at 110 dp, 2 at 136, 3 at 168. */
    public static int rowsFit(float hDp) {
        int n = (int) Math.floor((hDp - PAD_DP - ROWS_TOP_DP + GAP_DP) / (ROW_DP + GAP_DP));
        return Math.max(0, Math.min(ROWS, n));
    }

    /**
     * The arrivals ageMin whole minutes after they were fetched: trains already gone are dropped, and one minute or
     * less is due. Returns in itself when ageMin is 0 or less.
     */
    public static List<Arrival> aged(List<Arrival> in, int ageMin) {
        if (ageMin <= 0) return in;
        List<Arrival> out = new ArrayList<Arrival>();
        for (Arrival a : in) {
            int m = a.minutes - ageMin;
            if (m < 0) continue;
            out.add(new Arrival(a.dest, m, a.due || m <= 1));
        }
        return out;
    }
}
