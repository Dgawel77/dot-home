#!/usr/bin/env python3
"""Build the dot-matrix font pair from core/DotMatrixFont.java. Run on the NAS:
    tools/nas python3 tools/build_matrix_font.py

Writes app/src/main/res/font/dot_matrix.ttf (lit cells only) and dot_matrix_grid.ttf (every cell of each
glyph, for the unlit L10 layer underneath). Both are committed, so build.sh needs no Python; re-run this after
changing a glyph in DotMatrixFont.java (tools/test_matrix_font.py fails while the committed fonts are stale).

The glyph bitmaps are parsed straight from the Java source with strict regexes, so DotMatrixFont stays the one
source of truth; any missing or malformed glyph is an error.

Metrics: unitsPerEm 1000 and one cell = 100 units, so text size = cell x 10. A dot is a circle of diameter 60
(the lattice's regular weight, 0.6) centred in its cell, drawn as eight quadratic arcs. Rows run top-down from
the ascender (700); descent and line gap are 0, so a TextView with includeFontPadding=false is exactly 7 cells
tall. Advance = (cols + 1) x 100: digits and symbols 600, colon 200; space 300.

First run creates tools/.fontvenv with the pinned fontTools (tools/font-requirements.txt) and re-runs inside it.
"""
import math
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VENV = os.path.join(HERE, ".fontvenv")
REQUIREMENTS = os.path.join(HERE, "font-requirements.txt")
SOURCE = os.path.join(ROOT, "app", "src", "main", "java", "dev", "gawel", "dothome", "core", "DotMatrixFont.java")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "font")
LIT_FILE, GRID_FILE = "dot_matrix.ttf", "dot_matrix_grid.ttf"

UPM = 1000
CELL = 100
WEIGHT = 0.6  # Lattice.Weight.REGULAR: dot diameter / step
DOT = round(CELL * WEIGHT)  # 60
ROWS = 7
ASCENT = ROWS * CELL  # 700
SPACE_ADVANCE = 300
NOTDEF_ADVANCE = 600
# Fixed so a rebuild is byte-identical (head.created/modified): 2026-11-16 00:00 UTC in seconds since 1904.
TIMESTAMP = 3877632000

# glyph name, Java constant (DIGITS[i] for digits), code points
GLYPHS = [("zero", "DIGITS[0]", [0x30]), ("one", "DIGITS[1]", [0x31]), ("two", "DIGITS[2]", [0x32]),
          ("three", "DIGITS[3]", [0x33]), ("four", "DIGITS[4]", [0x34]), ("five", "DIGITS[5]", [0x35]),
          ("six", "DIGITS[6]", [0x36]), ("seven", "DIGITS[7]", [0x37]), ("eight", "DIGITS[8]", [0x38]),
          ("nine", "DIGITS[9]", [0x39]), ("colon", "COLON", [0x3A]), ("degree", "DEGREE", [0xB0]),
          ("minus", "MINUS", [0x2212, 0x2D]), ("period", "DOT", [0x2E]), ("percent", "PERCENT", [0x25])]
NAMED = ("COLON", "DEGREE", "MINUS", "DOT", "PERCENT")

FAMILIES = {
    LIT_FILE: ("DotMatrix", "DotMatrix-Regular", False),
    GRID_FILE: ("DotMatrix Grid", "DotMatrixGrid-Regular", True),
}

_SPEC = r"[01]+(?:,[01]+){%d}" % (ROWS - 1)
_DIGITS_RE = re.compile(r"private static final String\[\] DIGITS = \{\n(.*?)\n\s*\};", re.S)
_DIGIT_LINE_RE = re.compile(r'\s*"(%s)", // (\d)' % _SPEC)
_NAMED_RE = r'private static final String %s = "(%s)";'
_GLYPH_COLS_RE = re.compile(r"public static final int GLYPH_COLS = (\d+);")


class GlyphSourceError(ValueError):
    pass


