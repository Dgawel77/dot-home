"""Tests for the dot-matrix font pair. Run on the NAS: tools/nas python3 tools/test_matrix_font.py
(re-runs itself inside tools/.fontvenv, like the builder). No JVM needed."""
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_matrix_font as bmf  # noqa: E402

if __name__ == "__main__":
    bmf.reexec_in_venv(__file__, sys.argv)

from fontTools.ttLib import TTFont  # noqa: E402

LIT = os.path.join(bmf.OUT_DIR, bmf.LIT_FILE)
GRID = os.path.join(bmf.OUT_DIR, bmf.GRID_FILE)
DIGITS = "0123456789"
SYMBOLS = "°−-.%"
R = bmf.DOT // 2


def source():
    with open(bmf.SOURCE, encoding="utf-8") as f:
        return f.read()


def contour_centres(glyph, glyf):
    """(cx, cy, w, h) of each contour's bounding box."""
    if glyph.numberOfContours == 0:
        return []
    coords, ends, _ = glyph.getCoordinates(glyf)
    out, start = [], 0
    for end in ends:
        pts = coords[start:end + 1]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        out.append(((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, max(xs) - min(xs), max(ys) - min(ys)))
        start = end + 1
    return out


def cells(rows, lit_only):
    return {bmf.cell_centre(c, r) for r, row in enumerate(rows) for c, on in enumerate(row) if on or not lit_only}


class ParseTest(unittest.TestCase):
    def test_reads_every_glyph(self):
        rows = bmf.read_glyphs()
        for _, const, _ in bmf.GLYPHS:
            self.assertIn(const, rows)
            self.assertEqual(7, len(rows[const]))
        self.assertEqual(5, len(rows["DIGITS[8]"][0]))
        self.assertEqual(1, len(rows["COLON"][0]))
        self.assertEqual(17, sum(sum(r) for r in rows["DIGITS[8]"]))
        self.assertEqual([False, False, True, False, True, False, False], [r[0] for r in rows["COLON"]])

    def test_missing_glyph_fails_loudly(self):
        java = source()
        broken = java.replace('private static final String PERCENT =', 'private static final String PERCENT_X =')
        with self.assertRaisesRegex(bmf.GlyphSourceError, "PERCENT"):
            bmf.parse_glyphs(broken)

    def test_missing_digit_fails_loudly(self):
        java = source()
        line = next(ln for ln in java.split("\n") if ln.strip().endswith("// 7"))
        with self.assertRaisesRegex(bmf.GlyphSourceError, "10 entries"):
            bmf.parse_glyphs(java.replace(line + "\n", ""))

    def test_malformed_glyph_fails_loudly(self):
        java = source().replace('"0,0,1,0,1,0,0"', '"0,0,1,0,1,0"')
        with self.assertRaisesRegex(bmf.GlyphSourceError, "COLON"):
            bmf.parse_glyphs(java)

    def test_wrong_width_fails_loudly(self):
        java = source().replace("01110,01010,01110,00000,00000,00000,00000", "0111,0101,0111,0000,0000,0000,0000")
        with self.assertRaisesRegex(bmf.GlyphSourceError, "DEGREE"):
            bmf.parse_glyphs(java)


class FontTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lit, cls.grid = TTFont(LIT), TTFont(GRID)
        cls.rows = bmf.read_glyphs()

    def fonts(self):
        return (self.lit, self.grid)

    def test_every_glyph_exists(self):
        for f in self.fonts():
            cmap = f.getBestCmap()
            for ch in DIGITS + ":" + SYMBOLS + " ":
                self.assertIn(ord(ch), cmap, "U+%04X" % ord(ch))
                self.assertIn(cmap[ord(ch)], f.getGlyphOrder())
            self.assertEqual(cmap[0x2212], cmap[0x2D], "hyphen draws the true minus")

    def test_advances_fixed_per_class(self):
        expected = dict([(d, 600) for d in DIGITS] + [(s, 600) for s in SYMBOLS] + [(":", 200), (" ", 300)])
        for f in self.fonts():
            cmap, hmtx = f.getBestCmap(), f["hmtx"]
            for ch, adv in expected.items():
                self.assertEqual(adv, hmtx[cmap[ord(ch)]][0], "advance of %r" % ch)
        # "hh:mm:ss" is 39 cells wide once the trailing gap is dropped (ClockFit.SECONDS_COLS)
        self.assertEqual(3900, 6 * 600 + 2 * 200 - 100)

    def test_circle_count_equals_lit_cells(self):
        glyf = self.lit["glyf"]
        for name, const, _ in bmf.GLYPHS:
            rows = self.rows[const]
            g = glyf[name]
            self.assertEqual(sum(sum(r) for r in rows), max(0, g.numberOfContours), name)
            got = {(round(cx), round(cy)) for cx, cy, _, _ in contour_centres(g, glyf)}
            self.assertEqual(cells(rows, lit_only=True), got, name)

    def test_grid_font_has_every_cell(self):
        glyf = self.grid["glyf"]
        for name, const, _ in bmf.GLYPHS:
            rows = self.rows[const]
            g = glyf[name]
            self.assertEqual(7 * len(rows[0]), g.numberOfContours, name)
            got = {(round(cx), round(cy)) for cx, cy, _, _ in contour_centres(g, glyf)}
            self.assertEqual(cells(rows, lit_only=False), got, name)

    def test_dots_are_quadratic_circles_of_diameter_60(self):
        for f in self.fonts():
            glyf = f["glyf"]
            g = glyf["eight"]
            _, ends, flags = g.getCoordinates(glyf)
            start = 0
            for end in ends:
                fl = [flags[i] & 1 for i in range(start, end + 1)]
                self.assertEqual(16, len(fl), "8 on-curve + 8 off-curve points")
                self.assertEqual(8, fl.count(0), "quadratic control points")
                start = end + 1
            for _, _, w, h in contour_centres(g, glyf):
                self.assertEqual(2 * R, w)
                self.assertEqual(2 * R, h)
        self.assertEqual(0, self.lit["glyf"]["space"].numberOfContours)

    def test_metrics_lay_out_without_padding(self):
        for f in self.fonts():
            self.assertEqual(1000, f["head"].unitsPerEm)
            hhea, os2 = f["hhea"], f["OS/2"]
            self.assertEqual((700, 0, 0), (hhea.ascent, hhea.descent, hhea.lineGap))
            self.assertEqual((700, 0, 0), (os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap))
            self.assertEqual((700, 0), (os2.usWinAscent, os2.usWinDescent))
            self.assertTrue(os2.fsSelection & (1 << 7), "USE_TYPO_METRICS")
            self.assertLessEqual(f["head"].yMax, 700)
            self.assertGreaterEqual(f["head"].yMin, 0)

    def test_names(self):
        self.assertEqual("DotMatrix", self.lit["name"].getDebugName(1))
        self.assertEqual("DotMatrix Grid", self.grid["name"].getDebugName(1))
        self.assertEqual("Regular", self.grid["name"].getDebugName(2))
        self.assertEqual("DotMatrixGrid-Regular", self.grid["name"].getDebugName(6))

    def test_committed_fonts_match_the_java_source(self):
        """Rebuilding is byte-identical, so a glyph change in DotMatrixFont.java without a rebuild fails here."""
        with tempfile.TemporaryDirectory() as tmp:
            for path in bmf.build(tmp):
                with open(path, "rb") as a, open(os.path.join(bmf.OUT_DIR, os.path.basename(path)), "rb") as b:
                    self.assertTrue(a.read() == b.read(),
                                    "%s is stale: run tools/build_matrix_font.py" % os.path.basename(path))


if __name__ == "__main__":
    unittest.main()
