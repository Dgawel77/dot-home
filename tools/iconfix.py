#!/usr/bin/env python3
"""Icon pack post-processor. Run on the NAS: tools/nas tools/iconfix.py

Reads the phone export (build/icons/: <drawable>.png shape masks, optional aligned
<drawable>.tone.png tone layers, index.tsv) and writes build/icons-final/<drawable>.png. The export is
never modified, so re-runs are idempotent.

Every final icon is a greyscale stipple of a shape: FG dots on a square grid of 48 x 48 cells (pitch P = 9 px,
centres at (i + 0.5) P). A cell gets a dot when the shape covers at least 12% of it; the radius is
r = P 0.47 sqrt(t) k with a nearly flat tone t = 0.85 + 0.15 L and an edge factor k = sqrt(min(1, cov
/ 0.85)) that shrinks dots on thin or edge cells (r >= 0.22 P once cov >= 0.3). The dot colour is
constant; tone is dot size.

Per index.tsv row (component, drawable, mono|auto[, label]):
  * iconpack/overrides/<drawable>.png             -> kept as is (hand-made wins)
  * kind mono and 0.01 <= fill ratio <= 0.55     -> stipple of the exported shape, tone per dot from
                                                     <drawable>.tone.png
  * kind auto, or fill ratio outside that range   -> shape replaced, first of:
      1. Simple Icons glyph (explicit PACKAGE_TO_SLUG, then, auto rows only, exact normalised
         label == title); rejected if it fills > 55% (or < 1%) of its box, or is a wordmark
         (aspect > 2.5 or fewer than 4 dot rows)
      2. with --auto-shapes, auto rows only: the row's own mask + tone, if it passes the
         cleanliness gate (shape_problem: fill, edge ink, solidity, dot clusters, wordmark)
      3. Inter Bold capital initial of the label
    brand glyphs and initials are fitted into the median mono glyph box of the run, and stippled at
    the colour icon's mean luminance (0.7 without a tone layer)
  * final source "real": a row that would end up as brand or initial, has a tone layer and is not in
    KEEP_STANDIN gets its real icon instead: the tone luminance (flipped if bright, 2nd-98th percentile
    stretched) stippled through a rounded-square tile mask the size of the median mono glyph box
Usage: iconfix.py [--auto-shapes] [in_dir [out_dir]]
Also writes contact.png (every final icon on BG with label and source marker).

First run creates tools/.venv with pinned wheels (skia-python draws the anti-aliased dots and renders
the SVGs; the NAS has no system cairo). Simple Icons (CC0) comes from the pinned npm tarball, cached
in tools/cache/.
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import unicodedata
import urllib.request

try:  # the venv provides numpy; under the system python main() bootstraps the venv first
    import numpy as np
except ImportError:  # pragma: no cover
    np = None

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VENV = os.path.join(HERE, ".venv")
CACHE = os.path.join(HERE, "cache")
FONT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "Inter-Regular.ttf")  # variable: wght 100-900

REQUIREMENTS = os.path.join(HERE, "iconfix-requirements.txt")  # full freeze incl. transitive deps
SIMPLE_ICONS_VERSION = "16.36.0"  # published 2026-10-25
SIMPLE_ICONS_SHA512 = (  # registry dist.integrity for 16.36.0 (base64)
    "+VNPPkqSOLidFeSgBKUWX4n50WPpDC09hav+BgS26anIF1oQOlGBb8LvwGR+6fYRjo+B6CiKzNBceMPkSVXANQ==")
SIMPLE_ICONS_URL = "https://registry.npmjs.org/simple-icons/-/simple-icons-%s.tgz" % SIMPLE_ICONS_VERSION

# core/DotStyle: GROUND #000000, L100 #DDDDDD, L65 #A6A6A6, L46 #757575, L10 #1A1A1A
FG = (0xDD, 0xDD, 0xDD)  # L100
BG = (0x00, 0x00, 0x00)  # GROUND
FG2 = (0xA6, 0xA6, 0xA6)  # L65 (contact-sheet labels)
SIZE = 432
MAX_FILL = 0.55
MIN_FILL = 0.01
# Brand glyphs and initials are fitted into the typical mono glyph box: the median ink bbox (w, h) of
# this run's mono shapes. With fewer than MIN_MONO of them: brand box 1 - 2 PAD square, initial box
# 1 - 2 PAD wide and LETTER_H tall (fractions of the tile).
MIN_MONO = 8
PAD = 0.24
LETTER_H = 0.50
# Wordmark gate: a glyph whose ink bbox is more than 2.5x longer one way than the other, or whose
# stipple spans fewer than 4 dot rows, does not read as dots.
WORDMARK_ASPECT = 2.5
MIN_ROWS = 4
# --auto-shapes cleanliness gate for an auto row's own fromFullIcon mask (calibrated on the 80 auto
# rows of the 2026-11-16 export): clean glyphs stipple into 1-3 dot clusters, keep clear of the
# border and have some inner structure; masks of gradient or photo backgrounds break into many clusters
# (10-21 seen) or carry background ink into the outer band (6-12% seen), and featureless solids
# (squares, discs: 0.76-1.0 of their own bbox filled) say less than an initial (real glyphs <= 0.74).
MAX_PARTS = 4                    # connected dot clusters (dots in adjacent cells, diagonals included, are connected)
EDGE_BAND = 14                   # px from the tile border (~P)
MAX_EDGE = 0.02                  # share of the mask's ink inside that band
MAX_SOLID = 0.75                 # fill ratio of the mask's own ink bbox

# Stipple (user pick D): square grid, one candidate dot per cell; coverage sets the edge factor.
CELLS = 48                       # cells across the tile
P = SIZE / float(CELLS)          # 9 px pitch; cell (i, j) is centred on ((i + .5) P, (j + .5) P)
COV_MIN = 0.12                   # skip a cell whose mean shape alpha is below this
COV_FULL = 0.85                  # edge factor k = sqrt(min(1, cov / COV_FULL))
COV_FLOOR = 0.3                  # from this coverage on, r >= R_FLOOR * P
T_BASE, T_GAIN = 0.85, 0.15      # t = T_BASE + T_GAIN * L (flat tone, no gradient)
R_FLOOR, R_GAIN = 0.22, 0.47     # r = P * R_GAIN * sqrt(t) * k
L_DEFAULT = 0.7                  # luminance without a tone layer
SS = 4                           # dots are drawn at SS x and area-averaged down (see render_dots)

# Real-icon stand-in replacement: stand-ins (initial / brand) with a tone layer become the colour icon's
# luminance as a stipple, inside a rounded-square tile mask; these packages come out muddy and keep theirs.
KEEP_STANDIN = frozenset([
    "com.activision.callofduty.warzone",
    "com.hungrydroid.whitescreen",
    "com.illinoislottery.illinoislottery",
    "com.innersloth.spacemafia",
    "com.plato.android",
    "com.productmadness.hovmobile",
    "digital.cuisine.ticketplus",
    "feastkwgt.kustom.pack",
    "gift.ace.kwgt",
    "rainbowforkwgt.kustom.pack",
])
TILE_FALLBACK = 0.62             # tile side (fraction of SIZE) with too few mono rows
TILE_RADIUS = 0.24               # corner radius / side
BRIGHT_MEDIAN = 0.55             # flip L when its median inside the tile is above this
STRETCH_LO, STRETCH_HI = 2, 98   # percentiles mapped to 0..1
STRETCH_MIN_SPAN = 1e-3
REAL_COV_MIN = 0.12
REAL_B_MIN = 0.10

# Checked against simple-icons 16.36.0 (test_iconfix asserts every slug resolves). Packages from
# this phone's auto list whose brand is in Simple Icons; the rest get an initial. Wordmark slugs stay
# listed: the wordmark gate decides at run time.
PACKAGE_TO_SLUG = {
    "com.alltrails.alltrails": "alltrails",
    "com.anthropic.claude": "claude",
    "com.google.android.apps.translate": "googletranslate",
    "com.netflix.mediaclient": "netflix",
    "com.paypal.android.p2pmobile": "paypal",
    "com.robinhood.money": "robinhood",
    "com.snapchat.android": "snapchat",
    "com.tencent.mm": "wechat",
    "com.ubercab": "uber",
    "com.zhiliaoapp.musically": "tiktok",
    "de.hafas.android.db": "deutschebahn",
    "com.mcdonalds.app": "mcdonalds",
    "com.okta.android.auth": "okta",
    "com.scee.psxandroid": "playstation",
    "com.tacobell.ordering": "tacobell",
    "cloud.shelly.smartcontrol": "shelly",
    "com.tplink.iot": "tplink",
    "com.netgear.netgearup": "netgear",
    "com.funnycat.virustotal": "virustotal",
    "com.discoverfinancial.mobile": "discover",
    "com.sony.playmemories.mobile": "sony",
    "com.activision.callofduty.warzone": "activision",
}


# ---------------------------------------------------------------- pure helpers

def normalise(s):
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]", "", s.lower())


_TLDS = {"com", "org", "net", "de", "io", "app", "gov", "dev", "ai", "cloud", "me", "co", "pro", "edu"}
_GENERIC = {"android", "app", "mobile", "client", "main", "free", "fdroid", "v2", "apps", "official"}


def label_from_package(pkg):
    parts = [p for p in pkg.split(".") if p]
    keep = [p for p in parts[1:] if p.lower() not in _GENERIC] if parts and parts[0].lower() in _TLDS else \
        [p for p in parts if p.lower() not in _GENERIC]
    word = (keep or parts or ["?"])[-1]
    return word[:1].upper() + word[1:]


def resolve_slug(pkg, label, table, titles, kind="auto"):
    """Table first; then (auto rows only) exact normalised label == normalised Simple Icons title.
    Mono rows rejected for fill ratio never get a coincidental label match."""
    if pkg in table:
        return table[pkg]
    if kind != "auto":
        return None
    return titles.get(normalise(label)) if label else None


def fill_ratio(alphas):
    """Share of pixels with alpha >= 128 (same definition as MonoIcon.fillRatio); 0..255 values."""
    a = np.asarray(alphas)
    return float(np.count_nonzero(a >= 128)) / a.size if a.size else 0.0


def too_filled(ratio):
    return ratio > MAX_FILL


def too_empty(ratio):
    return ratio < MIN_FILL


def parse_index(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            c = line.split("\t")
            rows.append((c[0], c[1], c[2], c[3].strip() if len(c) > 3 else ""))
    return rows


# ---------------------------------------------------------------- Simple Icons

def _sha512_b64(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return base64.b64encode(h.digest()).decode("ascii")


def _download(tgz):
    req = urllib.request.Request(SIMPLE_ICONS_URL, headers={"User-Agent": "dothome-iconfix"})
    part = tgz + ".part"
    with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as out:
        shutil.copyfileobj(r, out)
    got = _sha512_b64(part)
    if got != SIMPLE_ICONS_SHA512:
        os.remove(part)
        raise RuntimeError("simple-icons %s integrity mismatch: expected sha512-%s, got sha512-%s"
                           % (SIMPLE_ICONS_VERSION, SIMPLE_ICONS_SHA512, got))
    os.replace(part, tgz)


def _extract(tgz, dest):
    tmp = dest + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    root = os.path.realpath(tmp)
    with tarfile.open(tgz, "r:gz") as t:
        for m in t.getmembers():
            name = m.name
            if os.path.isabs(name) or ".." in name.split("/") or not (m.isfile() or m.isdir()):
                raise RuntimeError("unsafe tar member: " + name)
            if not os.path.realpath(os.path.join(root, name)).startswith(root + os.sep):
                raise RuntimeError("unsafe tar member: " + name)
            if m.isfile() and (name.startswith("package/icons/") or name == "package/data/simple-icons.json"
                               or name == "package/LICENSE.md"):
                target = os.path.join(root, name)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with t.extractfile(m) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
    shutil.rmtree(dest, ignore_errors=True)
    os.replace(tmp, dest)


def ensure_simple_icons():
    """Download (once, sha512-verified) and safely extract the pinned simple-icons release.

    The cached tarball is re-hashed on every run; a mismatch or tar error deletes the tarball and
    the extracted dir and re-downloads once. Returns the package dir."""
    dest = os.path.join(CACHE, "simple-icons-" + SIMPLE_ICONS_VERSION)
    pkg = os.path.join(dest, "package")
    tgz = dest + ".tgz"
    os.makedirs(CACHE, exist_ok=True)
    for attempt in (1, 2):
        try:
            if os.path.isfile(tgz) and _sha512_b64(tgz) != SIMPLE_ICONS_SHA512:
                raise tarfile.TarError("cached tarball hash mismatch")
            if not os.path.isfile(tgz):
                _download(tgz)
            if not os.path.isfile(os.path.join(pkg, "data", "simple-icons.json")):
                _extract(tgz, dest)
            return pkg
        except (tarfile.TarError, EOFError, OSError) as e:
            if attempt == 2:
                raise
            print("simple-icons cache bad (%s); re-downloading" % e)
            if os.path.isfile(tgz):
                os.remove(tgz)
            shutil.rmtree(dest, ignore_errors=True)
    return pkg


def load_titles(pkg_dir):
    """{normalised title: slug} for the pinned release."""
    with open(os.path.join(pkg_dir, "data", "simple-icons.json"), encoding="utf-8") as f:
        data = json.load(f)
    out = {}
    for e in data:
        slug = e.get("slug") or normalise(e["title"])
        out.setdefault(normalise(e["title"]), slug)
    return out


def check_table(pkg_dir, table=None):
    """Every slug has an icon file in the pinned release (titles can collide; file names cannot)."""
    for pkg, slug in (PACKAGE_TO_SLUG if table is None else table).items():
        if not os.path.isfile(os.path.join(pkg_dir, "icons", slug + ".svg")):
            raise AssertionError("PACKAGE_TO_SLUG: %s -> %s not in simple-icons %s"
                                 % (pkg, slug, SIMPLE_ICONS_VERSION))


# ---------------------------------------------------------------- shapes

def ink_bbox(alpha):
    """(x0, y0, x1, y1), end-exclusive, of the pixels with alpha >= 128; None when there are none."""
    a = np.asarray(alpha) >= 128
    ys, xs = np.flatnonzero(a.any(axis=1)), np.flatnonzero(a.any(axis=0))
    if not len(ys):
        return None
    return int(xs[0]), int(ys[0]), int(xs[-1]) + 1, int(ys[-1]) + 1


def glyph_box(shapes):
    """Typical mono glyph size: (median ink bbox width, median height) of the given shapes, or None
    when fewer than MIN_MONO of them have ink."""
    sizes = [(b[2] - b[0], b[3] - b[1]) for b in map(ink_bbox, shapes) if b]
    if len(sizes) < MIN_MONO:
        return None
    return float(np.median([w for w, _ in sizes])), float(np.median([h for _, h in sizes]))


def brand_box(box=None):
    return box or (SIZE * (1 - 2 * PAD),) * 2


def letter_box(box=None):
    return box or (SIZE * (1 - 2 * PAD), SIZE * LETTER_H)


def _box_region(box):
    """Slices of the (w, h) box centred in the tile."""
    w, h = int(round(box[0])), int(round(box[1]))
    x0, y0 = (SIZE - w) // 2, (SIZE - h) // 2
    return slice(y0, y0 + h), slice(x0, x0 + w)


def _render_svg(dom, tx=0.0, ty=0.0, scale=1.0):
    """Alpha (uint8, SIZE x SIZE) of the SVG laid out in a SIZE x SIZE container, then scaled and moved."""
    import skia
    surface = skia.Surface(SIZE, SIZE)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    canvas.translate(tx, ty)
    canvas.scale(scale, scale)
    dom.setContainerSize(skia.Size(SIZE, SIZE))
    dom.render(canvas)
    return np.ascontiguousarray(surface.makeImageSnapshot().toarray()[:, :, 3])


def brand_icon(svg_bytes, box=None):
    """Simple Icons SVG -> 432 px RGBA, FG on transparent: the glyph's ink bbox fitted into the box
    (default brand_box()) and centred in the tile."""
    import skia
    from PIL import Image
    svg = re.sub(rb"<svg ", b'<svg fill="#000" ', svg_bytes, count=1)
    dom = skia.SVGDOM.MakeFromStream(skia.MemoryStream(svg, len(svg)))
    if dom is None:
        return None
    a = _render_svg(dom)
    bb = ink_bbox(a)
    if bb is not None:
        bw, bh = brand_box(box)
        s = min(bw / (bb[2] - bb[0]), bh / (bb[3] - bb[1]))
        a = _render_svg(dom, SIZE / 2.0 - s * (bb[0] + bb[2]) / 2.0, SIZE / 2.0 - s * (bb[1] + bb[3]) / 2.0, s)
    return glyph_from_alpha(Image.fromarray(a))


def brand_fill(img, box=None):
    """Fill ratio of a brand glyph inside its padded box, not the whole tile (where it could never
    reach 0.55): the centred square of side max(box), as the reviewed M4 check measured it, so a glyph
    fitted tightly into a non-square box is not judged against its own bbox."""
    side = max(brand_box(box))
    ys, xs = _box_region((side, side))
    return fill_ratio(np.asarray(img.getchannel("A"))[ys, xs])


def row_span(dots):
    """Number of grid rows from the top dot to the bottom one (0 without dots)."""
    if not dots:
        return 0
    rows = [int(round(cy / P - 0.5)) for _, cy, _ in dots]
    return max(rows) - min(rows) + 1


def wordmark(alpha):
    """True for an elongated glyph (ink bbox aspect > WORDMARK_ASPECT either way) or one whose stipple
    spans fewer than MIN_ROWS dot rows: neither reads as dots."""
    bb = ink_bbox(alpha)
    if bb is None:
        return True
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    if max(w, h) > WORDMARK_ASPECT * min(w, h):
        return True
    return row_span(stipple_dots(alpha)) < MIN_ROWS


def brand_problem(img, box=None):
    """Why a rendered brand glyph is rejected (blob, empty, wordmark), or None if it is usable."""
    if img is None:
        return "unreadable"
    f = brand_fill(img, box)
    if too_filled(f):
        return "blob %.2f" % f
    if too_empty(f):
        return "empty"
    if wordmark(np.asarray(img.getchannel("A"))):
        return "wordmark"
    return None


def brand_ok(img, box=None):
    return brand_problem(img, box) is None


def dot_parts(dots):
    """Connected clusters of dots (8-neighbours on the grid: at most P * sqrt(2) apart)."""
    n = len(dots)
    if not n:
        return 0
    xy = np.array([(x, y) for x, y, _ in dots])
    near = np.hypot(xy[:, None, 0] - xy[None, :, 0], xy[:, None, 1] - xy[None, :, 1]) <= P * 1.5
    seen = np.zeros(n, bool)
    parts = 0
    for i in range(n):
        if seen[i]:
            continue
        parts += 1
        seen[i] = True
        stack = [i]
        while stack:
            k = stack.pop()
            new = np.flatnonzero(near[k] & ~seen)
            seen[new] = True
            stack.extend(new.tolist())
    return parts


def edge_share(shape):
    """Share of a mask's ink (alpha >= 128) within EDGE_BAND px of the tile border."""
    ink = np.asarray(shape) >= 128
    total = int(ink.sum())
    if not total:
        return 0.0
    inner = ink[EDGE_BAND:-EDGE_BAND, EDGE_BAND:-EDGE_BAND].sum()
    return float(total - inner) / total


