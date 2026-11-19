package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** The 1 x 4 strip: a top line (‹ name › ... ALL OFF) over a full-width row of 20 brightness dots. */
public final class HueLayoutTest {
    private HueLayoutTest() {}

    private static final float W = 311, H = 95;

    public static void testBlockCentred() {
        float top = HueLayout.top(H);
        float bottom = top + HueLayout.LINE_DP + HueLayout.GAP_DP + HueLayout.DOTS_H_DP;
        T.near(top, H - bottom, 1e-4, "the two lines are centred vertically");
        T.check(top >= 0, "fits one row");
    }

    public static void testTopLine() {
        float nameW = 90;
        float[] prev = HueLayout.prevCell(W, H), name = HueLayout.nameCell(W, H, nameW);
        float[] next = HueLayout.nextCell(W, H, nameW), off = HueLayout.offCell(W, H);
        T.near(HueLayout.PAD_DP, prev[0], 1e-4, "‹ at the left pad");
        T.near(prev[0] + prev[2], name[0], 1e-4, "the name follows ‹");
        T.near(nameW, name[2], 1e-4, "the name as wide as its text");
        T.near(name[0] + name[2], next[0], 1e-4, "› right after the name");
        T.near(W - HueLayout.PAD_DP, off[0] + off[2], 1e-4, "ALL OFF at the right pad");
        T.check(next[0] + next[2] <= off[0] - HueLayout.GAP_DP + 1e-4, "› clear of ALL OFF");
        for (float[] c : new float[][] {prev, name, next, off}) {
            T.near(HueLayout.top(H), c[1], 1e-4, "top line y");
            T.near(HueLayout.LINE_DP, c[3], 1e-4, "top line height");
        }
    }

    public static void testLongNameCapped() {
        float[] name = HueLayout.nameCell(W, H, 1000), next = HueLayout.nextCell(W, H, 1000);
        float[] off = HueLayout.offCell(W, H);
        T.near(HueLayout.maxNameW(W), name[2], 1e-4, "capped");
        T.near(off[0] - HueLayout.GAP_DP, next[0] + next[2], 1e-4, "› still clear of ALL OFF");
    }

    public static void testDotsTileRow() {
        float[] row = HueLayout.dotsRow(W, H);
        T.near(HueLayout.PAD_DP, row[0], 1e-4, "row starts at the pad");
        T.near(W - 2 * HueLayout.PAD_DP, row[2], 1e-4, "full padded width");
        T.near(HueLayout.top(H) + HueLayout.LINE_DP + HueLayout.GAP_DP, row[1], 1e-4, "below the top line");
        float[] off = HueLayout.offDotCell(W, H), offP = HueLayout.offDotCentre(W, H);
        T.near(row[0], off[0], 1e-4, "the red off dot starts the row");
        T.near(row[2] / (HueRows.STEPS + 1), off[2], 1e-4, "one cell of 21");
        T.near(off[0] + off[2] / 2, offP[0], 1e-4, "off dot centred");
        float x = off[0] + off[2];
        for (int i = 0; i < HueRows.STEPS; i++) {
            float[] c = HueLayout.dotCell(W, H, i), p = HueLayout.dotCentre(W, H, i);
            T.near(x, c[0], 1e-4, "cell " + i + " follows the last");
            T.near(c[0] + c[2] / 2, p[0], 1e-4, "centre x " + i);
            T.near(c[1] + c[3] / 2, p[1], 1e-4, "centre y " + i);
            T.check(HueLayout.DOT_R_DP * 2 < c[2], "dot fits its cell " + i);
            x += c[2];
        }
        T.near(row[0] + row[2], x, 1e-4, "the dots end the row");
    }
}
