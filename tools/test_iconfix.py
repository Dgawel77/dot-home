import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iconfix as ix  # noqa: E402

S = ix.SIZE
SOLID_SVG = (b'<svg role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">'
             b'<path d="M0 0h24v24H0z"/></svg>')


# ---------------------------------------------------------------- fixtures

def write_png(path, alpha, grey=None, size=S):
    """RGBA PNG: alpha (0..1, array or scalar); rgb = grey level (0..1, array or scalar) or FG."""
    a = np.broadcast_to(np.asarray(alpha, np.float64), (size, size))
    a = (np.clip(a, 0, 1) * 255).round().astype(np.uint8)
    if grey is None:
        rgb = np.empty(a.shape + (3,), np.uint8)
        rgb[:] = ix.FG
    else:
        g = (np.clip(np.broadcast_to(grey, a.shape), 0, 1) * 255).round().astype(np.uint8)
        rgb = np.dstack([g, g, g])
    Image.fromarray(np.dstack([rgb, a]), "RGBA").save(path)


def ring(size=S, r0=0.22, r1=0.36):
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    d = np.hypot(xx - size / 2.0, yy - size / 2.0) / size
    return ((d >= r0) & (d <= r1)).astype(np.float32)


def blobs(size=S):
    """Irregular shape: a few overlapping discs and a thin bar."""
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    m = np.zeros((size, size), bool)
    for cx, cy, r in ((0.3, 0.35, 0.12), (0.62, 0.4, 0.08), (0.5, 0.7, 0.15)):
        m |= np.hypot(xx - cx * size, yy - cy * size) <= r * size
    m[int(0.15 * size):int(0.17 * size), int(0.1 * size):int(0.9 * size)] = True
    return m.astype(np.float32)


def u8(alpha):
    return (np.clip(alpha, 0, 1) * 255).round().astype(np.uint8)


def ink(img):
    return np.asarray(img.getchannel("A"), np.float32) / 255.0


def measured_radii(img, dots):
    """Radius of each rendered dot from its ink area inside a P/2 disk around its centre.

    Neighbours sit P apart and are at most 0.47 P (+ ~1 px anti-aliasing) wide, so the disk holds
    exactly one dot."""
    a = ink(img)
    out = []
    for cx, cy, _ in dots:
        x0, x1 = max(0, int(cx - ix.P / 2) - 1), min(a.shape[1], int(cx + ix.P / 2) + 2)
        y0, y1 = max(0, int(cy - ix.P / 2) - 1), min(a.shape[0], int(cy + ix.P / 2) + 2)
        yy, xx = np.mgrid[y0:y1, x0:x1] + 0.5
        disk = np.hypot(xx - cx, yy - cy) <= ix.P / 2
        out.append(math.sqrt(a[y0:y1, x0:x1][disk].sum() / math.pi))
    return out


def exact_coverage(cx, cy, r, x0, y0, n, k=32):
    """Exact-ish (k x k supersampled) coverage of a disc over the n x n pixels from (x0, y0)."""
    yy, xx = (np.mgrid[0:n * k, 0:n * k] + 0.5) / k
    inside = np.hypot(xx + x0 - cx, yy + y0 - cy) <= r
    return inside.reshape(n, k, n, k).mean(axis=(1, 3))


def dilate(mask, r):
    out = np.zeros_like(mask)
    h, w = mask.shape
    k = int(math.ceil(r))
    for dy in range(-k, k + 1):
        for dx in range(-k, k + 1):
            if dx * dx + dy * dy > r * r:
                continue
            out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] |= \
                mask[max(0, -dy):h + min(0, -dy), max(0, -dx):w + min(0, -dx)]
    return out


# ---------------------------------------------------------------- pure helpers (Task 4)