def shape_problem(shape):
    """--auto-shapes gate for an auto row's own mask: why it is not a clean glyph (fill, edge ink,
    featureless solid, too many dot clusters, wordmark), or None."""
    f = fill_ratio(shape)
    if too_filled(f) or too_empty(f):
        return "fill %.2f" % f
    e = edge_share(shape)
    if e > MAX_EDGE:
        return "edge %.2f" % e
    x0, y0, x1, y1 = ink_bbox(shape)
    solid = fill_ratio(np.asarray(shape)[y0:y1, x0:x1])
    if solid > MAX_SOLID:
        return "solid %.2f" % solid
    parts = dot_parts(stipple_dots(shape))
    if parts > MAX_PARTS:
        return "%d parts" % parts
    if wordmark(shape):
        return "wordmark"
    return None


def glyph_from_alpha(alpha):
    from PIL import Image
    img = Image.new("RGBA", alpha.size, FG + (0,))
    img.putalpha(alpha)
    return img


def letter_mask(letter, box=None):
    """Inter Bold capital of the label's first letter (or digit; '?' if none), its rendered ink fitted
    into the box (default letter_box()) and centred. uint8 alpha, SIZE x SIZE."""
    from PIL import Image, ImageDraw, ImageFont
    ch = unicodedata.normalize("NFKD", letter or "?")
    ch = next((c for c in ch if c.isascii() and c.isalnum()), "?").upper()
    bw, bh = letter_box(box)

    def ink(size):
        """The font and the ink bbox of ch drawn at size, relative to the drawing origin."""
        font = ImageFont.truetype(FONT, size)
        font.set_variation_by_name("Bold")
        o = SIZE // 2
        m = Image.new("L", (2 * SIZE, 2 * SIZE), 0)
        ImageDraw.Draw(m).text((o, o), ch, fill=255, font=font)
        x0, y0, x1, y1 = ink_bbox(np.asarray(m)) or (o, o, o + 1, o + 1)
        return font, (x0 - o, y0 - o, x1 - o, y1 - o)

    size = 200
    for _ in range(4):
        _, (x0, y0, x1, y1) = ink(size)
        fit = max(8, int(size * min(bw / (x1 - x0), bh / (y1 - y0))))  # round down: stay inside the box
        if fit == size:
            break
        size = fit
    font, (x0, y0, x1, y1) = ink(size)
    mask = Image.new("L", (SIZE, SIZE), 0)
    origin = (int(round(SIZE / 2.0 - (x0 + x1) / 2.0)), int(round(SIZE / 2.0 - (y0 + y1) / 2.0)))
    ImageDraw.Draw(mask).text(origin, ch, fill=255, font=font)
    return np.array(mask, np.uint8)


