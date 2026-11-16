package dev.gawel.dothome.test;

import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotMatrixFont.Grid;

public final class DotMatrixFontTest {
    private static int count(boolean[] g) {
        int n = 0;
        for (boolean b : g) if (b) n++;
        return n;
    }

    public static void testOneGlyphRows() {
        String[] rows = {"00100", "01100", "00100", "00100", "00100", "00100", "01110"};
        boolean[] g = DotMatrixFont.glyph('1');
        T.eq(35, g.length, "glyph size");
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 5; c++) {
                T.eq(rows[r].charAt(c) == '1', g[r * 5 + c], "cell " + c + "," + r);
            }
        }
    }

    public static void testLitCounts() {
        T.eq(17, count(DotMatrixFont.glyph('8')), "lit in 8");
        T.eq(10, count(DotMatrixFont.glyph('1')), "lit in 1");
    }

    public static void testRenderWidth() {
        T.eq(29, DotMatrixFont.render("12:41").cols, "12:41 cols");
        T.eq(23, DotMatrixFont.render("9:41").cols, "9:41 cols");
        T.eq(7, DotMatrixFont.render("9:41").rows, "rows");
    }

    public static void testColonCells() {
        Grid g = DotMatrixFont.render(":");
        T.eq(5, g.cols, "colon cols (fixed advance)");
        for (int r = 0; r < 7; r++) {
            T.check(g.present(0, r) && g.present(4, r), "present row " + r);
            T.eq(r == 2 || r == 4, g.lit(2, r), "lit row " + r);
        }
        Grid two = DotMatrixFont.render("1:");
        for (int r = 0; r < 7; r++) T.check(!two.present(5, r), "gap column absent row " + r);
    }

    public static void testUnknownCharThrows() {
        try {
            DotMatrixFont.glyph('a');
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
