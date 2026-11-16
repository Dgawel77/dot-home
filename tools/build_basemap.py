"""Build app/src/main/assets/basemap.json from Natural Earth 1:10m GeoJSON.

Run from the repo root:  python3 -I tools/build_basemap.py
Source: nvkelso/natural-earth-vector (public domain), pinned to commit
ca96624a56bd078437bca8184e78163e5039ad19 (NE_COMMIT) for reproducible builds. Files are cached in
tools/cache/ and treated as untrusted data (only ever json.load-ed).

ne_10m_roads fields (inspected): feature `level` == "Interstate" marks US
interstates, and the route number is in the `name` property (e.g. "90", "294";
`prefix` is "I" or empty, `label` is empty). Refs are normalised to bare digits.
"""
import json
import math
import os
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "basemap.json")
NE_COMMIT = "ca96624a56bd078437bca8184e78163e5039ad19"  # pinned nvkelso/natural-earth-vector commit
BASE_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/%s/geojson/" % NE_COMMIT
FILES = ["ne_10m_lakes", "ne_10m_admin_1_states_provinces_lines",
         "ne_10m_roads", "ne_10m_populated_places_simple"]

BBOX = (-95.0, 38.0, -83.0, 47.5)  # lon0, lat0, lon1, lat1
TOL = 0.002

# Suburbs/places from the mockup that Natural Earth lacks (name, lat, lon).
EXTRA_PLACES = [
    ("O'Hare", 41.98, -87.90), ("Midway", 41.79, -87.75), ("Evanston", 42.05, -87.69),
    ("Skokie", 42.03, -87.74), ("Oak Park", 41.885, -87.79), ("Schaumburg", 42.03, -88.08),
    ("Oak Brook", 41.83, -87.93), ("Naperville", 41.75, -88.15), ("Aurora", 41.76, -88.32),
    ("Elgin", 42.04, -88.28), ("Joliet", 41.53, -88.08), ("Orland Park", 41.63, -87.85),
    ("Hammond", 41.58, -87.50), ("Gary", 41.60, -87.34), ("Arlington Hts", 42.09, -87.98),
]
# Region-view cities from the mockup, added only if missing from Natural Earth.
MOCKUP_CITIES = [
    ("Chicago", 41.88, -87.63), ("Milwaukee", 43.04, -87.91), ("Madison", 43.07, -89.40),
    ("Rockford", 42.27, -89.09), ("Quad Cities", 41.52, -90.58), ("Iowa City", 41.66, -91.53),
    ("Peoria", 40.69, -89.59), ("Bloomington", 40.48, -88.99), ("Champaign", 40.12, -88.24),
    ("South Bend", 41.68, -86.25), ("Dubuque", 42.50, -90.66), ("Indianapolis", 39.77, -86.16),
]
REGIONS = [
    ("LAKE MICHIGAN", 43.10, -87.05), ("ILLINOIS", 40.95, -89.30), ("WISCONSIN", 43.75, -89.60),
    ("IOWA", 41.15, -91.70), ("INDIANA", 40.60, -86.60), ("MICHIGAN", 42.45, -85.85),
]


def in_bbox(pt, bbox=BBOX):
    return bbox[0] <= pt[0] <= bbox[2] and bbox[1] <= pt[1] <= bbox[3]


def _seg_dist(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    if dx == 0 and dy == 0:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)


def simplify(pts, tol):
    """Douglas-Peucker; keeps both endpoints (iterative, no recursion limit)."""
    n = len(pts)
    if n < 3:
        return list(pts)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = -1.0, -1
        for k in range(i + 1, j):
            d = _seg_dist(pts[k], pts[i], pts[j])
            if d > best:
                best, idx = d, k
        if best > tol:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    return [p for p, k in zip(pts, keep) if k]


def _clip_seg(a, b, bbox):
    """Liang-Barsky: returns (t0, t1) of the part of a->b inside bbox, or None."""
    x0, y0, x1, y1 = bbox
    dx, dy = b[0] - a[0], b[1] - a[1]
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, a[0] - x0), (dx, x1 - a[0]), (-dy, a[1] - y0), (dy, y1 - a[1])):
        if p == 0:
            if q < 0:
                return None
        else:
            r = q / p
            if p < 0:
                if r > t1:
                    return None
                t0 = max(t0, r)
            else:
                if r < t0:
                    return None
                t1 = min(t1, r)
    return t0, t1


def clip_line(pts, bbox=BBOX):
    """Clip a polyline to bbox; returns a list of polylines."""
    out, cur = [], []
    for a, b in zip(pts, pts[1:]):
        r = _clip_seg(a, b, bbox)
        if r is None:
            if len(cur) > 1:
                out.append(cur)
            cur = []
            continue
        t0, t1 = r
        pa = a if t0 == 0 else (a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0)
        pb = b if t1 == 1 else (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)
        if not cur:
            cur = [pa]
        elif cur[-1] != pa:
            out.append(cur)
            cur = [pa]
        cur.append(pb)
        if t1 < 1:
            out.append(cur)
            cur = []
    if len(cur) > 1:
        out.append(cur)
    return [c for c in out if len(c) > 1]