def _read_rgba(path):
    """RGBA uint8 array at SIZE; other sizes (the old 192 px export) are resampled smoothly."""
    from PIL import Image
    img = Image.open(path).convert("RGBA")
    if img.size != (SIZE, SIZE):
        img = img.resize((SIZE, SIZE), Image.LANCZOS)  # premultiplied by Pillow; never nearest
    return np.asarray(img)


def load_shape(path):
    """Shape mask of an exported <name>.png: its alpha, uint8 SIZE x SIZE."""
    return np.array(_read_rgba(path)[:, :, 3])


def load_tone(path):
    """<name>.tone.png as a Tone (Rec. 709 luminance of its RGB, coverage = alpha), or None."""
    if not os.path.isfile(path):
        return None
    a = _read_rgba(path).astype(np.float64) / 255.0
    return Tone(0.2126 * a[:, :, 0] + 0.7152 * a[:, :, 1] + 0.0722 * a[:, :, 2], a[:, :, 3])


# ---------------------------------------------------------------- stipple

def cell_means(a):
    """Mean of a (SIZE x SIZE) array over each grid cell: a CELLS x CELLS array (row = y, column = x)."""
    a = np.asarray(a, np.float64)
    n = a.shape[0] // CELLS
    return a.reshape(CELLS, n, CELLS, n).mean(axis=(1, 3))


