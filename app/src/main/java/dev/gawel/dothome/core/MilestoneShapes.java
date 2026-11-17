package dev.gawel.dothome.core;

/** The point sets the debris gathers into: x,y pairs centred on 0, at most 2 wide, aspect kept. */
public final class MilestoneShapes {
    private MilestoneShapes() {}

    private static final int STAR_ROWS = 9, STAR_GAP = 3, STARS = 4;
    /** The lit width of a star: the grid's two outer columns are never lit (the points fall short of +-4.5). */
    private static final int STAR_LIT_COLS = STAR_ROWS - 2;

    /** The shape for a milestone: four six-pointed stars at 500 (the Chicago flag), else the count's digits. */
    public static float[] points(int milestone) {
        return milestone == 500 ? stars() : digits(String.valueOf(milestone));
    }

    private static float[] digits(String text) {
        DotMatrixFont.Grid g = DotMatrixFont.render(text);
        int n = 0;
        for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) if (g.lit(c, r)) n++;
        int[] cx = new int[n], cy = new int[n];
        int i = 0;
        for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) if (g.lit(c, r)) { cx[i] = c; cy[i++] = r; }
        return normalise(cx, cy, n);
    }

    private static float[] stars() {
        int cap = STARS * STAR_ROWS * STAR_ROWS;
        int[] cx = new int[cap], cy = new int[cap];
        int n = 0;
        float half = STAR_ROWS / 2f; // circumradius, cells
        float c30 = (float) Math.cos(Math.PI / 6), s30 = 0.5f;
        // up triangle: tip (0,-R), base corners (+-R cos30, R/2); down triangle: the mirror in y
        for (int s = 0; s < STARS; s++) {
            int col0 = s * (STAR_LIT_COLS + STAR_GAP);
            for (int r = 0; r < STAR_ROWS; r++) {
                for (int c = 0; c < STAR_ROWS; c++) {
                    float x = c - (STAR_ROWS - 1) / 2f, y = r - (STAR_ROWS - 1) / 2f;
                    if (inTriangle(x, y, half, c30, s30) || inTriangle(x, -y, half, c30, s30)) {
                        cx[n] = col0 + c - 1; // column 0 is never lit: lit columns are 1..7
                        cy[n++] = r;
                    }
                }
            }
        }
        return normalise(cx, cy, n);
    }

    /** Whether (x, y) is inside the triangle with tip (0,-R) and base y = R/2 spanning +-R cos30. */
    private static boolean inTriangle(float x, float y, float r, float c30, float s30) {
        if (y > r * s30) return false;
        float t = (y + r) / (r * (1f + s30)); // 0 at the tip, 1 at the base
        return Math.abs(x) <= t * r * c30;
    }

    /** Centres the cells on their bounding box and scales so the width is 2 (a lone column keeps unit pitch). */
    private static float[] normalise(int[] cx, int[] cy, int n) {
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            x0 = Math.min(x0, cx[i]); x1 = Math.max(x1, cx[i]);
            y0 = Math.min(y0, cy[i]); y1 = Math.max(y1, cy[i]);
        }
        float scale = 2f / Math.max(1, x1 - x0);
        float[] out = new float[2 * n];
        for (int i = 0; i < n; i++) {
            out[2 * i] = (cx[i] - (x0 + x1) / 2f) * scale;
            out[2 * i + 1] = (cy[i] - (y0 + y1) / 2f) * scale;
        }
        return out;
    }
}
