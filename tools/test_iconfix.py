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
SVG = b'<svg role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path %s/></svg>'
SOLID_SVG = SVG % b'd="M0 0h24v24H0z"'
WIDE_SVG = SVG % b'd="M0 9h24v6H0z"'  # 4:1 bar, a wordmark's proportions
FRAME_SVG = SVG % b'fill-rule="evenodd" d="M2 2h20v20H2zM4.5 4.5v15h15v-15z"'  # square, fill ~0.44


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


def specks(n=12, r=12, size=S):
    """n small discs far apart: a noisy mask that stipples into n separate clusters."""
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    m = np.zeros((size, size), bool)
    for k in range(n):
        cx, cy = 90 + (k % 4) * 84, 120 + (k // 4) * 96
        m |= np.hypot(xx - cx, yy - cy) <= r
    return m.astype(np.float32)


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

def half_cov_shape(cov):
    """A mask whose every cell has mean alpha `cov` (full rows of 255 in the top part of each cell)."""
    a = np.zeros((S, S), np.uint8)
    rows = int(round(cov * 9))
    for y in range(S):
        if y % 9 < rows:
            a[y, :] = 255
    return a


def r_of(shape, lum=1.0):
    dots = ix.place_dots(shape, lum)
    return sorted({round(r, 6) for _, _, r in dots}), dots


class Grid(unittest.TestCase):
    def test_pitch_and_cells(self):
        self.assertEqual(ix.CELLS, 48)
        self.assertAlmostEqual(ix.P, 9.0)

    def test_square_grid_of_48_by_48_candidate_cells(self):
        dots = ix.place_dots(np.full((S, S), 255, np.uint8), 1.0)
        self.assertEqual(len(dots), 48 * 48)
        xs = sorted({round(x, 6) for x, _, _ in dots})
        ys = sorted({round(y, 6) for _, y, _ in dots})
        self.assertEqual(xs, [(i + 0.5) * 9 for i in range(48)])
        self.assertEqual(ys, xs)
        self.assertEqual(len({(round(x, 6), round(y, 6)) for x, y, _ in dots}), 48 * 48)

    def test_symmetric_glyph_stipples_symmetrically(self):
        dots = ix.place_dots(u8(ring()), 1.0)
        key = lambda x, y: (round(x, 6), round(y, 6))  # noqa: E731
        pts = {key(x, y) for x, y, _ in dots}
        self.assertEqual(pts, {key(x, S - y) for x, y in pts})
        self.assertEqual(pts, {key(S - x, y) for x, y in pts})

    def test_full_mask_full_tone_gives_every_cell_max_radius(self):
        rs, dots = r_of(np.full((S, S), 255, np.uint8), 1.0)
        for _, _, r in dots:
            self.assertAlmostEqual(r, 9 * 0.47 * math.sqrt(1.0), delta=0.05)
        self.assertAlmostEqual(dots[0][2], 4.23, delta=0.05)

    def test_half_covered_edge_cell_radius_scales_with_sqrt_cov(self):
        rs, dots = r_of(half_cov_shape(0.5), 1.0)  # rows 0..3 of 9 are ink: cov 4/9
        cov = 4 / 9.0
        want = 9 * 0.47 * math.sqrt(cov / 0.85)
        self.assertEqual(len(rs), 1)
        self.assertAlmostEqual(rs[0], want, delta=0.01)
        full = 9 * 0.47
        self.assertAlmostEqual(rs[0] / full, math.sqrt(cov / 0.85), delta=0.002)

    def test_cell_below_012_is_skipped_and_at_012_is_drawn(self):
        low = ix.place_dots(np.full((S, S), round(0.11 * 255), np.uint8), 1.0)  # 0.1098
        at = ix.place_dots(np.full((S, S), round(0.12 * 255), np.uint8), 1.0)   # 0.1216
        self.assertEqual(len(low), 0)
        self.assertEqual(len(at), 48 * 48)
        cut_off = np.full((S, S), 255, np.uint8)
        exact = np.zeros((S, S), np.uint8)
        for y in range(S):  # 1 of 9 rows inked: cov 0.111 < 0.12 -> skipped
            if y % 9 < 1:
                exact[y, :] = 255
        self.assertEqual(len(ix.place_dots(exact, 1.0)), 0)
        two = np.zeros((S, S), np.uint8)
        for y in range(S):  # 2 of 9 rows: cov 0.222 >= 0.12 -> drawn
            if y % 9 < 2:
                two[y, :] = 255
        self.assertEqual(len(ix.place_dots(two, 1.0)), 48 * 48)
        self.assertEqual(len(ix.place_dots(cut_off, 1.0)), 48 * 48)

    def test_min_radius_applies_from_cov_03(self):
        # cov 0.3: sqrt(0.3/0.85) * 0.47 * 9 = 2.51 > 0.22 * 9 = 1.98, so use a low tone-free check on dot_radius
        self.assertAlmostEqual(float(ix.dot_radius(0.85, 0.3)), max(9 * 0.47 * math.sqrt(0.85) * math.sqrt(0.3 / 0.85), 1.98))
        # below 0.3 the floor does not apply: r follows the formula down to tiny dots
        r = float(ix.dot_radius(0.85, 0.12))
        self.assertAlmostEqual(r, 9 * 0.47 * math.sqrt(0.85) * math.sqrt(0.12 / 0.85))
        self.assertLess(r, 0.22 * 9)
        # a dim, thin cell right at the threshold gets lifted to the floor
        self.assertAlmostEqual(float(ix.dot_radius(0.0, 0.3)), 0.22 * 9)
        self.assertAlmostEqual(float(ix.dot_radius(0.0, 0.299)), 0.0)
        self.assertGreaterEqual(float(ix.dot_radius(0.85, 0.3)), 0.22 * 9)

    def test_tone_is_flat(self):
        full = np.full((S, S), 255, np.uint8)
        r0 = ix.place_dots(full, 0.0)[0][2]
        r1 = ix.place_dots(full, 1.0)[0][2]
        self.assertAlmostEqual(r0 / r1, math.sqrt(0.85), delta=1e-9)
        self.assertAlmostEqual(r0, 9 * 0.47 * math.sqrt(0.85), delta=1e-9)

    def test_no_gradient(self):
        full = np.full((S, S), 255, np.uint8)
        dots = ix.place_dots(full, 0.5)
        self.assertEqual(len({round(r, 9) for _, _, r in dots}), 1)
        self.assertAlmostEqual(dots[0][2], dots[-1][2])  # top-left vs bottom-right

    def test_tone_mapping(self):
        self.assertAlmostEqual(ix.tone_t(1.0), 1.0)
        self.assertAlmostEqual(ix.tone_t(0.0), 0.85)
        self.assertAlmostEqual(ix.tone_t(0.7), 0.85 + 0.15 * 0.7)

    def test_radius_grows_with_tone_and_coverage(self):
        rs = [float(ix.dot_radius(ix.tone_t(L), 1.0)) for L in np.linspace(0, 1, 11)]
        self.assertTrue(all(b > a for a, b in zip(rs, rs[1:])))
        rs = [float(ix.dot_radius(1.0, c)) for c in np.linspace(0.12, 1, 20)]
        self.assertTrue(all(b >= a for a, b in zip(rs, rs[1:])))


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
        dots = ix.place_dots(np.full((S, S), 255, np.uint8)[:, :], 0.5)
        img = ix.render_dots(dots)
        a = np.asarray(img.getchannel("A"))
        partial = (a > 0) & (a < 255)
        self.assertGreater(int(partial.sum()), 10 * len(dots))  # soft rims, not jagged steps
        for meas, (_, _, r) in zip(measured_radii(img, dots), dots):
            self.assertAlmostEqual(meas, r, delta=0.05)

    def test_dot_edges_match_exact_coverage(self):
        # off-grid centres and the whole radius range; every pixel within 20/255 of exact coverage
        for cx, cy, r in ((40.0, 40.0, 0.22 * ix.P), (100.3, 61.77, 5.2), (200.5, 300.25, 0.47 * ix.P)):
            a = ink(ix.render_dots([(cx, cy, r)]))
            x0, y0 = int(cx) - 10, int(cy) - 10
            err = np.abs(a[y0:y0 + 20, x0:x0 + 20] - exact_coverage(cx, cy, r, x0, y0, 20))
            self.assertLess(err.max(), 20 / 255.0, "r=%.2f" % r)
            self.assertAlmostEqual(a.sum(), math.pi * r * r, delta=0.03 * math.pi * r * r)  # 4x supersample: small dots

    def test_dot_radius_follows_tone(self):
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
            self.assertTrue(all(b >= a for a, b in zip(rs, rs[1:])), rs)
            self.assertGreater(rs[-1] - rs[0], 0.2)  # flat tone: only ~0.35 px from black to white

    def test_no_ink_outside_shape_dilated_by_pitch(self):
        for name, shape in (("ring", ring()), ("blobs", blobs()), ("letter", ix.letter_mask("R") / 255.0)):
            img = ix.stipple(u8(shape), 1.0)
            inked = ink(img) > 0
            self.assertTrue(inked.any(), name)
            outside = inked & ~dilate(shape > 0, ix.P)
            self.assertFalse(outside.any(), "%s: %d px outside" % (name, int(outside.sum())))

    def test_full_mask_at_full_tone_fills_the_grid_with_max_dots(self):
        full = np.full((S, S), 255, np.uint8)
        dots = ix.place_dots(full, 1.0)
        self.assertEqual(len(dots), 48 * 48)
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

    def check_follows(self, dots, lum_of, tol, shape=None):
        self.assertGreater(len(dots), 100)
        cov = ix.cell_means(ring() if shape is None else shape)
        for cx, cy, r in dots:
            c = cov[int(cy // ix.P), int(cx // ix.P)]
            want = ix.dot_radius(ix.tone_t(lum_of(cx, cy)), c)
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
        left = [(cx, cy, r) for cx, cy, r in dots if cx < S / 2]
        right = [(cx, cy, r) for cx, cy, r in dots if cx > S / 2]
        self.assertTrue(left and right)
        for side, lum in ((left, 0.0), (right, 1.0)):
            for cx, cy, r in side:
                self.assertAlmostEqual(r, ix.dot_radius(ix.tone_t(lum), 1.0), delta=1e-6)
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
                           lambda cx, cy: (cx * n / float(S) - 0.5) / (n - 1.0), 0.05, shape=shape / 255.0)

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


class ExportFixture(unittest.TestCase):
    """A synthetic phone export in a temp dir, run through fix() (needs the simple-icons cache)."""

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


class Pipeline(ExportFixture):
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
        with mock.patch.object(ix, "KEEP_STANDIN", frozenset(["com.example.quux"])):
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
        with mock.patch.object(ix, "KEEP_STANDIN", frozenset(["com.netflix.mediaclient"])):
            self.assertEqual(self.run_fix(), {"brand": 1})
        a = self.final(name)
        self.assertEqual({tuple(p) for p in a[a[:, :, 3] > 0][:, :3]}, {ix.FG})


class Initial(unittest.TestCase):
    def test_letter_mask_is_a_bold_capital(self):
        m = ix.letter_mask("i")
        self.assertEqual((m.shape, m.dtype), ((S, S), np.uint8))
        ys, xs = np.nonzero(m >= 128)
        self.assertAlmostEqual((ys.max() - ys.min() + 1) / S, ix.LETTER_H, delta=0.01)  # fallback height
        self.assertAlmostEqual((ys.max() + ys.min()) / 2.0, S / 2.0, delta=2)
        stem = xs.max() - xs.min() + 1  # capital I: one stem
        self.assertGreater(stem, 2 * ix.P)  # bold enough to stipple at least two dots across

    def test_letter_is_fitted_into_the_box(self):
        for ch, box in (("W", (150.0, 200.0)), ("I", (150.0, 200.0)), ("Q", (230.0, 230.0))):
            x0, y0, x1, y1 = ix.ink_bbox(ix.letter_mask(ch, box))
            w, h = x1 - x0, y1 - y0
            self.assertLessEqual(w, box[0] + 2, ch)
            self.assertLessEqual(h, box[1] + 2, ch)
            self.assertTrue(w >= box[0] - 4 or h >= box[1] - 4, "%s %dx%d" % (ch, w, h))  # touches one side
            self.assertAlmostEqual((x0 + x1) / 2.0, S / 2.0, delta=2)
            self.assertAlmostEqual((y0 + y1) / 2.0, S / 2.0, delta=2)

    def test_initial_stipples_like_any_icon(self):
        img = ix.stipple(ix.letter_mask("F"), 0.7)
        a = np.asarray(img)
        on = a[:, :, 3] > 0
        self.assertEqual({tuple(p) for p in a[on][:, :3]}, {ix.FG})
        self.assertLess(ix.fill_ratio(a[:, :, 3]), 0.3)
        self.assertGreater(len(ix.stipple_dots(ix.letter_mask("F"), 0.7)), 40)


class Wordmark(unittest.TestCase):
    def test_wide_glyph_is_rejected(self):
        img = ix.brand_icon(WIDE_SVG)
        self.assertLess(ix.brand_fill(img), ix.MAX_FILL)  # not a blob: rejected for its shape
        self.assertEqual(ix.brand_problem(img), "wordmark")
        tall = ix.glyph_from_alpha(Image.fromarray(np.ascontiguousarray(np.asarray(img.getchannel("A")).T)))
        self.assertTrue(ix.wordmark(np.asarray(tall.getchannel("A"))))  # either way round

    def test_square_glyph_passes(self):
        img = ix.brand_icon(FRAME_SVG)
        self.assertIsNone(ix.brand_problem(img))
        self.assertTrue(ix.brand_ok(img))

    def test_glyph_spanning_too_few_dot_rows_is_rejected(self):
        # a band 2 cells tall (18 px): a thin glyph now stipples (small dots) rather than breaking up, so the
        # row gate is checked on a short, wide-enough band that is not caught by the aspect test
        a = np.zeros((S, S), np.uint8)
        a[S // 2 - 9:S // 2 + 9, 100:332] = 255
        self.assertLess(ix.row_span(ix.stipple_dots(a)), ix.MIN_ROWS)
        self.assertTrue(ix.wordmark(a))
        self.assertEqual(ix.row_span([]), 0)

    def test_blob_check_uses_the_square_padded_box(self):
        icons = os.path.join(ix.ensure_simple_icons(), "icons")
        with open(os.path.join(icons, "snapchat.svg"), "rb") as f:
            ghost = ix.brand_icon(f.read(), (220.0, 208.0))  # solid silhouette, tightly fitted
        x0, y0, x1, y1 = ix.ink_bbox(np.asarray(ghost.getchannel("A")))
        own = ix.fill_ratio(np.asarray(ghost.getchannel("A"))[y0:y1, x0:x1])
        self.assertGreater(own, ix.MAX_FILL)  # its own bbox would call it a blob
        self.assertLess(ix.brand_fill(ghost, (220.0, 208.0)), ix.MAX_FILL)
        self.assertTrue(ix.brand_ok(ghost, (220.0, 208.0)))
        self.assertFalse(ix.brand_ok(ix.brand_icon(SOLID_SVG, (220.0, 208.0)), (220.0, 208.0)))  # still a blob

    def test_real_wordmarks_and_glyphs(self):
        icons = os.path.join(ix.ensure_simple_icons(), "icons")
        for slug, wordmark in (("discover", True), ("sony", True), ("netgear", True),
                               ("netflix", False), ("paypal", False), ("snapchat", False)):
            with open(os.path.join(icons, slug + ".svg"), "rb") as f:
                self.assertEqual(ix.brand_problem(ix.brand_icon(f.read())) == "wordmark", wordmark, slug)

    def test_wordmark_falls_back_in_the_pipeline(self):
        tmp = tempfile.mkdtemp()
        try:
            inp, out = os.path.join(tmp, "in"), os.path.join(tmp, "out")
            os.makedirs(inp)
            write_png(os.path.join(inp, "ic_x.png"), ring())
            with open(os.path.join(inp, "index.tsv"), "w") as f:
                f.write("com.discoverfinancial.mobile/.Main\tic_x\tauto\tDiscover\n")
            lines = []
            self.assertEqual(ix.fix(inp, out, None, log=lines.append), {"initial": 1})
            self.assertIn("brand discover rejected (wordmark)", "\n".join(lines))
        finally:
            shutil.rmtree(tmp)


class GlyphSize(unittest.TestCase):
    def test_box_is_the_median_mono_bbox(self):
        shapes = []
        for k in range(9):
            m = np.zeros((S, S), np.uint8)
            w, h = 150 + 10 * k, 100 + 20 * k
            m[50:50 + h, 60:60 + w] = 255
            shapes.append(m)
        self.assertEqual(ix.glyph_box(shapes), (190.0, 180.0))
        self.assertIsNone(ix.glyph_box(shapes[:ix.MIN_MONO - 1]))
        self.assertIsNone(ix.glyph_box([np.zeros((S, S), np.uint8)] * 20))  # blank shapes don't count

    def test_fallback_boxes(self):
        self.assertEqual(ix.brand_box(), (S * (1 - 2 * 0.24),) * 2)
        self.assertEqual(ix.letter_box(), (S * (1 - 2 * 0.24), S * 0.50))
        self.assertEqual(ix.brand_box((200.0, 150.0)), (200.0, 150.0))

    def test_brand_glyph_is_fitted_and_centred(self):
        icons = os.path.join(ix.ensure_simple_icons(), "icons")
        for slug in ("netflix", "paypal", "snapchat"):
            with open(os.path.join(icons, slug + ".svg"), "rb") as f:
                svg = f.read()
            for box in ((150.0, 200.0), (230.0, 230.0)):
                x0, y0, x1, y1 = ix.ink_bbox(np.asarray(ix.brand_icon(svg, box).getchannel("A")))
                w, h = x1 - x0, y1 - y0
                self.assertLessEqual(w, box[0] + 2, slug)
                self.assertLessEqual(h, box[1] + 2, slug)
                self.assertTrue(w >= box[0] - 3 or h >= box[1] - 3, "%s %dx%d in %s" % (slug, w, h, box))
                self.assertAlmostEqual((x0 + x1) / 2.0, S / 2.0, delta=1.5)
                self.assertAlmostEqual((y0 + y1) / 2.0, S / 2.0, delta=1.5)

    def test_replacements_take_the_runs_mono_glyph_size(self):
        tmp = tempfile.mkdtemp()
        try:
            inp, out = os.path.join(tmp, "in"), os.path.join(tmp, "out")
            os.makedirs(inp)
            mono = u8(ring(r0=0.15, r1=0.25))  # ~216 px across
            rows = []
            for k in range(ix.MIN_MONO):
                write_png(os.path.join(inp, "ic_m%d.png" % k), mono / 255.0)
                rows.append("com.m%d/.Main\tic_m%d\tmono\tM%d\n" % (k, k, k))
            write_png(os.path.join(inp, "ic_q.png"), ring())
            write_png(os.path.join(inp, "ic_n.png"), ring())
            rows += ["com.example.quux/.Main\tic_q\tauto\tQuux\n",
                     "com.netflix.mediaclient/.Main\tic_n\tauto\tNetflix\n"]
            with open(os.path.join(inp, "index.tsv"), "w") as f:
                f.writelines(rows)
            ix.fix(inp, out, None, log=lambda *_: None)
            box = ix.glyph_box([mono] * ix.MIN_MONO)
            self.assertAlmostEqual(box[0], 216, delta=2)
            got = np.asarray(Image.open(os.path.join(out, "ic_q.png")))
            self.assertTrue(np.array_equal(got, np.asarray(ix.stipple(ix.letter_mask("Q", box), 0.7))))
            with open(os.path.join(ix.ensure_simple_icons(), "icons", "netflix.svg"), "rb") as f:
                brand = np.asarray(ix.brand_icon(f.read(), box).getchannel("A"))
            got = np.asarray(Image.open(os.path.join(out, "ic_n.png")))
            self.assertTrue(np.array_equal(got, np.asarray(ix.stipple(brand, 0.7))))
        finally:
            shutil.rmtree(tmp)


class AutoShapes(ExportFixture):
    def run_fix(self, auto_shapes=True):
        with open(os.path.join(self.inp, "index.tsv"), "w") as f:
            f.writelines(self.rows)
        return ix.fix(self.inp, self.out, None, log=self.lines.append, auto_shapes=auto_shapes)

    def test_gate_reasons(self):
        self.assertIsNone(ix.shape_problem(u8(ring())))
        self.assertEqual(ix.shape_problem(u8(specks())), "12 parts")
        framed = ring()
        framed[:20, :] = 1.0  # background ink along the top edge
        self.assertTrue(ix.shape_problem(u8(framed)).startswith("edge"))
        self.assertTrue(ix.shape_problem(np.full((S, S), 255, np.uint8)).startswith("fill"))
        self.assertEqual(ix.shape_problem(u8(np.zeros((S, S)))), "fill 0.00")
        bar = np.zeros((S, S), np.float32)
        bar[200:240, 60:372] = 1.0
        bar[210:230, 70:362] = 0.0  # outlined, so it is not merely solid
        self.assertEqual(ix.shape_problem(u8(bar)), "wordmark")
        square = np.zeros((S, S), np.float32)
        square[136:296, 136:296] = 1.0  # fill 0.14 of the tile, but a featureless solid
        self.assertEqual(ix.shape_problem(u8(square)), "solid 1.00")
        disc = np.hypot(*(np.mgrid[0:S, 0:S] + 0.5 - S / 2.0)) <= 80
        self.assertTrue(ix.shape_problem(u8(disc)).startswith("solid 0.7"))

    def test_dot_parts_and_edge_share(self):
        self.assertEqual(ix.dot_parts([]), 0)
        self.assertEqual(ix.dot_parts(ix.stipple_dots(u8(ring()))), 1)
        self.assertEqual(ix.dot_parts(ix.stipple_dots(u8(specks()))), 12)
        self.assertEqual(ix.edge_share(u8(ring())), 0.0)
        m = np.zeros((S, S), np.uint8)
        m[:, :ix.EDGE_BAND] = 255
        m[100:300, 100:300] = 255
        self.assertAlmostEqual(ix.edge_share(m), S * ix.EDGE_BAND / float(S * ix.EDGE_BAND + 200 * 200))

    def test_clean_own_shape_is_stippled_with_its_tone(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        name = self.add("com.example.quux", "auto", "Quux", ring(), cols)
        self.assertEqual(self.run_fix(), {"auto": 1})
        want = ix.stipple(ix.load_shape(os.path.join(self.inp, name + ".png")),
                          ix.load_tone(os.path.join(self.inp, name + ".tone.png")))
        self.assertTrue(np.array_equal(self.final(name), np.asarray(want)))

    def test_noisy_or_edge_shapes_fall_back_to_initials(self):
        self.add("com.example.noisy", "auto", "Noisy", specks())
        framed = ring()
        framed[:20, :] = 1.0
        self.add("com.example.framed", "auto", "Framed", framed)
        self.assertEqual(self.run_fix(), {"initial": 2})
        log = "\n".join(self.lines)
        self.assertIn("com.example.noisy: own shape rejected (12 parts)", log)
        self.assertIn("com.example.framed: own shape rejected (edge", log)

    def test_order_is_brand_then_own_shape_then_initial(self):
        self.add("com.netflix.mediaclient", "auto", "Netflix", ring())  # brand wins
        self.add("com.example.quux", "auto", "Quux", ring())  # own shape
        self.add("com.example.mono", "mono", "Blob", 1.0)  # mono rows never use their own (rejected) shape
        self.assertEqual(self.run_fix(), {"brand": 1, "auto": 1, "initial": 1})

    def test_off_by_default(self):
        self.add("com.example.quux", "auto", "Quux", ring())
        self.assertEqual(self.run_fix(auto_shapes=False), {"initial": 1})
        self.assertEqual(ix.MARK["auto"], "A")

    def test_command_line_flag(self):
        a = ix.parse_args(["iconfix.py", "--auto-shapes", "in", "out"])
        self.assertEqual((a.in_dir, a.out_dir, a.auto_shapes), ("in", "out", True))
        a = ix.parse_args(["iconfix.py"])
        self.assertFalse(a.auto_shapes)
        self.assertTrue(a.in_dir.endswith(os.path.join("build", "icons")))


class RealIcon(ExportFixture):
    def tone_obj(self, lum, cov=1.0):
        return ix.Tone(np.broadcast_to(np.asarray(lum, np.float64), (S, S)),
                       np.broadcast_to(np.asarray(cov, np.float64), (S, S)))

    def test_tile_mask(self):
        m = ix.tile_mask((200, 160))
        self.assertEqual(m.shape, (S, S))
        self.assertEqual(m[S // 2, S // 2], 1.0)
        self.assertEqual(m[10, 10], 0.0)
        self.assertEqual(m[S // 2, (S - 200) // 2 + 2], 1.0)
        self.assertEqual(m[S // 2, (S - 200) // 2 - 2], 0.0)
        self.assertEqual(m[(S - 200) // 2 + 1, (S - 200) // 2 + 1], 0.0)  # rounded corner
        self.assertTrue(((m > 0) & (m < 1)).any())  # anti-aliased

    def test_content_is_resampled_not_cropped(self):
        lum = np.zeros((S, S))
        lum[:, 82:350] = 0.0
        lum[200:232, 82:90] = 1.0   # marks on the left and right edges of the central 268 px square
        lum[200:232, 342:350] = 1.0
        t = ix.fit_tone(self.tone_obj(lum), (200, 160))
        xs = np.nonzero(t.lum.max(axis=0) > 0.3)[0]
        self.assertGreaterEqual(xs.min(), (S - 200) // 2 - 1)
        self.assertLessEqual(xs.max(), (S - 200) // 2 + 200)
        self.assertLess(xs.min(), (S - 200) // 2 + 6)  # the edge marks survive, at the tile's edges
        self.assertGreater(xs.max(), (S - 200) // 2 + 193)
        self.assertEqual(float(t.cov[0, 0]), 0.0)

    def test_polarity_flips_on_bright_tile(self):
        mask = ix.tile_mask()
        bright = np.full((S, S), 0.9)
        bright[200:260, 200:260] = 0.2  # dark logo on a bright tile
        lv = ix.real_levels(self.tone_obj(bright), mask)
        self.assertGreater(lv[230, 230], 0.9)  # logo ends up bright
        self.assertLess(lv[S // 2 + 60, S // 2 + 60], 0.1)  # tile ends up dark
        lv2 = ix.real_levels(self.tone_obj(1.0 - bright), mask)  # already dark tile: same result
        self.assertTrue(np.allclose(lv, lv2))

    def test_stretch_maps_p2_to_zero(self):
        mask = ix.tile_mask()
        ramp = np.linspace(0.1, 0.5, S)[None, :] * np.ones((S, 1))
        lv = ix.real_levels(self.tone_obj(ramp), mask)
        inside = mask > 0.5
        self.assertAlmostEqual(float(np.percentile(lv[inside], 2)), 0.0, places=2)
        self.assertAlmostEqual(float(np.percentile(lv[inside], 98)), 1.0, places=2)
        flat = ix.real_levels(self.tone_obj(0.4), mask)
        self.assertTrue(np.allclose(flat, 0.4))  # span < 1e-3: as is

    def test_skip_thresholds(self):
        ramp = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        dots = ix.real_dots(self.tone_obj(ramp))
        self.assertTrue(dots)
        cov = ix.cell_means(ix.tile_mask())
        for cx, cy, r in dots:
            self.assertGreaterEqual(cov[int(cy // ix.P), int(cx // ix.P)], 0.12)
            self.assertLessEqual(r, ix.P * 0.47 + 1e-9)
        self.assertGreater(min(cx for cx, _, _ in dots), (S - ix.TILE_FALLBACK * S) / 2.0)
        self.assertEqual(ix.real_dots(self.tone_obj(0.5, 0.0)), [])  # no coverage
        # stretched brightness < 0.10 is skipped: a dark ramp's left tenth of the tile
        n = len(ix.real_dots(self.tone_obj(np.linspace(0, 1, S)[None, :] ** 3 * np.ones((S, 1)))))
        self.assertLess(n, len(dots))

    def test_stand_in_becomes_real(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        name = self.add("com.example.quux", "auto", "Quux", specks(), cols)
        self.add("com.netflix.mediaclient", "auto", "Netflix", ring(), cols)
        self.assertEqual(self.run_fix(), {"real": 2})
        want = ix.render_dots(ix.real_dots(ix.load_tone(os.path.join(self.inp, name + ".tone.png"))))
        self.assertTrue(np.array_equal(self.final(name), np.asarray(want)))
        self.assertEqual(ix.MARK["real"], "R")

    def test_keep_list_and_missing_tone_keep_stand_in(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        self.add("com.hungrydroid.whitescreen", "auto", "White", specks(), cols)
        self.add("com.example.notone", "auto", "Notone", specks())
        self.add("com.example.quux", "auto", "Quux", ring(), cols)  # own shape passes the gate
        with open(os.path.join(self.inp, "index.tsv"), "w") as f:
            f.writelines(self.rows)
        counts = ix.fix(self.inp, self.out, None, log=self.lines.append, auto_shapes=True)
        self.assertEqual(counts, {"initial": 2, "auto": 1})

    def test_mono_untouched(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        self.add("com.example.mono", "mono", "Mono", ring(), cols)
        self.assertEqual(self.run_fix(), {"mono": 1})


class Determinism(ExportFixture):
    def test_two_runs_are_byte_identical(self):
        cols = np.linspace(0, 1, S)[None, :] * np.ones((S, 1))
        for k in range(ix.MIN_MONO):
            self.add("com.m%d" % k, "mono", "M%d" % k, ring(r0=0.12 + 0.01 * k, r1=0.3), cols)
        self.add("com.netflix.mediaclient", "auto", "Netflix", ring(), 0.3)
        self.add("com.example.quux", "auto", "Quux", ring(), cols)
        self.add("com.example.noisy", "auto", "Noisy", specks())
        self.add("com.example.blank", "mono", "Blank", 0.0)
        with open(os.path.join(self.inp, "index.tsv"), "w") as f:
            f.writelines(self.rows)

        def snapshot(d):
            out = {}
            for n in sorted(os.listdir(d)):
                with open(os.path.join(d, n), "rb") as f:
                    out[n] = f.read()
            return out

        for auto in (False, True):
            a, b = os.path.join(self.tmp, "a%d" % auto), os.path.join(self.tmp, "b%d" % auto)
            ix.fix(self.inp, a, None, log=lambda *_: None, auto_shapes=auto)
            first = snapshot(a)
            ix.fix(self.inp, b, None, log=lambda *_: None, auto_shapes=auto)
            ix.fix(self.inp, a, None, log=lambda *_: None, auto_shapes=auto)  # re-run in place
            self.assertEqual(len(first), len(self.rows) + 2)  # PNGs + index.tsv + contact.png
            self.assertEqual(snapshot(b), first)
            self.assertEqual(snapshot(a), first)


class Glass(ExportFixture):
    def setUp(self):
        super().setUp()
        for k in range(ix.MIN_MONO):
            self.add("com.m%d" % k, "mono", "M%d" % k, ring(r0=0.12 + 0.01 * k, r1=0.3))
        self.add("com.example.blank", "mono", "Blank", 0.0)
        with open(os.path.join(self.inp, "index.tsv"), "w") as f:
            f.writelines(self.rows)

    def run_glass(self, out, glass=True, ov=None):
        ix.fix(self.inp, out, ov, log=lambda *_: None, glass=glass)

    def test_tile_pixels(self):
        self.run_glass(self.out)
        im = np.asarray(Image.open(os.path.join(self.out, "ic_com_example_blank.png")))
        self.assertEqual(im.shape, (S, S, 4))
        self.assertEqual(im[0, 0, 3], 0)
        self.assertEqual(im[S - 1, S - 1, 3], 0)
        # a spot away from the glyph (inside the tile, outside the 70% glyph area): about 0x8C plus sheen
        a = int(im[S // 2, int(S * 0.13), 3])
        self.assertLess(abs(a - 0x8C), 24)
        rim_tl, rim_br = int(im[S // 2, 2, 3]), int(im[S // 2, S - 3, 3])
        self.assertGreater(rim_tl, rim_br)
        self.assertGreater(int(im[S // 2, 2, 0]), int(im[S // 2, S - 3, 0]))
        self.assertGreater(int(im[2, S // 2, 0]), int(im[S - 3, S // 2, 0]))

    def test_glyph_stays_fg(self):
        self.run_glass(self.out)
        im = np.asarray(Image.open(os.path.join(self.out, "ic_com_m0.png")))
        solid = np.zeros(im.shape[:2], bool)
        c = slice(int(S * 0.2), int(S * 0.8))
        solid[c, c] = im[c, c, 3] == 255
        self.assertTrue(solid.any())
        self.assertEqual(tuple(np.median(im[solid][:, :3], axis=0)), tuple(float(c) for c in ix.FG))

    def test_no_glass_unchanged(self):
        a, b = os.path.join(self.tmp, "a"), os.path.join(self.tmp, "b")
        self.run_glass(a, glass=False)
        ix.fix(self.inp, b, None, log=lambda *_: None)
        for n in os.listdir(a):
            with open(os.path.join(a, n), "rb") as f1, open(os.path.join(b, n), "rb") as f2:
                self.assertEqual(f1.read(), f2.read(), n)
        self.assertEqual(ix.parse_args(["x", "--no-glass"]).glass, False)
        self.assertEqual(ix.parse_args(["x"]).glass, True)

    def test_override_gets_tile(self):
        ov = os.path.join(self.tmp, "ov")
        os.makedirs(ov)
        write_png(os.path.join(ov, "ic_com_m0.png"), ring())
        self.run_glass(self.out, ov=ov)
        im = np.asarray(Image.open(os.path.join(self.out, "ic_com_m0.png")))
        self.assertEqual(im[0, 0, 3], 0)
        self.assertGreater(im[S // 2, int(S * 0.13), 3], 0)

    def test_deterministic(self):
        a, b = os.path.join(self.tmp, "a"), os.path.join(self.tmp, "b")
        self.run_glass(a)
        self.run_glass(b)
        self.run_glass(a)
        for n in os.listdir(a):
            with open(os.path.join(a, n), "rb") as f1, open(os.path.join(b, n), "rb") as f2:
                self.assertEqual(f1.read(), f2.read(), n)


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