def dot_radius(t, cov):
    """Dot radius for tone t and cell coverage cov: P R_GAIN sqrt(t) k, k = sqrt(min(1, cov / COV_FULL)),
    and at least R_FLOOR P once cov >= COV_FLOOR."""
    t, cov = np.asarray(t, np.float64), np.asarray(cov, np.float64)
    k = np.sqrt(np.minimum(1.0, cov / COV_FULL))
    r = R_GAIN * P * np.sqrt(np.maximum(t, 0.0)) * k
    return np.where(cov >= COV_FLOOR, np.maximum(r, R_FLOOR * P), r)


def tone_t(lum):
    return T_BASE + T_GAIN * lum


class Tone(object):
    """The colour icon: luminance (0..1) and coverage (0..1) at SIZE, aligned pixel for pixel with the shape."""

    def __init__(self, lum, cov):
        self.lum = np.asarray(lum, np.float64)
        self.cov = np.asarray(cov, np.float64)

    def mean(self):
        """Coverage-weighted mean luminance; None when nothing is covered."""
        a = self.cov.sum()
        return float((self.lum * self.cov).sum() / a) if a > 1e-9 else None

    def cells(self, solid, cov, default):
        """Per cell: mean of luminance x tone coverage over the pixels where `solid` (shape alpha > 0.5),
        divided by max(cov, 1e-3) and clamped to 0..1; `default` where the tone layer covers none of them."""
        w = self.cov * solid
        a = cell_means(w)
        level = np.clip(cell_means(self.lum * w) / np.maximum(cov, 1e-3), 0.0, 1.0)
        return np.where(a > 0.01, level, default)