class Normalise(unittest.TestCase):
    def test_title(self):
        self.assertEqual(ix.normalise("McDonald's"), "mcdonalds")
        self.assertEqual(ix.normalise("  TP-Link "), "tplink")
        self.assertEqual(ix.normalise("Café Ünï"), "cafeuni")

    def test_label_from_package(self):
        self.assertEqual(ix.label_from_package("com.tollway"), "Tollway")
        self.assertEqual(ix.label_from_package("com.ninjatrader.pulse"), "Pulse")
        self.assertEqual(ix.label_from_package("com.example.android"), "Example")


class Resolve(unittest.TestCase):
    TITLES = {"netflix": "netflix", "claude": "claude", "foo": "foo"}

    def test_table_beats_label(self):
        # the label says "Foo" but the table pins the package to claude
        slug = ix.resolve_slug("com.anthropic.claude", "Foo", {"com.anthropic.claude": "claude"}, self.TITLES)
        self.assertEqual(slug, "claude")

    def test_label_match_when_not_in_table(self):
        self.assertEqual(ix.resolve_slug("x.y", "Netflix", {}, self.TITLES), "netflix")

    def test_mono_rejected_for_fill_never_label_matches(self):
        self.assertIsNone(ix.resolve_slug("x.y", "Netflix", {}, self.TITLES, "mono"))
        self.assertEqual(ix.resolve_slug("com.a", "Zzz", {"com.a": "claude"}, self.TITLES, "mono"), "claude")

    def test_no_match(self):
        self.assertIsNone(ix.resolve_slug("x.y", "Zzz", {}, self.TITLES))


class Slugs(unittest.TestCase):
    def test_every_table_slug_exists_in_pinned_release(self):
        # check_table looks at the icon files, so colliding titles cannot hide a missing slug
        ix.check_table(ix.ensure_simple_icons())

    def test_check_table_rejects_a_missing_slug(self):
        with self.assertRaises(AssertionError):
            ix.check_table(ix.ensure_simple_icons(), {"com.example": "no-such-slug-zzz"})


class Cache(unittest.TestCase):
    def test_corrupt_cache_is_repaired(self):
        pkg = ix.ensure_simple_icons()
        tgz = os.path.dirname(pkg) + ".tgz"
        with open(tgz, "ab") as f:
            f.write(b"x")
        pkg = ix.ensure_simple_icons()
        self.assertEqual(ix._sha512_b64(tgz), ix.SIMPLE_ICONS_SHA512)
        self.assertTrue(os.path.isfile(os.path.join(pkg, "data", "simple-icons.json")))


class Fill(unittest.TestCase):
    def test_ratio(self):
        self.assertAlmostEqual(ix.fill_ratio([0, 127, 128, 255]), 0.5)
        self.assertAlmostEqual(ix.fill_ratio(np.array([[0, 128], [255, 3]], np.uint8)), 0.5)

    def test_rejection(self):
        self.assertTrue(ix.too_filled(0.56))
        self.assertFalse(ix.too_filled(0.55))

    def test_blank_is_rejected(self):
        self.assertTrue(ix.too_empty(0.0))
        self.assertTrue(ix.too_empty(0.009))
        self.assertFalse(ix.too_empty(0.01))

    def test_solid_blob_is_rejected_and_falls_back(self):
        solid = [255] * 100
        ring_ = [255 if i % 2 else 0 for i in range(100)]
        self.assertTrue(ix.too_filled(ix.fill_ratio(solid)))
        self.assertFalse(ix.too_filled(ix.fill_ratio(ring_)))


# ---------------------------------------------------------------- stipple geometry

