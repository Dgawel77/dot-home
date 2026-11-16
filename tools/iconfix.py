#!/usr/bin/env python3
"""Icon pack post-processor. Run on the NAS: tools/nas tools/iconfix.py

Reads the phone export (build/icons/: <drawable>.png shape masks, optional aligned
<drawable>.tone.png tone layers, index.tsv) and writes build/icons-final/<drawable>.png. The export is
never modified, so re-runs are idempotent.

Every final icon is a greyscale stipple of a shape: FG dots on a hex grid (pitch P = 432/30), placed
where the shape covers half of a +-P/4 box, sized by tone. The dot colour is constant; tone is dot size.

Per index.tsv row (component, drawable, mono|auto[, label]):
  * iconpack/overrides/<drawable>.png             -> kept as is (hand-made wins)
  * kind mono and 0.01 <= fill ratio <= 0.55     -> stipple of the exported shape, tone per dot from
                                                     <drawable>.tone.png
  * kind auto, or fill ratio outside that range   -> shape replaced, first of:
      1. Simple Icons glyph (explicit PACKAGE_TO_SLUG, then, auto rows only, exact normalised
         label == title), rasterised at 432 px with 14% padding; rejected if it fills > 55% (or
         < 1%) of the padded box
      2. Inter Bold capital initial of the label
    stippled at the colour icon's mean luminance (0.7 without a tone layer)
Also writes contact.png (every final icon on BG with label and source marker).

First run creates tools/.venv with pinned wheels (skia-python draws the anti-aliased dots and renders
the SVGs; the NAS has no system cairo). Simple Icons (CC0) comes from the pinned npm tarball, cached
in tools/cache/.
"""
import base64
import hashlib
import json
import math
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

# core/DotStyle: FG 0xFFDDDDDD, BG 0xFF0B0B0B
FG = (0xDD, 0xDD, 0xDD)
BG = (0x0B, 0x0B, 0x0B)
FG2 = (0x8E, 0x8E, 0x8E)
SIZE = 432
PAD = 0.14
MAX_FILL = 0.55
MIN_FILL = 0.01
LETTER_H = 0.60

# Stipple (Task 6): hex grid, dot where the shape covers >= half of a +-P/4 box; tone -> dot radius.
P = SIZE / 30.0                  # 14.4 px pitch: nearest-neighbour distance on the hex grid
BOX = P / 4                      # coverage / tone sampling box half-width
COVER = 0.5
T_BASE, T_GAIN = 0.38, 0.57      # t = (T_BASE + T_GAIN * L) * g
G_END = 0.88                     # light gradient g: 1.0 top-left -> G_END bottom-right
R_FLOOR, R_GAIN = 0.28, 0.47     # r = max(R_FLOOR * P, R_GAIN * P * sqrt(t))
L_DEFAULT = 0.7                  # luminance without a tone layer
SS = 4                           # dots are drawn at SS x and area-averaged down (see render_dots)

# Checked against simple-icons 16.36.0 (test_iconfix asserts every slug resolves). Packages from
# this phone's auto list whose brand is in Simple Icons; the rest get a dot initial.
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

def _padded_box():
    """(offset, size) of the 14% padded box that brand glyphs are drawn into."""
    inner = round(SIZE * (1 - 2 * PAD))
    return (SIZE - inner) // 2, inner


def brand_icon(svg_bytes):
    """Simple Icons SVG -> 432 px RGBA, FG on transparent, inside the padded box."""
    import skia
    from PIL import Image
    svg = re.sub(rb"<svg ", b'<svg fill="#000" ', svg_bytes, count=1)
    stream = skia.MemoryStream(svg, len(svg))
    dom = skia.SVGDOM.MakeFromStream(stream)
    if dom is None:
        return None
    off, inner = _padded_box()
    surface = skia.Surface(SIZE, SIZE)
    canvas = surface.getCanvas()
    canvas.translate(off, off)
    dom.setContainerSize(skia.Size(inner, inner))
    dom.render(canvas)
    arr = surface.makeImageSnapshot().toarray()
    return glyph_from_alpha(Image.fromarray(np.ascontiguousarray(arr[:, :, 3])))


def brand_fill(img):
    """Fill ratio of a brand glyph inside its padded box (the tile-wide ratio can never exceed ~0.52)."""
    off, inner = _padded_box()
    return fill_ratio(np.asarray(img.getchannel("A"))[off:off + inner, off:off + inner])