def place_dots(shape, lum=None):
    """[(cx, cy, r)] for a shape: one candidate per grid cell, skipped below COV_MIN coverage. lum: a
    Tone (luminance per cell), one luminance, or None (L_DEFAULT)."""
    alpha = np.asarray(shape, np.float64) / 255.0
    cov = cell_means(alpha)
    if isinstance(lum, Tone):
        mean = lum.mean()
        level = lum.cells(alpha > 0.5, cov, L_DEFAULT if mean is None else mean)
    else:
        level = np.full(cov.shape, L_DEFAULT if lum is None else float(lum))
    js, is_ = np.nonzero(cov >= COV_MIN)
    r = dot_radius(tone_t(level[js, is_]), cov[js, is_])
    return [(float((i + 0.5) * P), float((j + 0.5) * P), float(q)) for j, i, q in zip(js, is_, r)]


stipple_dots = place_dots


def render_dots(dots, size=SIZE):
    """Anti-aliased circles drawn by skia, output at `size`: RGBA with rgb = FG everywhere and alpha =
    dot coverage. Skia's raster AA alone is off by up to ~20% coverage on edge pixels (polygonised
    circles), so it draws at SS x and each output pixel is the exact mean of its SS x SS block (an area
    average: no nearest-neighbour step, edges within ~6% of exact coverage)."""
    import skia
    from PIL import Image
    surface = skia.Surface(size * SS, size * SS)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    canvas.scale(SS, SS)
    paint = skia.Paint(AntiAlias=True, Color=skia.ColorWHITE)
    for cx, cy, r in dots:
        canvas.drawCircle(cx, cy, r, paint)
    a = surface.makeImageSnapshot().toarray()[:, :, 3].reshape(size, SS, size, SS).mean(axis=(1, 3))
    return glyph_from_alpha(Image.fromarray(np.round(a).astype(np.uint8)))