def clip_ring(ring, bbox=BBOX):
    """Sutherland-Hodgman polygon clip of a closed ring to bbox."""
    x0, y0, x1, y1 = bbox
    edges = [
        (lambda p: p[0] >= x0, lambda a, b: (x0, a[1] + (b[1] - a[1]) * (x0 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= x1, lambda a, b: (x1, a[1] + (b[1] - a[1]) * (x1 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= y0, lambda a, b: (a[0] + (b[0] - a[0]) * (y0 - a[1]) / (b[1] - a[1]), y0)),
        (lambda p: p[1] <= y1, lambda a, b: (a[0] + (b[0] - a[0]) * (y1 - a[1]) / (b[1] - a[1]), y1)),
    ]
    pts = list(ring[:-1] if ring and ring[0] == ring[-1] else ring)
    for ins, inter in edges:
        if not pts:
            break
        res = []
        prev = pts[-1]
        for cur in pts:
            if ins(cur):
                if not ins(prev):
                    res.append(inter(prev, cur))
                res.append(cur)
            elif ins(prev):
                res.append(inter(prev, cur))
            prev = cur
        pts = res
    if len(pts) < 3:
        return []
    return pts + [pts[0]]


def _rnd(pts):
    return [[round(x, 4), round(y, 4)] for x, y in pts]


def _load(name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name + ".geojson")
    if not os.path.exists(path):
        part = path + ".part"
        try:
            urllib.request.urlretrieve(BASE_URL + name + ".geojson", part)
            with open(part) as f:
                json.load(f)  # sanity check: complete, valid JSON
            os.replace(part, path)
        except (OSError, ValueError) as e:
            if os.path.exists(part):
                os.remove(part)
            raise SystemExit("failed to download %s: %s" % (name, e))
    with open(path) as f:
        return json.load(f)["features"]


def _lines(geom):
    if geom["type"] == "LineString":
        return [geom["coordinates"]]
    if geom["type"] == "MultiLineString":
        return geom["coordinates"]
    return []


def _rings(geom):
    if geom["type"] == "Polygon":
        polys = [geom["coordinates"]]
    elif geom["type"] == "MultiPolygon":
        polys = geom["coordinates"]
    else:
        return []
    return [r for poly in polys for r in poly]


def _simp_clip(line):
    return [simplify([tuple(p) for p in part], TOL) for part in clip_line([tuple(p) for p in line])]


def build():
    lakes, shore = [], []
    for f in _load("ne_10m_lakes"):
        for ring in _rings(f["geometry"]):
            ring = [tuple(p[:2]) for p in ring]
            clipped = clip_ring(ring)
            if clipped:
                simp = simplify(clipped, TOL)
                if len(simp) >= 4:
                    lakes.append(_rnd(simp))
            for part in clip_line(ring):
                s = simplify(part, TOL)
                if len(s) > 1:
                    shore.append(_rnd(s))

    states = []
    for f in _load("ne_10m_admin_1_states_provinces_lines"):
        if f["properties"].get("ADM0_A3") != "USA":
            continue
        for line in _lines(f["geometry"]):
            states.extend(_rnd(p) for p in _simp_clip(line) if len(p) > 1)

    roads = []
    for f in _load("ne_10m_roads"):
        p = f["properties"]
        if p.get("sov_a3") != "USA" or p.get("level") != "Interstate":
            continue
        ref = (p.get("name") or "").replace("I-", "").strip()
        if not ref.isdigit():
            continue
        for line in _lines(f["geometry"]):
            for part in _simp_clip(line):
                if len(part) > 1:
                    roads.append({"ref": ref, "pts": _rnd(part)})

    places, seen = [], set()
    for f in _load("ne_10m_populated_places_simple"):
        p = f["properties"]
        pt = (p["longitude"], p["latitude"])
        pop = p.get("pop_max") or 0
        if not in_bbox(pt) or pop < 50000 or p.get("adm0_a3") not in ("USA", "CAN"):
            continue
        rank = 1 if pop >= 1000000 else 2 if pop >= 300000 else 3 if pop >= 100000 else 4
        places.append({"name": p["name"], "lat": round(pt[1], 4), "lon": round(pt[0], 4), "rank": rank})
        seen.add(p["name"])
    for name, lat, lon in MOCKUP_CITIES + EXTRA_PLACES:
        if name not in seen:
            places.append({"name": name, "lat": lat, "lon": lon, "rank": 3 if (name, lat, lon) in MOCKUP_CITIES else 4})
            seen.add(name)
    places.sort(key=lambda q: (q["rank"], q["name"]))

    regions = [{"name": n, "lat": la, "lon": lo} for n, la, lo in REGIONS]
    return {"lakes": lakes, "shore": shore, "states": states, "roads": roads,
            "places": places, "regions": regions}


def main():
    data = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(data, f, separators=(",", ":"))
    print("wrote", os.path.normpath(OUT), os.path.getsize(OUT), "bytes")
    print("roads:", sorted({r["ref"] for r in data["roads"]}, key=int))
    print("places:", len(data["places"]))


if __name__ == "__main__":
    main()