class Grid(unittest.TestCase):
    def test_pitch(self):
        self.assertAlmostEqual(ix.P, 14.4)

    def test_hex_grid(self):
        pts = ix.hex_grid()
        ys = sorted({round(y, 6) for _, y in pts})
        steps = np.diff(ys)
        self.assertTrue(np.allclose(steps, ix.P * math.sqrt(3) / 2), steps)
        rows = [sorted(x for x, y in pts if abs(y - ry) < 1e-6) for ry in ys]
        for r in rows:
            self.assertTrue(np.allclose(np.diff(r), ix.P))
        self.assertAlmostEqual(abs(rows[1][0] - rows[0][0]), ix.P / 2)  # odd rows offset by P/2
        for x, y in pts:  # whole dots stay inside the tile
            self.assertTrue(ix.P / 2 - 1e-9 <= x <= S - ix.P / 2 + 1e-9)
            self.assertTrue(ix.P / 2 - 1e-9 <= y <= S - ix.P / 2 + 1e-9)
        a = np.array(pts)
        mid = a[np.argmin(np.hypot(a[:, 0] - S / 2, a[:, 1] - S / 2))]
        d = np.hypot(a[:, 0] - mid[0], a[:, 1] - mid[1])
        self.assertAlmostEqual(np.sort(d)[1], ix.P)  # nearest neighbours are P apart
        self.assertEqual(int(np.sum(np.abs(d - ix.P) < 1e-6)), 6)  # hex: six of them

    def test_gradient(self):
        self.assertAlmostEqual(ix.gradient(0, 0), 1.0)
        self.assertAlmostEqual(ix.gradient(S, S), 0.88)
        self.assertAlmostEqual(ix.gradient(S, 0), 0.94)

    def test_tone_mapping(self):
        self.assertAlmostEqual(ix.tone_t(1.0, 0, 0), 0.95)
        self.assertAlmostEqual(ix.tone_t(0.0, 0, 0), 0.38)
        self.assertAlmostEqual(ix.tone_t(0.7, S, S), (0.38 + 0.57 * 0.7) * 0.88)

    def test_radius(self):
        self.assertAlmostEqual(ix.dot_radius(1.0), 0.47 * ix.P)
        self.assertAlmostEqual(ix.dot_radius(0.0), 0.28 * ix.P)
        self.assertAlmostEqual(ix.dot_radius(0.3), 0.28 * ix.P)  # floor: 0.47 sqrt(.3) < 0.28
        self.assertAlmostEqual(ix.dot_radius(0.5), 0.47 * ix.P * math.sqrt(0.5))
        rs = [ix.dot_radius(t) for t in np.linspace(0, 1, 101)]
        self.assertTrue(all(b >= a for a, b in zip(rs, rs[1:])))

    def test_dot_needs_half_the_box_covered(self):
        flat = lambda xs, ys: 1.0  # noqa: E731
        self.assertEqual(len(ix.place_dots(np.full((S, S), 128, np.uint8), flat)), len(ix.hex_grid()))
        self.assertEqual(len(ix.place_dots(np.full((S, S), 127, np.uint8), flat)), 0)