def stipple(shape, lum=None):
    return render_dots(place_dots(shape, lum))


# ---------------------------------------------------------------- pipeline

def keep_mono(kind, ratio):
    return kind == "mono" and not too_filled(ratio) and not too_empty(ratio)


def tile_mask(box=None):
    """Anti-aliased rounded square centred in the tile (float 0..1, SIZE x SIZE): side = the larger side
    of the median mono glyph box (TILE_FALLBACK x SIZE without one), corner radius TILE_RADIUS x side."""
    from PIL import Image, ImageDraw
    side = max(box) if box else TILE_FALLBACK * SIZE
    big = SIZE * SS
    a = big * 0.5 - side * SS / 2.0
    b = big * 0.5 + side * SS / 2.0
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([a, a, b - 1, b - 1], radius=side * SS * TILE_RADIUS, fill=255)
    return np.asarray(m, np.float64).reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3)) / 255.0


def real_levels(tone, mask):
    """Tone luminance with the tile polarity (flipped when bright) and the 2nd-98th percentile stretch
    applied; None when the tile holds no ink. mask: float 0..1, already times the tone coverage."""
    lum = tone.lum
    inside = mask > 0.5
    if not inside.any():
        return None
    if np.median(lum[inside]) > BRIGHT_MEDIAN:
        lum = 1.0 - lum
    lo, hi = np.percentile(lum[inside], [STRETCH_LO, STRETCH_HI])
    if hi - lo < STRETCH_MIN_SPAN:
        return lum
    return np.clip((lum - lo) / (hi - lo), 0.0, 1.0)


