package dev.gawel.dothome.core;

/** 5x7 dot-matrix digits and a 1x7 colon, for rendering the clock as dots. */
public final class DotMatrixFont {
    public static final int ROWS = 7;

    private static final String[] DIGITS = {
        "01110,10001,10011,10101,11001,10001,01110", // 0
        "00100,01100,00100,00100,00100,00100,01110", // 1
        "01110,10001,00001,00010,00100,01000,11111", // 2
        "11111,00010,00100,00010,00001,10001,01110", // 3
        "00010,00110,01010,10010,11111,00010,00010", // 4
        "11111,10000,11110,00001,00001,10001,01110", // 5
        "00110,01000,10000,11110,10001,10001,01110", // 6
        "11111,00001,00010,00100,01000,01000,01000", // 7
        "01110,10001,10001,01110,10001,10001,01110", // 8
        "01110,10001,10001,01111,00001,00010,01100", // 9
    };
    private static final String COLON = "0,0,1,0,1,0,0";

    private DotMatrixFont() {}

    /** Row-major cells (width x 7) of a glyph; true means lit. */
    public static boolean[] glyph(char c) {
        String[] rows;
        if (c == ':') {
            rows = COLON.split(",");
        } else if (c >= '0' && c <= '9') {
            rows = DIGITS[c - '0'].split(",");
        } else {
            throw new IllegalArgumentException("no glyph for '" + c + "'");
        }
        int w = rows[0].length();
        boolean[] out = new boolean[w * ROWS];
        for (int r = 0; r < ROWS; r++) {
            for (int x = 0; x < w; x++) {
                out[r * w + x] = rows[r].charAt(x) == '1';
            }
        }
        return out;
    }

    public static int width(char c) {
        if (c == ':') return 1;
        if (c >= '0' && c <= '9') return 5;
        throw new IllegalArgumentException("no glyph for '" + c + "'");
    }

    /** Lays glyphs out left to right with one absent column between them. */
    public static Grid render(String text) {
        int cols = 0;
        for (int i = 0; i < text.length(); i++) {
            cols += width(text.charAt(i)) + (i > 0 ? 1 : 0);
        }
        boolean[] lit = new boolean[cols * ROWS];
        boolean[] present = new boolean[cols * ROWS];
        int x0 = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int w = width(ch);
            boolean[] g = glyph(ch);
            for (int r = 0; r < ROWS; r++) {
                for (int x = 0; x < w; x++) {
                    present[r * cols + x0 + x] = true;
                    lit[r * cols + x0 + x] = g[r * w + x];
                }
            }
            x0 += w + 1;
        }
        return new Grid(cols, ROWS, present, lit);
    }

    /** Rendered text: every cell is either absent (gap) or present and lit/unlit. */
    public static final class Grid {
        public final int cols, rows;
        private final boolean[] present, lit;

        Grid(int cols, int rows, boolean[] present, boolean[] lit) {
            this.cols = cols;
            this.rows = rows;
            this.present = present;
            this.lit = lit;
        }

        public boolean present(int c, int r) {
            return present[r * cols + c];
        }

        public boolean lit(int c, int r) {
            return lit[r * cols + c];
        }
    }
}
