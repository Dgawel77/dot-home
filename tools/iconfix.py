#!/usr/bin/env python3
"""Icon pack post-processor. Run on the NAS: tools/nas tools/iconfix.py

Reads the phone export (build/icons/: <drawable>.png + index.tsv) and writes build/icons-final/
with the same names. The export is never modified, so re-runs are idempotent.

Per index.tsv row (component, drawable, mono|auto[, label]):
  * iconpack/overrides/<drawable>.png        -> kept as is (hand-made wins)
  * kind mono and fill ratio <= 0.55         -> export PNG copied unchanged
  * kind auto, or fill ratio > 0.55          -> replaced, first of:
      1. Simple Icons glyph (explicit PACKAGE_TO_SLUG, then exact normalised label == title),
         rasterised at 432 px with 14% padding in FG; rejected if it fills > 55%
      2. dot-matrix initial of the label, soft dots in FG
Also writes contact.png (every final icon on BG with label and source marker).

First run creates tools/.venv with pinned wheels (skia-python renders the SVGs; the NAS has no
system cairo). Simple Icons (CC0) comes from the pinned npm tarball, cached in tools/cache/.
"""
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VENV = os.path.join(HERE, ".venv")
CACHE = os.path.join(HERE, "cache")
FONT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "Inter-Regular.ttf")

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
PITCH = 20  # dot pitch at 432 px; radius = PITCH * 1.35 / 4.5 (shared scale ratio)
RADIUS = PITCH * 1.35 / 4.5
LETTER_H = 0.60

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
    """Share of pixels with alpha >= 128 (same definition as MonoIcon.fillRatio)."""
    alphas = list(alphas)
    if not alphas:
        return 0.0
    return sum(1 for a in alphas if a >= 128) / len(alphas)


def too_filled(ratio):
    return ratio > MAX_FILL


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


def check_table(pkg_dir):
    for pkg, slug in PACKAGE_TO_SLUG.items():
        if not os.path.isfile(os.path.join(pkg_dir, "icons", slug + ".svg")):
            raise AssertionError("PACKAGE_TO_SLUG: %s -> %s not in simple-icons %s"
                                 % (pkg, slug, SIMPLE_ICONS_VERSION))


# ---------------------------------------------------------------- rasterising

def brand_icon(svg_bytes):
    """Simple Icons SVG -> 432 px RGBA, FG on transparent, 14% padding."""
    import skia
    from PIL import Image
    svg = re.sub(rb"<svg ", b'<svg fill="#000" ', svg_bytes, count=1)
    stream = skia.MemoryStream(svg, len(svg))
    dom = skia.SVGDOM.MakeFromStream(stream)
    if dom is None:
        return None
    inner = round(SIZE * (1 - 2 * PAD))
    off = (SIZE - inner) // 2
    surface = skia.Surface(SIZE, SIZE)
    canvas = surface.getCanvas()
    canvas.translate(off, off)
    dom.setContainerSize(skia.Size(inner, inner))
    dom.render(canvas)
    arr = surface.makeImageSnapshot().toarray()
    return glyph_from_alpha(Image.fromarray(arr[:, :, 3], "L"))


def glyph_from_alpha(alpha):
    from PIL import Image
    img = Image.new("RGBA", alpha.size, FG + (0,))
    img.putalpha(alpha)
    return img