def real_dots(tone, box=None):
    """[(cx, cy, r)] halftone of the real icon: one dot per cell, radius from the stretched luminance
    (brightness) and the tile coverage; cells below REAL_COV_MIN coverage or REAL_B_MIN brightness skip."""
    mask = tile_mask(box) * tone.cov
    level = real_levels(tone, mask)
    if level is None:
        return []
    cov, b = cell_means(mask), cell_means(level)
    js, is_ = np.nonzero((cov >= REAL_COV_MIN) & (b >= REAL_B_MIN))
    r = R_GAIN * P * np.sqrt(b[js, is_]) * np.sqrt(np.minimum(1.0, cov[js, is_] / COV_FULL))
    return [(float((i + 0.5) * P), float((j + 0.5) * P), float(q)) for j, i, q in zip(js, is_, r)]


def final_icon(in_dir, name, pkg, kind, label, shown, pkg_dir, titles, log, box=None, auto_shapes=False):
    """Stippled final icon and its source for one index row: mono | brand | auto | initial | real.

    Mono rows keep their shape. Other rows try a brand glyph (blob/empty/wordmark gate), then with
    auto_shapes and an auto row its own mask (shape_problem gate), then an initial. Brand glyphs and
    initials are fitted into box, the run's typical mono glyph size (None: the PAD/LETTER_H fallback)."""
    shape = load_shape(os.path.join(in_dir, name + ".png"))
    tone = load_tone(os.path.join(in_dir, name + ".tone.png"))
    ratio = fill_ratio(shape)
    if keep_mono(kind, ratio):
        return stipple(shape, tone), "mono"
    why = "auto" if kind != "mono" else "fill %.2f" % ratio
    mean = tone.mean() if tone is not None else None
    level = L_DEFAULT if mean is None else mean
    out = source = None
    slug = resolve_slug(pkg, label, PACKAGE_TO_SLUG, titles, kind)
    if slug:
        svg = os.path.join(pkg_dir, "icons", slug + ".svg")
        if os.path.isfile(svg):
            with open(svg, "rb") as f:
                cand = brand_icon(f.read(), box)
            problem = brand_problem(cand, box)
            if problem is None:
                out, source = stipple(np.asarray(cand.getchannel("A")), level), "brand"
            else:
                log("  %s: brand %s rejected (%s)" % (pkg, slug, problem))
    if out is None and auto_shapes and kind == "auto":
        problem = shape_problem(shape)
        if problem is None:
            out, source = stipple(shape, tone), "auto"
        else:
            log("  %s: own shape rejected (%s)" % (pkg, problem))
    if out is None:
        out, source = stipple(letter_mask(shown[:1], box), level), "initial"
    if source in ("brand", "initial") and tone is not None and pkg not in KEEP_STANDIN:
        out, source = render_dots(real_dots(tone, box)), "real"
    log("%-45s %-10s -> %s" % (pkg, why, source))
    return out, source


