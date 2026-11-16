import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iconfix as ix  # noqa: E402


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

    def test_no_match(self):
        self.assertIsNone(ix.resolve_slug("x.y", "Zzz", {}, self.TITLES))

    def test_every_table_slug_exists_in_pinned_release(self):
        titles = ix.load_titles(ix.ensure_simple_icons())
        slugs = set(titles.values())
        for pkg, slug in ix.PACKAGE_TO_SLUG.items():
            self.assertIn(slug, slugs, pkg)


class Fill(unittest.TestCase):
    def test_ratio(self):
        self.assertAlmostEqual(ix.fill_ratio([0, 127, 128, 255]), 0.5)

    def test_rejection(self):
        self.assertTrue(ix.too_filled(0.56))
        self.assertFalse(ix.too_filled(0.55))

    def test_solid_blob_is_rejected_and_falls_back(self):
        solid = [255] * 100
        ring = [255 if i % 2 else 0 for i in range(100)]
        self.assertTrue(ix.too_filled(ix.fill_ratio(solid)))
        self.assertFalse(ix.too_filled(ix.fill_ratio(ring)))


class Rasterise(unittest.TestCase):
    def test_dot_initial_is_soft_fg_and_not_blob(self):
        a = ix.dot_initial("F")
        self.assertEqual(a.size, (432, 432))
        alphas = list(a.getchannel("A").tobytes())
        self.assertGreater(max(alphas), 200)
        self.assertLess(ix.fill_ratio(alphas), 0.3)
        rgb = {p[:3] for p in a.get_flattened_data() if p[3] > 0}
        self.assertEqual(rgb, {(0xDD, 0xDD, 0xDD)})


if __name__ == "__main__":
    unittest.main()