def _rows(name, spec, cols):
    rows = spec.split(",")
    if len(rows) != ROWS or any(len(r) != cols for r in rows):
        raise GlyphSourceError("%s must be %d rows of %d cells, got %r" % (name, ROWS, cols, spec))
    return [[ch == "1" for ch in r] for r in rows]


def parse_glyphs(java):
    """Glyph rows by Java constant name ("DIGITS[0]".."DIGITS[9]", "COLON", ...), each a list of 7 rows of bools.

    Strict: every expected glyph must be present exactly once in the expected shape, or GlyphSourceError."""
    m = _GLYPH_COLS_RE.search(java)
    if not m:
        raise GlyphSourceError("GLYPH_COLS not found in DotMatrixFont.java")
    cols = int(m.group(1))
    out = {}
    blocks = _DIGITS_RE.findall(java)
    if len(blocks) != 1:
        raise GlyphSourceError("expected one DIGITS array in DotMatrixFont.java, found %d" % len(blocks))
    lines = [ln for ln in blocks[0].split("\n") if ln.strip()]
    if len(lines) != 10:
        raise GlyphSourceError("DIGITS must have 10 entries, found %d" % len(lines))
    for i, ln in enumerate(lines):
        dm = _DIGIT_LINE_RE.fullmatch(ln)
        if not dm or int(dm.group(2)) != i:
            raise GlyphSourceError("DIGITS[%d] is malformed or out of order: %r" % (i, ln.strip()))
        out["DIGITS[%d]" % i] = _rows("DIGITS[%d]" % i, dm.group(1), cols)
    for name in NAMED:
        found = re.findall(_NAMED_RE % (name, _SPEC), java)
        if len(found) != 1:
            raise GlyphSourceError("glyph %s: expected one definition in DotMatrixFont.java, found %d"
                                   % (name, len(found)))
        out[name] = _rows(name, found[0], 1 if name == "COLON" else cols)
    return out


def read_glyphs(path=SOURCE):
    with open(path, encoding="utf-8") as f:
        return parse_glyphs(f.read())


def advance(cols):
    return (cols + 1) * CELL


def cell_centre(col, row):
    """Centre of cell (col, row), rows counted top-down from the ascender."""
    return col * CELL + CELL // 2, ASCENT - row * CELL - CELL // 2


def draw_circle(pen, cx, cy, r):
    """Clockwise (TrueType outer contour) circle: on-curve points every 45 degrees, one off-curve control each
    between them at r / cos(22.5 deg), so each arc is a single quadratic."""
    k = r / math.cos(math.pi / 8)

    def pt(rad, deg):
        a = math.radians(deg)
        return round(cx + rad * math.cos(a)), round(cy + rad * math.sin(a))

    pen.moveTo(pt(r, 90))
    for i in range(8):
        a = 90 - 45 * i
        pen.qCurveTo(pt(k, a - 22.5), pt(r, a - 45))
    pen.closePath()