class Stipple(unittest.TestCase):
    def gradient_tone(self, axis=1):
        lum = np.linspace(0, 1, S, dtype=np.float32)
        lum = np.broadcast_to(lum[None, :] if axis == 1 else lum[:, None], (S, S))
        return ix.Tone(lum, np.ones((S, S), np.float32))

    def test_output_is_single_colour_fg(self):
        img = ix.stipple(u8(ring()), self.gradient_tone())
        self.assertEqual((img.mode, img.size), ("RGBA", (S, S)))
        a = np.asarray(img)
        on = a[:, :, 3] > 0
        self.assertTrue(on.any())
        self.assertEqual({tuple(p) for p in a[on][:, :3]}, {ix.FG})

    def test_dots_are_antialiased_circles(self):
        dots = ix.place_dots(np.full((S, S), 255, np.uint8), lambda xs, ys: 0.5)
        img = ix.render_dots(dots)
        a = np.asarray(img.getchannel("A"))
        partial = (a > 0) & (a < 255)
        self.assertGreater(int(partial.sum()), 10 * len(dots))  # soft rims, not jagged steps
        for meas, (_, _, r) in zip(measured_radii(img, dots), dots):
            self.assertAlmostEqual(meas, r, delta=0.05)

    def test_dot_edges_match_exact_coverage(self):
        # off-grid centres and the whole radius range; every pixel within 20/255 of exact coverage
        for cx, cy, r in ((40.0, 40.0, 0.28 * ix.P), (100.3, 61.77, 5.2), (200.5, 300.25, 0.47 * ix.P)):
            a = ink(ix.render_dots([(cx, cy, r)]))
            x0, y0 = int(cx) - 10, int(cy) - 10
            err = np.abs(a[y0:y0 + 20, x0:x0 + 20] - exact_coverage(cx, cy, r, x0, y0, 20))
            self.assertLess(err.max(), 20 / 255.0, "r=%.2f" % r)
            self.assertAlmostEqual(a.sum(), math.pi * r * r, delta=0.02 * math.pi * r * r)

    def test_dot_radius_grows_with_tone(self):
        full = np.full((S, S), 255, np.uint8)
        dots = ix.stipple_dots(full, self.gradient_tone())
        meas = measured_radii(ix.render_dots(dots), dots)
        by_row = {}
        for (cx, cy, r), m in zip(dots, meas):
            self.assertAlmostEqual(m, r, delta=0.05)  # drawn size == formula size
            by_row.setdefault(round(cy, 3), []).append((cx, r, m))
        for row in by_row.values():  # luminance rises left to right in every row
            row.sort()
            rs = [r for _, r, _ in row]
            ms = [m for _, _, m in row]
            self.assertTrue(all(b >= a for a, b in zip(rs, rs[1:])), rs)
            self.assertTrue(all(b > a for a, b in zip(ms, ms[1:])), ms)
            self.assertGreater(ms[-1] - ms[0], 1.5)  # ~2 px from black to white across a row

    def test_no_ink_outside_shape_dilated_by_pitch(self):
        for name, shape in (("ring", ring()), ("blobs", blobs()), ("letter", ix.letter_mask("R") / 255.0)):
            img = ix.stipple(u8(shape), 1.0)
            inked = ink(img) > 0
            self.assertTrue(inked.any(), name)
            outside = inked & ~dilate(shape > 0, ix.P)
            self.assertFalse(outside.any(), "%s: %d px outside" % (name, int(outside.sum())))

    def test_full_mask_at_t1_fills_the_grid_with_max_dots(self):
        full = np.full((S, S), 255, np.uint8)
        dots = ix.place_dots(full, lambda xs, ys: 1.0)
        grid = ix.hex_grid()
        self.assertEqual(len(dots), len(grid))
        rmax = 0.47 * ix.P
        for cx, cy, r in dots:
            self.assertAlmostEqual(r, rmax)
        a = ink(ix.render_dots(dots))
        for cx, cy, _ in dots:
            self.assertEqual(a[int(cy), int(cx)], 1.0)
        self.assertAlmostEqual(a.sum() / (len(dots) * math.pi * rmax * rmax), 1.0, delta=0.01)


