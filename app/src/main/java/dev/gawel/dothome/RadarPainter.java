package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import dev.gawel.dothome.core.BoxSet;
import dev.gawel.dothome.core.ClearZones;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.LabelPicker;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.RadarScale;

/**
 * Draws the dotted radar map on the 4 dp lattice anchored at the canvas origin: land speckle and lake texture at
 * light weight, borders, roads and shore as dot rows at regular weight, mono caps
 * labels, and the echo storm as a {@link Halftone} of the intensity grid. Shared by the full-screen
 * {@link RadarView} and the radar widget. Not thread-safe (it owns Paints and a bitset): one per thread.
 */
final class RadarPainter {
    private final float u;       // display density (px per dp) the art is drawn at
    private final float step;    // S4 step in px
    private final float rLight, rReg;
    private final boolean region;
    private final float topPad, bottomPad; // labels stay out of these bands (px)
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitSet seen = new BitSet();
    private ClearZones zones;
    private BoxSet boxes = new BoxSet(); // region label boxes drawn so far this frame

    /** Region view: the basemap has no population, so these major cities (in priority order) are the only ones labelled. */
    private static final String[] REGION_CITIES = {"Chicago", "Milwaukee", "Madison", "Rockford", "Indianapolis",
        "Grand Rapids", "Gary", "South Bend", "Peoria"};
    private static final int REGION_MAX_LABELS = 6;
    private static final float REGION_MIN_GAP_DP = 120f;

