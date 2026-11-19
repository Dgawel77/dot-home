package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class HueLayoutTest {
    private HueLayoutTest() {}

    public static void testRowsFit() {
        T.eq(4, HueLayout.rowsFit(180, 4), "4x2 widget fits 4 rows");
        T.eq(2, HueLayout.rowsFit(180, 2), "only the picked rows");
        T.eq(2, HueLayout.rowsFit(110, 4), "minimum height fits 2");
        T.eq(0, HueLayout.rowsFit(40, 4), "too short");
        T.eq(4, HueLayout.rowsFit(400, 9), "never more than MAX");
    }

    public static void testCellsTile() {
        float[] r = HueLayout.row(300, 180, 4, 1);
        float[] name = HueLayout.nameCell(r);
        T.near(r[0], name[0], 1e-4, "name starts the row");
        T.near(r[2], name[2] + HueRows.DOTS * HueLayout.DOT_CELL_DP, 1e-4, "name + 5 dot cells = row width");
        float x = name[0] + name[2];
        for (int d = 0; d < HueRows.DOTS; d++) {
            float[] c = HueLayout.dotCell(r, d);
            T.near(x, c[0], 1e-4, "dot cell " + d + " follows the last");
            T.near(HueLayout.DOT_CELL_DP, c[2], 1e-4, "dot cell width");
            T.near(r[1], c[1], 1e-4, "dot cell top");
            T.near(r[3], c[3], 1e-4, "dot cell height");
            x += c[2];
        }
        T.near(r[0] + r[2], x, 1e-4, "the dots end the row");
        T.near(HueLayout.PAD_DP, r[0], 1e-4, "row inside the pad");
        T.near(300 - 2 * HueLayout.PAD_DP, r[2], 1e-4, "row spans the padded width");
    }

    public static void testRowsEvenlySpaced() {
        float[] a = HueLayout.row(300, 180, 4, 0), b = HueLayout.row(300, 180, 4, 3);
        T.near(HueLayout.PAD_DP, a[1], 1e-4, "first row at the pad");
        T.near(180 - HueLayout.PAD_DP, b[1] + b[3], 1e-4, "last row ends at the pad");
        T.near((180 - 2 * HueLayout.PAD_DP) / 4, a[3], 1e-4, "equal heights");
    }

    public static void testCentredWhenFewer() {
        float[] four = HueLayout.row(300, 180, 4, 0);
        float[] a = HueLayout.row(300, 180, 2, 0), b = HueLayout.row(300, 180, 2, 1);
        T.near(four[3], a[3], 1e-4, "same row height as a full widget");
        T.near(a[1] + a[3], b[1], 1e-4, "rows touch");
        float top = a[1], bottom = 180 - (b[1] + b[3]);
        T.near(top, bottom, 1e-4, "centred vertically");
    }

    public static void testDotsInsideCells() {
        float[] r = HueLayout.row(300, 180, 3, 2);
        for (int d = 0; d < HueRows.DOTS; d++) {
            float[] c = HueLayout.dotCell(r, d), p = HueLayout.dotCentre(r, d);
            T.near(c[0] + c[2] / 2, p[0], 1e-4, "centred x " + d);
            T.near(c[1] + c[3] / 2, p[1], 1e-4, "centred y " + d);
            T.check(HueLayout.DOT_R_DP * 2 < c[2], "dot fits its cell");
        }
    }
}