class ToneLayer(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def pair(self, mask, grey, alpha=1.0, size=S):
        """Writes <tmp>/x.png (mask) and <tmp>/x.tone.png (grey, alpha) at `size`."""
        m, t = os.path.join(self.tmp, "x.png"), os.path.join(self.tmp, "x.tone.png")
        write_png(m, mask, size=size)
        write_png(t, alpha, grey, size=size)
        return m, t

    def check_follows(self, dots, lum_of, tol):
        self.assertGreater(len(dots), 100)
        for cx, cy, r in dots:
            want = ix.dot_radius(ix.tone_t(lum_of(cx, cy), cx, cy))
            self.assertAlmostEqual(r, want, delta=tol, msg="dot at %.1f,%.1f" % (cx, cy))

    def test_tone_lines_up_with_the_mask(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        m, t = self.pair(ring(), cols)
        dots = ix.stipple_dots(ix.load_shape(m), ix.load_tone(t))
        self.check_follows(dots, lambda cx, cy: cx / (S - 1.0), 0.03)
        # and transposed, so swapped axes cannot pass
        m, t = self.pair(ring(), cols.T)
        dots = ix.stipple_dots(ix.load_shape(m), ix.load_tone(t))
        self.check_follows(dots, lambda cx, cy: cy / (S - 1.0), 0.03)

    def test_step_tone_splits_dot_sizes(self):
        step = (np.arange(S)[None, :] >= S // 2) * np.ones((S, 1))
        m, t = self.pair(1.0, step)
        dots = ix.stipple_dots(ix.load_shape(m), ix.load_tone(t))
        left = [(cx, cy, r) for cx, cy, r in dots if cx < S / 2 - ix.P / 4 - 1]
        right = [(cx, cy, r) for cx, cy, r in dots if cx > S / 2 + ix.P / 4 + 1]
        self.assertTrue(left and right)
        for side, lum in ((left, 0.0), (right, 1.0)):
            for cx, cy, r in side:
                self.assertAlmostEqual(r, ix.dot_radius(ix.tone_t(lum, cx, cy)), delta=1e-6)
        self.assertLess(max(r for _, _, r in left), min(r for _, _, r in right))

    def test_old_192px_export_is_scaled_smoothly_and_stays_aligned(self):
        n = 192
        cols = np.linspace(0, 1, n)[None, :] * np.ones((n, 1))
        m, t = self.pair(ring(n), cols, size=n)  # binary mask: hard edges at 192 px
        shape, tone = ix.load_shape(m), ix.load_tone(t)
        self.assertEqual(shape.shape, (S, S))
        self.assertTrue(((shape > 0) & (shape < 255)).any())  # smooth resample, not nearest-neighbour
        # 432 px coordinate cx sits at 192 px pixel index cx * n / S - 0.5
        self.check_follows(ix.stipple_dots(shape, tone),
                           lambda cx, cy: (cx * n / float(S) - 0.5) / (n - 1.0), 0.05)

    def test_luminance_is_weighted_by_coverage(self):
        lum = np.where(np.arange(S)[None, :] < S // 2, 1.0, 0.3) * np.ones((S, 1))
        cov = np.where(np.arange(S)[None, :] < S // 2, 0.0, 1.0) * np.ones((S, 1))
        m, t = self.pair(1.0, lum, cov)
        tone = ix.load_tone(t)
        self.assertAlmostEqual(tone.mean(), 0.3, delta=0.005)
        self.assertIsNone(ix.Tone(np.ones((S, S)), np.zeros((S, S))).mean())

    def test_missing_tone_file_uses_default_level(self):
        self.assertIsNone(ix.load_tone(os.path.join(self.tmp, "nope.tone.png")))
        m = os.path.join(self.tmp, "x.png")
        write_png(m, ring())
        dots = ix.stipple_dots(ix.load_shape(m), None)
        self.check_follows(dots, lambda cx, cy: 0.7, 1e-6)

    def test_tone_from_rec709_channels(self):
        rgb = np.zeros((S, S, 4), np.uint8)
        rgb[:, :] = (0, 255, 0, 255)
        t = os.path.join(self.tmp, "g.tone.png")
        Image.fromarray(rgb, "RGBA").save(t)
        self.assertAlmostEqual(ix.load_tone(t).mean(), 0.7152, delta=0.003)


class Pipeline(unittest.TestCase):
    """fix() end to end on a synthetic export (needs the simple-icons cache)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.inp = os.path.join(self.tmp, "icons")
        self.out = os.path.join(self.tmp, "final")
        os.makedirs(self.inp)
        self.rows = []
        self.lines = []

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def add(self, pkg, kind, label, mask, tone=None):
        name = "ic_" + pkg.replace(".", "_")
        write_png(os.path.join(self.inp, name + ".png"), mask)
        if tone is not None:
            write_png(os.path.join(self.inp, name + ".tone.png"), 1.0, tone)
        self.rows.append("%s/.Main\t%s\t%s\t%s\n" % (pkg, name, kind, label))
        return name

    def run_fix(self):
        with open(os.path.join(self.inp, "index.tsv"), "w") as f:
            f.writelines(self.rows)
        return ix.fix(self.inp, self.out, None, log=self.lines.append)

    def final(self, name):
        return np.asarray(Image.open(os.path.join(self.out, name + ".png")))

    def test_blank_and_blob_mono_fall_back(self):
        self.add("com.example.blank", "mono", "Blank", 0.0)
        self.add("com.example.ring", "mono", "Ring", ring())
        self.add("com.example.blob", "mono", "Blob", 1.0)
        self.add("com.example.quux", "auto", "Quux", ring())
        counts = self.run_fix()
        self.assertEqual(counts, {"mono": 1, "initial": 3})
        log = "\n".join(self.lines)
        self.assertRegex(log, r"com\.example\.blank\s+fill 0\.00\s+-> initial")
        self.assertRegex(log, r"com\.example\.blob\s+fill 1\.00\s+-> initial")

    def test_mono_stipples_shape_with_per_dot_tone(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        name = self.add("com.example.ring", "mono", "Ring", ring(), cols)
        self.run_fix()
        want = ix.stipple(ix.load_shape(os.path.join(self.inp, name + ".png")),
                          ix.load_tone(os.path.join(self.inp, name + ".tone.png")))
        self.assertTrue(np.array_equal(self.final(name), np.asarray(want)))

    def test_replacement_takes_the_mean_luminance(self):
        a = self.add("com.example.quux", "auto", "Quux", ring(), 0.2)
        b = self.add("com.example.zork", "auto", "Zork", ring())
        self.run_fix()
        self.assertTrue(np.array_equal(self.final(a), np.asarray(ix.stipple(ix.letter_mask("Q"), 0.2))))
        self.assertTrue(np.array_equal(self.final(b), np.asarray(ix.stipple(ix.letter_mask("Z"), 0.7))))

    def test_only_final_pngs_are_written(self):
        names = [self.add("com.example.ring", "mono", "Ring", ring(), 0.5),
                 self.add("com.example.quux", "auto", "Quux", ring(), 0.5)]
        os.makedirs(self.out)
        open(os.path.join(self.out, "stale.tone.png"), "w").close()
        self.run_fix()
        self.assertEqual(sorted(os.listdir(self.out)),
                         sorted([n + ".png" for n in names] + ["index.tsv", "contact.png"]))
        with open(os.path.join(self.out, "index.tsv")) as f:
            self.assertEqual({len(l.rstrip("\n").split("\t")) for l in f}, {4})
        for n in names:
            a = self.final(n)
            self.assertEqual({tuple(p) for p in a[a[:, :, 3] > 0][:, :3]}, {ix.FG})

    def test_brand_blob_is_measured_against_the_padded_box(self):
        solid = ix.brand_icon(SOLID_SVG)
        self.assertLess(ix.fill_ratio(np.asarray(solid.getchannel("A"))), ix.MAX_FILL)  # the old bug
        self.assertGreater(ix.brand_fill(solid), ix.MAX_FILL)
        self.assertFalse(ix.brand_ok(solid))
        with open(os.path.join(ix.ensure_simple_icons(), "icons", "netflix.svg"), "rb") as f:
            self.assertTrue(ix.brand_ok(ix.brand_icon(f.read())))
        self.add("com.example.quux", "auto", "Quux", ring())
        with mock.patch.dict(ix.PACKAGE_TO_SLUG, {"com.example.quux": "netflix"}), \
                mock.patch.object(ix, "brand_icon", return_value=solid):
            counts = self.run_fix()
        self.assertEqual(counts, {"initial": 1})
        self.assertIn("brand netflix rejected", "\n".join(self.lines))

    def test_real_brand_glyph_is_stippled(self):
        name = self.add("com.netflix.mediaclient", "auto", "Netflix", ring(), 0.5)
        self.assertEqual(self.run_fix(), {"brand": 1})
        a = self.final(name)
        self.assertEqual({tuple(p) for p in a[a[:, :, 3] > 0][:, :3]}, {ix.FG})


class Initial(unittest.TestCase):
    def test_letter_mask_is_a_bold_capital(self):
        m = ix.letter_mask("i")
        self.assertEqual((m.shape, m.dtype), ((S, S), np.uint8))
        ys, xs = np.nonzero(m >= 128)
        self.assertAlmostEqual((ys.max() - ys.min() + 1) / S, ix.LETTER_H, delta=0.01)
        self.assertAlmostEqual((ys.max() + ys.min()) / 2.0, S / 2.0, delta=2)
        stem = xs.max() - xs.min() + 1  # capital I: one stem
        self.assertGreater(stem, 2 * ix.P)  # bold enough to stipple at least two dots across

    def test_initial_stipples_like_any_icon(self):
        img = ix.stipple(ix.letter_mask("F"), 0.7)
        a = np.asarray(img)
        on = a[:, :, 3] > 0
        self.assertEqual({tuple(p) for p in a[on][:, :3]}, {ix.FG})
        self.assertLess(ix.fill_ratio(a[:, :, 3]), 0.3)
        self.assertGreater(len(ix.stipple_dots(ix.letter_mask("F"), 0.7)), 40)


class Bootstrap(unittest.TestCase):
    def test_in_venv_detection(self):
        with mock.patch.object(sys, "prefix", ix.VENV):
            self.assertTrue(ix._in_venv())
        with mock.patch.object(sys, "prefix", "/usr"):
            self.assertFalse(ix._in_venv())

    def test_no_reexec_from_inside_the_venv(self):
        with mock.patch.object(ix, "_in_venv", return_value=True), mock.patch("os.execv") as ex, \
                mock.patch("subprocess.check_call") as cc:
            with self.assertRaises(SystemExit) as cm:
                ix._bootstrap()
        self.assertIn("delete tools/.venv and re-run", str(cm.exception.code))
        ex.assert_not_called()
        cc.assert_not_called()

    def test_pip_failure_removes_the_venv(self):
        tmp = tempfile.mkdtemp()
        venv = os.path.join(tmp, ".venv")

        def fake(cmd, *a, **k):
            if "venv" in cmd:
                os.makedirs(os.path.join(venv, "bin"))
                open(os.path.join(venv, "bin", "python"), "w").close()
                return 0
            raise subprocess.CalledProcessError(1, cmd)

        try:
            with mock.patch.object(ix, "VENV", venv), mock.patch.object(ix, "_in_venv", return_value=False), \
                    mock.patch("subprocess.check_call", side_effect=fake), mock.patch("os.execv") as ex:
                with self.assertRaises(SystemExit):
                    ix._bootstrap()
            self.assertFalse(os.path.exists(venv))
            ex.assert_not_called()
        finally:
            shutil.rmtree(tmp)

    def test_half_built_venv_exits_instead_of_looping(self):
        py = os.path.join(ix.VENV, "bin", "python")
        if not os.path.isfile(py):
            self.skipTest("no tools/.venv")
        tmp = tempfile.mkdtemp()
        try:
            with open(os.path.join(tmp, "skia.py"), "w") as f:
                f.write("raise ImportError('simulated half-built venv')\n")
            env = dict(os.environ, PYTHONPATH=tmp)
            try:
                p = subprocess.run([py, os.path.join(HERE, "iconfix.py"), tmp, os.path.join(tmp, "out")],
                                   env=env, capture_output=True, text=True, timeout=60)
            except subprocess.TimeoutExpired:
                self.fail("iconfix re-execs itself in a loop when the venv lacks skia")
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("delete tools/.venv and re-run", p.stderr)
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