def brand_ok(img):
    if img is None:
        return False
    f = brand_fill(img)
    return not too_filled(f) and not too_empty(f)


def glyph_from_alpha(alpha):
    from PIL import Image
    img = Image.new("RGBA", alpha.size, FG + (0,))
    img.putalpha(alpha)
    return img


def letter_mask(letter):
    """Inter Bold capital of the label's first letter (or digit; '?' if none): LETTER_H of the tile
    tall, centred. uint8 alpha, SIZE x SIZE."""
    from PIL import Image, ImageDraw, ImageFont
    ch = unicodedata.normalize("NFKD", letter or "?")
    ch = next((c for c in ch if c.isascii() and c.isalnum()), "?").upper()

    def bold(size):
        font = ImageFont.truetype(FONT, size)
        font.set_variation_by_name("Bold")
        return font

    target = SIZE * LETTER_H
    size = 300
    for _ in range(2):
        l, t, r, b = bold(size).getbbox(ch)
        size = max(8, round(size * target / max(1, b - t)))
    font = bold(size)
    l, t, r, b = font.getbbox(ch)
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).text((SIZE / 2 - (l + r) / 2, SIZE / 2 - (t + b) / 2), ch, fill=255, font=font)
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

def hex_grid(size=SIZE, pitch=P):
    """Dot centres [(x, y)]: rows pitch*sqrt(3)/2 apart and centred vertically; even rows at P/2 + i P,
    odd rows shifted by P/2, so all six neighbours are P away. Every centre is >= P/2 from the edge."""
    row = pitch * math.sqrt(3) / 2
    rows = int((size - pitch) / row + 1e-9) + 1
    y0 = (size - (rows - 1) * row) / 2.0
    pts = []
    for j in range(rows):
        x0 = pitch / 2.0 * (2 if j % 2 else 1)
        n = int((size - pitch / 2.0 - x0) / pitch + 1e-9) + 1
        pts.extend((x0 + i * pitch, y0 + j * row) for i in range(n))
    return pts


def gradient(cx, cy):
    """Light gradient g: 1.0 at the top-left corner, G_END at the bottom-right, linear in x + y."""
    return 1.0 - (1.0 - G_END) * (cx + cy) / (2.0 * SIZE)


def tone_t(lum, cx, cy):
    return (T_BASE + T_GAIN * lum) * gradient(cx, cy)


def dot_radius(t):
    return np.maximum(R_FLOOR * P, R_GAIN * P * np.sqrt(np.maximum(t, 0.0)))


def _integral(a):
    ii = np.zeros((a.shape[0] + 1, a.shape[1] + 1), np.float64)
    ii[1:, 1:] = np.asarray(a, np.float64).cumsum(0).cumsum(1)
    return ii


def _box_sums(ii, xs, ys):
    """Sum over the pixels whose centres lie in the +-BOX square around each (x, y), and its pixel count."""
    h, w = ii.shape[0] - 1, ii.shape[1] - 1
    x0 = np.clip(np.ceil(xs - BOX - 0.5), 0, w).astype(int)
    x1 = np.clip(np.floor(xs + BOX - 0.5) + 1, 0, w).astype(int)
    y0 = np.clip(np.ceil(ys - BOX - 0.5), 0, h).astype(int)
    y1 = np.clip(np.floor(ys + BOX - 0.5) + 1, 0, h).astype(int)
    return ii[y1, x1] - ii[y0, x1] - ii[y1, x0] + ii[y0, x0], np.maximum(1, (x1 - x0) * (y1 - y0))


class Tone(object):
    """The colour icon: luminance (0..1) and coverage (0..1) at SIZE, aligned pixel for pixel with the shape."""

    def __init__(self, lum, cov):
        lum, cov = np.asarray(lum, np.float64), np.asarray(cov, np.float64)
        self._lum_cov = _integral(lum * cov)
        self._cov = _integral(cov)

    def mean(self):
        """Coverage-weighted mean luminance; None when nothing is covered."""
        a = self._cov[-1, -1]
        return float(self._lum_cov[-1, -1] / a) if a > 1e-9 else None

    def at(self, xs, ys, default):
        """Coverage-weighted luminance in the +-P/4 box of each centre; `default` where it is uncovered."""
        la, n = _box_sums(self._lum_cov, xs, ys)
        a, _ = _box_sums(self._cov, xs, ys)
        ok = a > 0.01 * n
        return np.where(ok, la / np.where(ok, a, 1.0), default)