def fix(in_dir, out_dir, overrides_dir=None, log=print, auto_shapes=False):
    pkg_dir = ensure_simple_icons()
    check_table(pkg_dir)
    titles = load_titles(pkg_dir)
    rows = parse_index(os.path.join(in_dir, "index.tsv"))
    os.makedirs(out_dir, exist_ok=True)
    keep = {r[1] + ".png" for r in rows} | {"index.tsv", "contact.png"}
    for f in os.listdir(out_dir):
        fp = os.path.join(out_dir, f)
        if f not in keep and os.path.isfile(fp):
            os.remove(fp)
    shutil.copyfile(os.path.join(in_dir, "index.tsv"), os.path.join(out_dir, "index.tsv"))

    def override(name):
        ov = os.path.join(overrides_dir, name + ".png") if overrides_dir else None
        return ov if ov and os.path.isfile(ov) else None

    def kept_mono_shapes():
        for _, name, kind, _ in rows:
            if kind == "mono" and not override(name):
                shape = load_shape(os.path.join(in_dir, name + ".png"))
                if keep_mono(kind, fill_ratio(shape)):
                    yield shape

    box = glyph_box(kept_mono_shapes())
    log("glyph box %s" % ("%.0f x %.0f px (median of mono glyphs)" % box if box else "fallback (few mono rows)"))
    sheet = []
    for comp, name, kind, label in rows:
        pkg = comp.split("/")[0]
        shown = label or label_from_package(pkg)
        dst = os.path.join(out_dir, name + ".png")
        ov = override(name)
        if ov:
            shutil.copyfile(ov, dst)
            source = "override"
        else:
            out, source = final_icon(in_dir, name, pkg, kind, label, shown, pkg_dir, titles, log, box, auto_shapes)
            out.save(dst)
        sheet.append((dst, shown, source))
    contact_sheet(sheet, os.path.join(out_dir, "contact.png"))
    counts = {}
    for _, _, s in sheet:
        counts[s] = counts.get(s, 0) + 1
    log("done: %d icons %s" % (len(sheet), counts))
    return counts


MARK = {"mono": "M", "brand": "B", "auto": "A", "initial": "I", "real": "R", "override": "O"}


def contact_sheet(items, path, width=2400, cols=16):
    from PIL import Image, ImageDraw, ImageFont
    cell = width // cols
    icon = cell - 54
    rows = (len(items) + cols - 1) // cols
    sheet = Image.new("RGB", (cell * cols, cell * rows), BG)
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(FONT, 14)
    small = ImageFont.truetype(FONT, 12)
    for i, (p, label, source) in enumerate(items):
        x, y = (i % cols) * cell, (i // cols) * cell
        im = Image.open(p).convert("RGBA").resize((icon, icon), Image.LANCZOS)
        sheet.paste(Image.new("RGB", im.size, FG), (x + (cell - icon) // 2, y + 8), im.getchannel("A"))
        text = label if len(label) <= 17 else label[:16] + "~"
        d.text((x + cell // 2, y + icon + 20), text, fill=FG2, font=font, anchor="mm")
        d.text((x + 6, y + 4), MARK.get(source, "?"), fill=FG2, font=small)
    sheet.save(path)


# ---------------------------------------------------------------- bootstrap + main

def _in_venv():
    return os.path.realpath(sys.prefix) == os.path.realpath(VENV)


def _bootstrap(err=None):
    """Create tools/.venv with the pinned wheels (first run) and re-run inside it, once.

    Inside the venv a failed import means the venv itself is broken: exit rather than re-exec forever.
    A failed venv/pip step removes the half-built venv so the next run starts clean."""
    if _in_venv():
        sys.exit("iconfix: %s inside %s; the venv is incomplete: delete tools/.venv and re-run"
                 % (err or "imports failed", VENV))
    py = os.path.join(VENV, "bin", "python")
    if not os.path.isfile(py):
        ok = False
        try:
            subprocess.check_call([sys.executable, "-m", "venv", VENV])
            subprocess.check_call([py, "-m", "pip", "-q", "install", "-r", REQUIREMENTS])
            ok = True
        except (subprocess.CalledProcessError, OSError) as e:
            sys.exit("iconfix: building tools/.venv failed (%s); removed it, fix the cause and re-run" % e)
        finally:
            if not ok:
                shutil.rmtree(VENV, ignore_errors=True)
    os.execv(py, [py, os.path.abspath(__file__)] + sys.argv[1:])


def parse_args(argv):
    import argparse
    p = argparse.ArgumentParser(prog="iconfix.py", description="Stipple the phone's icon export into the icon pack.")
    p.add_argument("in_dir", nargs="?", default=os.path.join(ROOT, "build", "icons"))
    p.add_argument("out_dir", nargs="?", default=os.path.join(ROOT, "build", "icons-final"))
    p.add_argument("--auto-shapes", action="store_true",
                   help="stipple an auto row's own mask when it passes the cleanliness gate "
                        "(brand -> own shape -> initial); contact sheet marker A")
    return p.parse_args(argv[1:])


def main(argv):
    try:
        import numpy  # noqa: F401
        import PIL  # noqa: F401
        import skia  # noqa: F401
    except ImportError as e:
        _bootstrap(e)
    args = parse_args(argv)
    ov = os.path.join(ROOT, "iconpack", "overrides")
    fix(args.in_dir, args.out_dir, ov if os.path.isdir(ov) else None, auto_shapes=args.auto_shapes)


if __name__ == "__main__":
    main(sys.argv)
