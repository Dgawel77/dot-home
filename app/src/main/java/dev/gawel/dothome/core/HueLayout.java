package dev.gawel.dothome.core;

/**
 * Dot Hue's 1 x 4 strip in dp, shared by the bitmap (HueArt) and the transparent tap cells over it (HueWidget).
 * Inside a PAD_DP pad, a block of two lines centred vertically: the top line is ‹ (prev), the room name (as wide as
 * its text, capped), › (next) and, at the right, ALL OFF; under it a full-width row of STEPS + 1 equal cells: the
 * red off dot, then HueRows.STEPS dots, one per 5% of brightness, like the music widget's seek zones. Rects are
 * {x, y, w, h}.
 */
public final class HueLayout {
    private HueLayout() {}

    public static final float PAD_DP = 16f, LINE_DP = 28f, GAP_DP = 4f, DOTS_H_DP = 28f;
    public static final float ARROW_DP = 32f, OFF_DP = 76f, NAME_DP = 13f, LABEL_DP = 11f, DOT_R_DP = 3f;

    /** The top of the two-line block, centred in a widget hDp tall (0 when it does not fit). */
    public static float top(float hDp) {
        return Math.max(0f, (hDp - (LINE_DP + GAP_DP + DOTS_H_DP)) / 2f);
    }

    /** The widest the name may be: the top line less the arrows, the gap and ALL OFF. */
    public static float maxNameW(float wDp) {
        return Math.max(0f, wDp - 2 * PAD_DP - 2 * ARROW_DP - GAP_DP - OFF_DP);
    }

    public static float[] prevCell(float wDp, float hDp) {
        return new float[] {PAD_DP, top(hDp), ARROW_DP, LINE_DP};
    }

    /** The name cell for a name nameWDp wide (capped at maxNameW). */
    public static float[] nameCell(float wDp, float hDp, float nameWDp) {
        return new float[] {PAD_DP + ARROW_DP, top(hDp), Math.min(Math.max(0f, nameWDp), maxNameW(wDp)), LINE_DP};
    }

    public static float[] nextCell(float wDp, float hDp, float nameWDp) {
        float[] n = nameCell(wDp, hDp, nameWDp);
        return new float[] {n[0] + n[2], top(hDp), ARROW_DP, LINE_DP};
    }

    public static float[] offCell(float wDp, float hDp) {
        return new float[] {wDp - PAD_DP - OFF_DP, top(hDp), OFF_DP, LINE_DP};
    }

    public static float[] dotsRow(float wDp, float hDp) {
        return new float[] {PAD_DP, top(hDp) + LINE_DP + GAP_DP, wDp - 2 * PAD_DP, DOTS_H_DP};
    }

    /** Cell k of the dot row's STEPS + 1 equal cells (0 is the off dot). */
    private static float[] cell(float wDp, float hDp, int k) {
        float[] r = dotsRow(wDp, hDp);
        float cw = r[2] / (HueRows.STEPS + 1);
        return new float[] {r[0] + k * cw, r[1], cw, r[3]};
    }

    /** The red off dot's cell, first in the row. */
    public static float[] offDotCell(float wDp, float hDp) {
        return cell(wDp, hDp, 0);
    }

    public static float[] offDotCentre(float wDp, float hDp) {
        float[] c = offDotCell(wDp, hDp);
        return new float[] {c[0] + c[2] / 2f, c[1] + c[3] / 2f};
    }

    /** Dot cell i (0..STEPS-1, left to right, after the off dot): brightness (i + 1) x 5%. */
    public static float[] dotCell(float wDp, float hDp, int i) {
        return cell(wDp, hDp, i + 1);
    }

    public static float[] dotCentre(float wDp, float hDp, int i) {
        float[] c = dotCell(wDp, hDp, i);
        return new float[] {c[0] + c[2] / 2f, c[1] + c[3] / 2f};
    }
}