def place_dots(shape, t_of):
    """[(cx, cy, r)]: a dot on every hex grid point whose +-P/4 box has mean shape alpha >= 0.5; its
    radius comes from the tone t_of(xs, ys) (scalar or one per centre)."""
    pts = np.array(hex_grid(), np.float64)
    xs, ys = pts[:, 0], pts[:, 1]
    cover, n = _box_sums(_integral(np.asarray(shape, np.float64) / 255.0), xs, ys)
    on = cover / n >= COVER
    xs, ys = xs[on], ys[on]
    if not len(xs):
        return []
    r = dot_radius(np.broadcast_to(np.asarray(t_of(xs, ys), np.float64), xs.shape))
    return [(float(x), float(y), float(q)) for x, y, q in zip(xs, ys, r)]


def stipple_dots(shape, lum=None):
    """Dots for a shape. lum: a Tone (luminance sampled per dot), one luminance, or None (L_DEFAULT)."""
    if isinstance(lum, Tone):
        mean = lum.mean()
        fallback = L_DEFAULT if mean is None else mean
        level = lambda xs, ys: lum.at(xs, ys, fallback)  # noqa: E731
    else:
        const = L_DEFAULT if lum is None else float(lum)
        level = lambda xs, ys: const  # noqa: E731
    return place_dots(shape, lambda xs, ys: tone_t(level(xs, ys), xs, ys))


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
    return render_dots(stipple_dots(shape, lum))


# ---------------------------------------------------------------- pipeline

def final_icon(in_dir, name, pkg, kind, label, shown, pkg_dir, titles, log):
    """Stippled final icon and its source (mono | brand | initial) for one index row."""
    shape = load_shape(os.path.join(in_dir, name + ".png"))
    tone = load_tone(os.path.join(in_dir, name + ".tone.png"))
    ratio = fill_ratio(shape)
    if kind == "mono" and not too_filled(ratio) and not too_empty(ratio):
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
                cand = brand_icon(f.read())
            if brand_ok(cand):
                out, source = stipple(np.asarray(cand.getchannel("A")), level), "brand"
            else:
                log("  %s: brand %s rejected (blob/empty)" % (pkg, slug))
    if out is None:
        out, source = stipple(letter_mask(shown[:1]), level), "initial"
    log("%-45s %-10s -> %s" % (pkg, why, source))
    return out, source


def fix(in_dir, out_dir, overrides_dir=None, log=print):
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
    sheet = []
    for comp, name, kind, label in rows:
        pkg = comp.split("/")[0]
        shown = label or label_from_package(pkg)
        dst = os.path.join(out_dir, name + ".png")
        ov = os.path.join(overrides_dir, name + ".png") if overrides_dir else None
        if ov and os.path.isfile(ov):
            shutil.copyfile(ov, dst)
            source = "override"
        else:
            out, source = final_icon(in_dir, name, pkg, kind, label, shown, pkg_dir, titles, log)
            out.save(dst)
        sheet.append((dst, shown, source))
    contact_sheet(sheet, os.path.join(out_dir, "contact.png"))
    counts = {}
    for _, _, s in sheet:
        counts[s] = counts.get(s, 0) + 1
    log("done: %d icons %s" % (len(sheet), counts))
    return counts


MARK = {"mono": "M", "brand": "B", "initial": "I", "override": "O"}


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
        d.text((x + cell // 2, y + icon + 20), text, fill=FG, font=font, anchor="mm")
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


def main(argv):
    try:
        import numpy  # noqa: F401
        import PIL  # noqa: F401
        import skia  # noqa: F401
    except ImportError as e:
        _bootstrap(e)
    in_dir = argv[1] if len(argv) > 1 else os.path.join(ROOT, "build", "icons")
    out_dir = argv[2] if len(argv) > 2 else os.path.join(ROOT, "build", "icons-final")
    ov = os.path.join(ROOT, "iconpack", "overrides")
    fix(in_dir, out_dir, ov if os.path.isdir(ov) else None)


if __name__ == "__main__":
    main(sys.argv)
