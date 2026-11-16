import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_basemap as bb  # noqa: E402

OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "basemap.json")


def inside(pt, ring):
    x, y = pt
    c = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


class BasemapTest(unittest.TestCase):
    def test_simplify_keeps_endpoints_and_drops_collinear(self):
        pts = [(0, 0), (1, 0), (2, 0), (3, 0), (4, 1)]
        out = bb.simplify(pts, 0.002)
        self.assertEqual(out[0], (0, 0))
        self.assertEqual(out[-1], (4, 1))
        self.assertNotIn((1, 0), out)
        self.assertNotIn((2, 0), out)
        self.assertIn((3, 0), out)

    def test_clip_splits_line_leaving_and_reentering_bbox(self):
        bbox = (0, 0, 10, 10)
        line = [(1, 5), (5, 5), (15, 5), (15, 8), (5, 8), (2, 8)]
        parts = bb.clip_line(line, bbox)
        self.assertEqual(len(parts), 2)
        for part in parts:
            for p in part:
                self.assertTrue(bb.in_bbox(p, bbox))
        self.assertEqual(parts[0][0], (1, 5))
        self.assertEqual(parts[1][-1], (2, 8))

    def test_output_has_lake_michigan(self):
        with open(OUT) as f:
            d = json.load(f)
        self.assertTrue(any(inside((-87.3, 42.5), r) for r in d["lakes"]))


if __name__ == "__main__":
    unittest.main()
