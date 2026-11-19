#!/usr/bin/env python3
"""Hand-designed glyphs for apps whose own icons don't stipple well, rendered through iconfix's own stipple so they
match the pack. Run on the NAS: tools/nas tools/.venv/bin/python tools/hand_icons.py
Writes iconpack/overrides/<drawable>.png (the stippled glyph; make_iconpack.sh puts it on the glass tile, as for
every icon) and build/hand-icons-preview.png (the overrides on their glass tiles, side by side).

Each design is a shape (alpha) and a tone (brightness 0..1, which sets the dot size), drawn at SS x and reduced to
iconfix.SIZE. Glyphs sit in a box about the size of the pack's median mono glyph (~ 0.5 x SIZE).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import numpy as np  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import iconfix  # noqa: E402

S = iconfix.SIZE
K = 4  # drawing supersample
OUT = os.path.join(ROOT, "iconpack", "overrides")


class Canvas(object):
    """A shape (alpha) and a tone (brightness) drawn together at K x, in SIZE coordinates."""

    def __init__(self):
        self.shape = Image.new("L", (S * K, S * K), 0)
        self.tone = Image.new("L", (S * K, S * K), 0)
        self.ds, self.dt = ImageDraw.Draw(self.shape), ImageDraw.Draw(self.tone)

    @staticmethod
    def _xy(box):
        return [v * K for v in box]

    def rounded(self, box, radius, lum, width=None):
        """A rounded rectangle at brightness lum: filled, or an outline width px wide."""
        kw = dict(radius=radius * K, fill=None if width else 255, outline=255 if width else None)
        if width:
            kw["width"] = int(width * K)
        self.ds.rounded_rectangle(self._xy(box), **kw)
        kw.update(fill=None if width else int(255 * lum), outline=int(255 * lum) if width else None)
        self.dt.rounded_rectangle(self._xy(box), **kw)

    def arc(self, cx, cy, r, start, end, width, lum):
        box = [cx - r, cy - r, cx + r, cy + r]
        self.ds.arc(self._xy(box), start, end, fill=255, width=int(width * K))
        self.dt.arc(self._xy(box), start, end, fill=int(255 * lum), width=int(width * K))

    def dot(self, cx, cy, r, lum):
        box = [cx - r, cy - r, cx + r, cy + r]
        self.ds.ellipse(self._xy(box), fill=255)
        self.dt.ellipse(self._xy(box), fill=int(255 * lum))

    def poly(self, pts, lum):
        p = [(x * K, y * K) for x, y in pts]
        self.ds.polygon(p, fill=255)
        self.dt.polygon(p, fill=int(255 * lum))

    def erase_rounded(self, box, radius):
        self.ds.rounded_rectangle(self._xy(box), radius=radius * K, fill=0)
        self.dt.rounded_rectangle(self._xy(box), radius=radius * K, fill=0)

    def done(self):
        shape = np.asarray(self.shape.resize((S, S), Image.LANCZOS), np.uint8)
        lum = np.asarray(self.tone.resize((S, S), Image.LANCZOS), np.float64) / 255.0
        cov = shape.astype(np.float64) / 255.0
        lum = np.where(cov > 0.02, lum / np.maximum(cov, 1e-6), 0.0).clip(0.0, 1.0)  # un-premultiply
        return shape, iconfix.Tone(lum, cov)


def kde_connect():
    """KDE Connect: a phone (bright frame, dim screen edge) with the link waves rising from a dot inside it."""
    c = Canvas()
    cx, cy = S / 2.0, S / 2.0
    w, h = 132, 212
    frame = [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2]
    c.rounded(frame, 26, 1.0, width=20)
    c.rounded([cx - 22, frame[1] + 30, cx + 22, frame[1] + 40], 5, 0.75)  # earpiece
    base = cy + 58
    c.dot(cx, base, 10, 1.0)
    for r, lum in ((30, 0.9), (54, 0.65)):
        c.arc(cx, base, r, 232, 308, 12, lum)
    return c.done()


def warpinator():
    """Warpinator: send and receive, two arrows passing each other (the upper one bright, the lower one dimmer)."""
    c = Canvas()
    cx, cy = S / 2.0, S / 2.0
    half, shaft, head, gap = 112, 24, 34, 36

    def arrow(y, right, lum):
        tip = cx + half if right else cx - half
        tail = cx - half + 6 if right else cx + half - 6
        neck = tip - head * 1.6 if right else tip + head * 1.6
        c.rounded([min(tail, neck), y - shaft / 2, max(tail, neck), y + shaft / 2], shaft / 2, lum)
        c.poly([(tip, y), (neck, y - head * 1.15), (neck, y + head * 1.15)], lum)

    arrow(cy - gap - 14, True, 1.0)
    arrow(cy + gap + 14, False, 0.62)
    return c.done()


DESIGNS = [
    ("org.kde.kdeconnect_tp/org.kde.kdeconnect.ui.MainActivity", "KDE Connect", kde_connect),
    ("slowscript.warpinator/slowscript.warpinator.MainActivity", "Warpinator", warpinator),
]


def drawable_name(component):
    """AppFilter.drawableName: ic_ + the lower-cased component with non-alphanumerics as _, at most 90 chars."""
    s = "ic_" + "".join(ch if ch.isalnum() and ch.isascii() else "_" for ch in component.lower())
    return s[:90]


def main():
    os.makedirs(OUT, exist_ok=True)
    tiles = []
    for comp, label, draw in DESIGNS:
        shape, tone = draw()
        glyph = iconfix.stipple(shape, tone)
        name = drawable_name(comp)
        path = os.path.join(OUT, name + ".png")
        glyph.save(path)
        tiles.append(iconfix.glass_tile(glyph).convert("RGBA"))
        print("wrote %s  (%s)" % (path, label))
    prev = Image.new("RGBA", (len(tiles) * (S + 24) + 24, S + 48), (40, 40, 40, 255))
    for i, t in enumerate(tiles):
        prev.paste(t, (24 + i * (S + 24), 24), t)
    os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
    out = os.path.join(ROOT, "build", "hand-icons-preview.png")
    prev.save(out)
    print("preview %s" % out)


if __name__ == "__main__":
    main()