    RadarPainter(Context ctx, float density, boolean region, float topDp, float bottomDp) {
        this.u = density;
        this.step = Lattice.stepPx(Lattice.Step.S4, density);
        this.rLight = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.LIGHT, density);
        this.rReg = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.REGULAR, density);
        this.region = region;
        this.topPad = topDp * density;
        this.bottomPad = bottomDp * density;
        dot.setStyle(Paint.Style.FILL);
        text.setTypeface(Fonts.mono(ctx));
        text.setTextSize(11f * density);
        text.setLetterSpacing(1f / 11f);
    }

    float step() { return step; }

    /** Map dots are skipped inside these rects (x, y, w, h in px) and thinned toward them over edgePx. */
    void setClear(List<float[]> rects, float edgePx) {
        zones = rects == null || rects.isEmpty() ? null : ClearZones.of(rects, edgePx, step);
    }

    void setZones(ClearZones z) { zones = z; }

    private boolean keep(float x, float y) { return zones == null || zones.keep(x, y) >= 0.5f; }

    /** Centre of the lattice cell holding v. */
    private float cell(float v) { return ((float) Math.floor(v / step) + 0.5f) * step; }

    void dotAt(Canvas c, float x, float y, float r, int color) {
        dot.setColor(color);
        c.drawCircle(x, y, r, dot);
    }

    // ---- labels and matrix numerals -----------------------------------------------------------------------

    /** Mono label (11 dp, letter-spacing 1/11 em); s must already be upper case; y is the baseline. */
    void label(Canvas c, String s, float x, float y, Paint.Align align, int color) {
        text.setTextAlign(align);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    float labelWidth(String s) { return text.measureText(s); }

    /** True when the whole rect is clear of the zones (corners and centre). */
    private boolean keepRect(float x0, float y0, float x1, float y1) {
        return keep(x0, y0) && keep(x1, y0) && keep(x0, y1) && keep(x1, y1) && keep((x0 + x1) / 2f, (y0 + y1) / 2f);
    }

    /** Baseline that puts the cap height of a label centred on cy. */
    private float capBase(float cy) {
        Paint.FontMetrics m = text.getFontMetrics();
        return cy - (m.ascent + m.descent) / 2f;
    }

    /** Matrix-font cells (from {@link DotMatrixFont#cells}) at S4 regular, top-left (ox, oy) on the lattice; lit cells only. Returns the width in px. */
    float matrix(Canvas c, byte[] cells, float ox, float oy, int lit) {
        int cols = cells.length / DotMatrixFont.ROWS;
        for (int r = 0; r < DotMatrixFont.ROWS; r++) {
            for (int k = 0; k < cols; k++) {
                if (cells[r * cols + k] == 1) dotAt(c, ox + (k + 0.5f) * step, oy + (r + 0.5f) * step, rReg, lit);
            }
        }
        return cols * step;
    }

    // ---- the full flat map: base, storm of one frame, labels, location, readout (the widget's render) -------

    private void paintMap(Canvas c, int w, int h, GeoProjection p, Basemap b, RadarTiles.Frame frame, double lat, double lon) {
        drawBase(c, p, b, w, h);
        if (frame != null) {
            int cols = (int) Math.ceil(w / step), rows = (int) Math.ceil(h / step);
            drawStorm(c, filterStorm(storm(frame, lons(p, cols), lats(p, rows))));
        }
        drawPlaces(c, p, b, w, h, lat, lon);
    }

    /** The widget's whole render. The location dot is static; readout may be null. */
    void paint(Canvas c, int w, int h, GeoProjection p, Basemap b, RadarTiles.Frame frame, String readout,
               double lat, double lon) {
        paint(c, w, h, p, b, frame, readout, lat, lon, null);
    }

    /** As above; keepClear (x, y, w, h in px, may be null) is kept free of map dots and labels, like the readout. */
    void paint(Canvas c, int w, int h, GeoProjection p, Basemap b, RadarTiles.Frame frame, String readout,
               double lat, double lon, float[] keepClear) {
        List<float[]> rects = new ArrayList<>();
        if (keepClear != null) rects.add(keepClear);
        if (readout != null) {
            readout = readout.toUpperCase(Locale.ROOT);
            float rw = labelWidth(readout) + 2 * step;
            rects.add(new float[] {readoutX() - step, readoutBase(h) - 12 * u, rw, 18 * u});
        }
        setClear(rects, 16 * u);
        paintMap(c, w, h, p, b, frame, lat, lon);
        drawLocation(c, p.x(lon), p.y(lat));
        drawReadout(c, h, readout);
    }

    private float readoutX() { return Lattice.snap(16f * u, u); }

    private float readoutBase(int h) { return Lattice.snap(h - 16f * u, u); }

    /** The user's place: a heavy S8 dot in L100. */
    void drawLocation(Canvas c, float x, float y) {
        dotAt(c, cell(x), cell(y), Lattice.radiusPx(Lattice.Step.S8, Lattice.Weight.HEAVY, u), DotStyle.L100);
    }

    /** The ping ring at phase pr in 0..1 (period is the 2 s signal tempo): dots on the lattice, stepping down the greys. */
    void drawPing(Canvas c, float x, float y, float pr) {
        float rad = (8 + pr * 22) * u;
        int n = Math.max(12, Math.round((float) (2 * Math.PI * rad / (2 * step))));
        int color = pr < 0.4f ? DotStyle.L65 : pr < 0.75f ? DotStyle.L46 : DotStyle.L10;
        for (int i = 0; i < n; i++) {
            double a = i / (double) n * Math.PI * 2;
            dotAt(c, cell(x + (float) Math.cos(a) * rad), cell(y + (float) Math.sin(a) * rad), rReg, color);
        }
    }

    // ---- the widget's LOCAL · REGION toggle -----------------------------------------------------------------

    static final String TOGGLE_LOCAL = "LOCAL", TOGGLE_SEP = " \u00B7 ", TOGGLE_REGION = "REGION";

    /** Width in px of the toggle pill at this painter's density: the labels plus padX on each side. */
    float toggleWidth(float padX) {
        return labelWidth(TOGGLE_LOCAL) + labelWidth(TOGGLE_SEP) + labelWidth(TOGGLE_REGION) + 2 * padX;
    }

    /**
     * The radar widget's view toggle: an L10 capsule hPx tall at (x, y) with mono caps "LOCAL \u00B7 REGION", the
     * active view in L100 and the other (and the dot) in L46.
     */
    void drawToggle(Canvas c, float x, float y, float padX, float hPx, boolean region) {
        dot.setColor(DotStyle.L10);
        c.drawRoundRect(x, y, x + toggleWidth(padX), y + hPx, hPx / 2f, hPx / 2f, dot);
        float base = capBase(y + hPx / 2f), tx = x + padX;
        label(c, TOGGLE_LOCAL, tx, base, Paint.Align.LEFT, region ? DotStyle.L46 : DotStyle.L100);
        tx += labelWidth(TOGGLE_LOCAL);
        label(c, TOGGLE_SEP, tx, base, Paint.Align.LEFT, DotStyle.L46);
        tx += labelWidth(TOGGLE_SEP);
        label(c, TOGGLE_REGION, tx, base, Paint.Align.LEFT, region ? DotStyle.L100 : DotStyle.L46);
    }

    /** Mono label, bottom left. */
    void drawReadout(Canvas c, int h, String readout) {
        if (readout == null) return;
        label(c, readout.toUpperCase(Locale.ROOT), readoutX(), readoutBase(h), Paint.Align.LEFT, DotStyle.L65);
    }

    // ---- static layers -------------------------------------------------------------------------------------

    void drawBase(Canvas c, GeoProjection p, Basemap bm, int w, int h) {
        c.drawColor(DotStyle.GROUND);
        int cols = (int) Math.ceil(w / step), rows = (int) Math.ceil(h / step);
        // lake polygons in view pixels, with bounding boxes for a cheap reject
        int nl = bm.lakes.size();
        float[][] px = new float[nl][], py = new float[nl][], bb = new float[nl][];
        for (int k = 0; k < nl; k++) {
            double[][] pts = bm.lakes.get(k);
            px[k] = new float[pts.length];
            py[k] = new float[pts.length];
            float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE;
            for (int i = 0; i < pts.length; i++) {
                px[k][i] = p.x(pts[i][0]);
                py[k][i] = p.y(pts[i][1]);
                minx = Math.min(minx, px[k][i]); maxx = Math.max(maxx, px[k][i]);
                miny = Math.min(miny, py[k][i]); maxy = Math.max(maxy, py[k][i]);
            }
            bb[k] = new float[] {minx, miny, maxx, maxy};
        }
        // land speckle first, one light dot per about 20 lattice cells, never on a lake
        dot.setColor(DotStyle.L46);
        Random rnd = new Random(region ? 9 : 5);
        int speckles = (int) Math.min(6000, cols * (long) rows / 20);
        for (int i = 0; i < speckles; i++) {
            float x = (rnd.nextInt(cols) + 0.5f) * step, y = (rnd.nextInt(rows) + 0.5f) * step;
            if (keep(x, y) && !inLake(px, py, bb, x, y)) c.drawCircle(x, y, rLight, dot);
        }
        // lake texture: light dots on alternate lattice cells
        for (int j = 0; j < rows; j++) {
            float y = (j + 0.5f) * step;
            for (int i = j & 1; i < cols; i += 2) {
                float x = (i + 0.5f) * step;
                if (inLake(px, py, bb, x, y) && keep(x, y)) c.drawCircle(x, y, rLight, dot);
            }
        }
        // dot rows at regular weight: borders L46, roads and shore L65
        dot.setColor(DotStyle.L46);
        seen.clear();
        for (double[][] s : bm.states) row(c, p, s, cols, rows);
        dot.setColor(DotStyle.L65);
        seen.clear();
        for (Basemap.Road r : bm.roads) row(c, p, r.pts, cols, rows);
        seen.clear();
        for (double[][] s : bm.shore) row(c, p, s, cols, rows);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(DotStyle.L46);
        boxes = new BoxSet();
        for (Basemap.Label l : bm.regions) {
            if (!region && !l.name.startsWith("LAKE")) continue;
            float x = p.x(l.lon), y = p.y(l.lat);
            if (y < topPad || y > h - bottomPad || x < 0 || x > w) continue;
            String name = l.name.toUpperCase(Locale.ROOT);
            float hw = labelWidth(name) / 2f;
            if (!keepRect(x - hw, y - 9 * u, x + hw, y + 3 * u)) continue;
            if (!boxes.tryAdd(x - hw, y - 9 * u, x + hw, y + 3 * u)) continue;
            c.drawText(name, x, y, text);
        }
    }

    void drawPlaces(Canvas c, GeoProjection p, Basemap bm, int w, int h, double userLat, double userLon) {
        boolean haveUser = !Double.isNaN(userLat) && !Double.isNaN(userLon);
        float ux = haveUser ? p.x(userLon) : -1e9f, uy = haveUser ? p.y(userLat) : -1e9f;
        List<Basemap.Label> cand = new ArrayList<>();
        if (region) {
            // major cities in priority order, plus the user's own place wherever it falls in the list
            for (String city : REGION_CITIES) {
                for (Basemap.Label l : bm.places) {
                    if (l.name.equals(city)) { cand.add(l); break; }
                }
            }
            for (Basemap.Label l : bm.places) {
                if (!cand.contains(l) && Math.hypot(p.x(l.lon) - ux, p.y(l.lat) - uy) < 12 * u) cand.add(l);
            }
        } else {
            for (Basemap.Label l : bm.places) if (l.rank <= 3) cand.add(l);
        }
        // on-screen, clear of the zones
        List<Basemap.Label> vis = new ArrayList<>();
        for (Basemap.Label l : cand) {
            float x = p.x(l.lon), y = p.y(l.lat);
            if (x < 8 * u || x > w - 8 * u || y < topPad || y > h - bottomPad) continue;
            String name = l.name.toUpperCase(Locale.ROOT);
            if (!keepRect(x - step, y - 8 * u, x + 2 * step + labelWidth(name), y + 8 * u)) continue;
            vis.add(l);
        }
        boolean[] keep = new boolean[vis.size()];
        java.util.Arrays.fill(keep, true);
        if (region) {
            float[] xs = new float[vis.size()], ys = new float[vis.size()];
            boolean[] mine = new boolean[vis.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = p.x(vis.get(i).lon);
                ys[i] = p.y(vis.get(i).lat);
                mine[i] = Math.hypot(xs[i] - ux, ys[i] - uy) < 12 * u;
            }
            keep = LabelPicker.pick(xs, ys, mine, REGION_MAX_LABELS, REGION_MIN_GAP_DP * u);
        }
        for (int i = 0; i < vis.size(); i++) {
            if (!keep[i]) continue;
            Basemap.Label l = vis.get(i);
            float x = p.x(l.lon), y = p.y(l.lat);
            String name = l.name.toUpperCase(Locale.ROOT);
            boolean mine = Math.hypot(x - ux, y - uy) < 12 * u;
            float cx = cell(x), cy = cell(y);
            // in Region view a city label never lands on a state name (the user's own place always shows)
            if (region && !boxes.tryAdd(cx - step, cy - 8 * u, cx + 2 * step + labelWidth(name), cy + 8 * u) && !mine) continue;
            int color = mine ? DotStyle.L100 : DotStyle.L65;
            dotAt(c, cx, cy, rReg, color);
            label(c, name, cx + 2 * step, capBase(cy), Paint.Align.LEFT, color);
        }
    }

    // ---- storm ---------------------------------------------------------------------------------------------

    /** Longitudes of the lattice column centres. */
    double[] lons(GeoProjection p, int cols) {
        double[] a = new double[cols];
        for (int i = 0; i < cols; i++) a[i] = p.lon(step * (i + 0.5f));
        return a;
    }

    /** Latitudes of the lattice row centres. */
    double[] lats(GeoProjection p, int rows) {
        double[] a = new double[rows];
        for (int j = 0; j < rows; j++) a[j] = p.lat(step * (j + 0.5f));
        return a;
    }

    /**
     * One frame's storm as (cx, cy, diameter) triples in lattice-cell units: the calibrated echo intensity at each
     * S4 cell centre goes to {@link Halftone#dotsAbsolute}, with no contrast stretch.
     */
    static float[] storm(RadarTiles.Frame fr, double[] lons, double[] lats) {
        int cols = lons.length, rows = lats.length;
        float[] b = new float[cols * rows];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) b[j * cols + i] = RadarScale.intensity(fr.argbAt(lons[i], lats[j]));
        }
        return Halftone.dotsAbsolute(b, cols, rows, 1f);
    }

    /** Drops the dots the clear zones remove (run on the worker); input and output are in cell units. */
    float[] filterStorm(float[] dots) {
        if (zones == null) return dots;
        float[] out = new float[dots.length];
        int n = 0;
        for (int i = 0; i + 2 < dots.length; i += 3) {
            if (!keep(dots[i] * step, dots[i + 1] * step)) continue;
            out[n++] = dots[i];
            out[n++] = dots[i + 1];
            out[n++] = dots[i + 2];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The (already filtered) storm in L65 dots, scaled from cell units to px. */
    void drawStorm(Canvas c, float[] dots) {
        dot.setColor(DotStyle.L65);
        for (int i = 0; i + 2 < dots.length; i += 3) c.drawCircle(dots[i] * step, dots[i + 1] * step, dots[i + 2] * step / 2f, dot);
    }

    /** Dot diameter in px of a legend sample at this brightness (Halftone's rule). */
    float stormDiameter(float brightness) { return Halftone.diameter(brightness, step); }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static boolean inLake(float[][] px, float[][] py, float[][] bb, float x, float y) {
        for (int k = 0; k < px.length; k++) {
            float[] b = bb[k];
            if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) continue;
            boolean in = false;
            float[] xs = px[k], ys = py[k];
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
            }
            if (in) return true;
        }
        return false;
    }

    /** A polyline as a dot row: sampled every S4 step, each point snapped to its lattice cell, a cell drawn once per pass. */
    private void row(Canvas c, GeoProjection p, double[][] pts, int cols, int rows) {
        float w = cols * step, h = rows * step;
        for (int i = 0; i < pts.length - 1; i++) {
            float x1 = p.x(pts[i][0]), y1 = p.y(pts[i][1]), x2 = p.x(pts[i + 1][0]), y2 = p.y(pts[i + 1][1]);
            if ((x1 < -step && x2 < -step) || (x1 > w + step && x2 > w + step)
                || (y1 < -step && y2 < -step) || (y1 > h + step && y2 > h + step)) continue;
            float len = (float) Math.hypot(x2 - x1, y2 - y1);
            int n = Math.max(1, (int) Math.ceil(len / step));
            for (int j = 0; j <= n; j++) {
                float t = j / (float) n;
                int ci = (int) Math.floor((x1 + (x2 - x1) * t) / step), cj = (int) Math.floor((y1 + (y2 - y1) * t) / step);
                if (ci < 0 || cj < 0 || ci >= cols || cj >= rows) continue;
                int k = cj * cols + ci;
                if (seen.get(k)) continue;
                seen.set(k);
                float cx = (ci + 0.5f) * step, cy = (cj + 0.5f) * step;
                if (keep(cx, cy)) c.drawCircle(cx, cy, rReg, dot);
            }
        }
    }
}