def dot_initial(letter):
    """Dot-matrix initial: soft dots on a PITCH grid, letter ~60% of the tile height."""
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
    ch = unicodedata.normalize("NFKD", letter or "?")
    ch = next((c for c in ch if c.isascii() and c.isalnum()), "?").upper()
    target = SIZE * LETTER_H
    size = 300
    for _ in range(2):
        font = ImageFont.truetype(FONT, size)
        l, t, r, b = font.getbbox(ch)
        size = max(8, round(size * target / max(1, b - t)))
    font = ImageFont.truetype(FONT, size)
    l, t, r, b = font.getbbox(ch)
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).text((SIZE / 2 - (l + r) / 2, SIZE / 2 - (t + b) / 2), ch, fill=255, font=font, stroke_width=7, stroke_fill=255)
    m = np.asarray(mask, dtype=np.float32) / 255.0
    n = SIZE // PITCH
    off = (SIZE - n * PITCH) // 2
    cells = m[off:off + n * PITCH, off:off + n * PITCH].reshape(n, PITCH, n, PITCH).mean(axis=(1, 3))
    # one soft dot: solid core, ~1.5 px feathered rim
    k = int(RADIUS) + 3
    yy, xx = np.mgrid[-k:k + 1, -k:k + 1].astype(np.float32)
    d = np.sqrt(xx * xx + yy * yy)
    stamp = np.clip((RADIUS + 0.75 - d) / 1.5, 0.0, 1.0)
    alpha = np.zeros((SIZE, SIZE), dtype=np.float32)
    for cy in range(n):
        for cx in range(n):
            if cells[cy, cx] > 0.45:
                px = off + cx * PITCH + PITCH // 2
                py = off + cy * PITCH + PITCH // 2
                alpha[py - k:py + k + 1, px - k:px + k + 1] = np.maximum(
                    alpha[py - k:py + k + 1, px - k:px + k + 1], stamp)
    return glyph_from_alpha(Image.fromarray((alpha * 255).round().astype("uint8"), "L"))


# ---------------------------------------------------------------- pipeline

def alphas_of(img):
    return list(img.getchannel("A").tobytes())


def fix(in_dir, out_dir, overrides_dir=None, log=print):
    from PIL import Image
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
        src = os.path.join(in_dir, name + ".png")
        ov = os.path.join(overrides_dir, name + ".png") if overrides_dir else None
        source = None
        if ov and os.path.isfile(ov):
            shutil.copyfile(ov, dst)
            source = "override"
        else:
            img = Image.open(src).convert("RGBA")
            ratio = fill_ratio(alphas_of(img))
            if kind == "mono" and not too_filled(ratio):
                shutil.copyfile(src, dst)
                source = "mono"
            else:
                why = "auto" if kind != "mono" else "fill %.2f" % ratio
                out = None
                slug = resolve_slug(pkg, label, PACKAGE_TO_SLUG, titles, kind)
                if slug:
                    svg = os.path.join(pkg_dir, "icons", slug + ".svg")
                    if os.path.isfile(svg):
                        with open(svg, "rb") as f:
                            cand = brand_icon(f.read())
                        if cand is not None and not too_filled(fill_ratio(alphas_of(cand))) \
                                and max(alphas_of(cand)) > 0:
                            out, source = cand, "brand"
                        else:
                            log("  %s: brand %s rejected (blob/empty)" % (pkg, slug))
                if out is None:
                    out, source = dot_initial(shown[:1]), "initial"
                out.save(dst)
                log("%-45s %-10s -> %s" % (pkg, why, source))
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

def _bootstrap():
    """Create tools/.venv with the pinned wheels (first run) and re-run inside it."""
    py = os.path.join(VENV, "bin", "python")
    if not os.path.isfile(py):
        subprocess.check_call([sys.executable, "-m", "venv", VENV])
        subprocess.check_call([py, "-m", "pip", "-q", "install", "-r", REQUIREMENTS])
    os.execv(py, [py, os.path.abspath(__file__)] + sys.argv[1:])


def main(argv):
    try:
        import PIL  # noqa: F401
        import skia  # noqa: F401
    except ImportError:
        _bootstrap()
    in_dir = argv[1] if len(argv) > 1 else os.path.join(ROOT, "build", "icons")
    out_dir = argv[2] if len(argv) > 2 else os.path.join(ROOT, "build", "icons-final")
    ov = os.path.join(ROOT, "iconpack", "overrides")
    fix(in_dir, out_dir, ov if os.path.isdir(ov) else None)


if __name__ == "__main__":
    main(sys.argv)