def build_font(glyph_rows, family, ps_name, grid):
    """A TTFont with one circle per lit cell (or, with grid, per cell) of every glyph."""
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.ttGlyphPen import TTGlyphPen

    names = [".notdef", "space"] + [g[0] for g in GLYPHS]
    fb = FontBuilder(UPM, isTTF=True)
    fb.font.recalcTimestamp = False
    fb.setupGlyphOrder(names)
    cmap = {0x20: "space"}
    for name, _, cps in GLYPHS:
        for cp in cps:
            cmap[cp] = name
    fb.setupCharacterMap(cmap)

    glyf, adv = {}, {".notdef": NOTDEF_ADVANCE, "space": SPACE_ADVANCE}
    for name in (".notdef", "space"):
        glyf[name] = TTGlyphPen(None).glyph()
    for name, const, _ in GLYPHS:
        rows = glyph_rows[const]
        pen = TTGlyphPen(None)
        for r, row in enumerate(rows):
            for c, lit in enumerate(row):
                if lit or grid:
                    draw_circle(pen, *cell_centre(c, r), DOT / 2)
        glyf[name] = pen.glyph()
        adv[name] = advance(len(rows[0]))
    fb.setupGlyf(glyf)
    table = fb.font["glyf"]
    fb.setupHorizontalMetrics({n: (adv[n], getattr(table[n], "xMin", 0)) for n in names})
    fb.setupHorizontalHeader(ascent=ASCENT, descent=0, lineGap=0)
    fb.setupNameTable({
        "copyright": "Generated by Dot Home tools/build_matrix_font.py from core/DotMatrixFont.java",
        "familyName": family,
        "styleName": "Regular",
        "uniqueFontIdentifier": "DotHome;%s;1.000" % ps_name,
        "fullName": family + " Regular",
        "version": "Version 1.000",
        "psName": ps_name,
    }, mac=False)
    fb.setupOS2(
        version=4, achVendID="NONE", usWeightClass=400, usWidthClass=5,
        fsSelection=(1 << 6) | (1 << 7),  # REGULAR, USE_TYPO_METRICS
        sTypoAscender=ASCENT, sTypoDescender=0, sTypoLineGap=0,
        usWinAscent=ASCENT, usWinDescent=0,
        sCapHeight=ASCENT - (CELL - DOT) // 2, sxHeight=0,
        fsType=0, ulCodePageRange1=1)  # installable; Latin 1
    os2 = fb.font["OS/2"]
    os2.recalcAvgCharWidth(fb.font)
    os2.recalcUnicodeRanges(fb.font)
    fb.setupPost(isFixedPitch=0)
    head = fb.font["head"]
    head.created = head.modified = TIMESTAMP
    head.fontRevision = 1.0
    return fb.font


def build(out_dir=OUT_DIR, source=SOURCE):
    """Writes both fonts into out_dir; returns their paths."""
    rows = read_glyphs(source)
    os.makedirs(out_dir, exist_ok=True)
    paths = []
    for fname, (family, ps_name, grid) in FAMILIES.items():
        path = os.path.join(out_dir, fname)
        build_font(rows, family, ps_name, grid).save(path)
        paths.append(path)
    return paths


# ---------------------------------------------------------------- venv bootstrap

def _in_venv():
    return os.path.realpath(sys.prefix) == os.path.realpath(VENV)


def reexec_in_venv(script, argv):
    """Re-run script inside tools/.fontvenv (built on first use from the pinned requirements), so the output never
    depends on whatever fontTools the system python has. A no-op inside the venv. A failed venv/pip step removes
    the half-built venv so the next run starts clean."""
    if _in_venv():
        return
    py = os.path.join(VENV, "bin", "python")
    if not os.path.isfile(py):
        ok = False
        try:
            subprocess.check_call([sys.executable, "-m", "venv", VENV])
            subprocess.check_call([py, "-m", "pip", "-q", "install", "-r", REQUIREMENTS])
            ok = True
        except (subprocess.CalledProcessError, OSError) as e:
            sys.exit("build_matrix_font: building tools/.fontvenv failed (%s); removed it, fix the cause and re-run"
                     % e)
        finally:
            if not ok:
                shutil.rmtree(VENV, ignore_errors=True)
    os.execv(py, [py, os.path.abspath(script)] + argv[1:])


def main(argv):
    reexec_in_venv(__file__, argv)
    try:
        import fontTools  # noqa: F401
    except ImportError as e:
        sys.exit("build_matrix_font: %s inside %s; the venv is incomplete: delete tools/.fontvenv and re-run"
                 % (e, VENV))
    out = argv[1] if len(argv) > 1 else OUT_DIR
    try:
        for p in build(out):
            print("wrote", os.path.relpath(p, ROOT))
    except GlyphSourceError as e:
        sys.exit("build_matrix_font: %s" % e)


if __name__ == "__main__":
    main(sys.argv)
