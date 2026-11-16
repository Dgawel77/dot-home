package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Random;

import dev.gawel.dothome.core.BoxSet;
import dev.gawel.dothome.core.DotScale;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.RadarScale;

/**
 * Draws the flat dotted radar map: lake texture, graticule, borders, highways with boxed route labels, place
 * labels and the echo halftone. Shared by the full-screen {@link RadarView} and the radar widget. Every dot is a
 * {@link SoftDot} at the shared {@link DotScale}: secondary texture dots are fractions of the dot radius and dot
 * spacings are multiples of the pitch. Not thread-safe (it owns a SoftDot and Paints): one per thread.
 */
final class RadarPainter {
    private final Context ctx;
    private final float u;          // display density (px per dp) the art is drawn at
    private final float dotR, pitch; // the shared dot scale at that density
    private final boolean region;
    private final float topPad, bottomPad; // labels stay out of these bands (px)
    private final SoftDot dot = new SoftDot(DotStyle.FG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    RadarPainter(Context ctx, float density, boolean region, float topDp, float bottomDp) {
        this.ctx = ctx;
        this.u = density;
        this.dotR = DotScale.radiusPx(density);
        this.pitch = DotScale.pitchPx(density);
        this.region = region;
        this.topPad = topDp * density;
        this.bottomPad = bottomDp * density;
        dot.prewarm(dotR).prewarm(dotR * 2.5f);
    }

    float pitch() { return pitch; }

    float dotRadius() { return dotR; }

    void dot(Canvas c, float x, float y, float r, float a) { dot.draw(c, x, y, r, a); }

    // ---- the full flat map: base, halftone of one frame, labels, location, readout (the widget's render) ----

    private void paintMap(Canvas c, int w, int h, GeoProjection p, Basemap b, RadarTiles.Frame frame) {
        drawBase(c, p, b, w, h);
        if (frame != null) {
            int cols = (int) Math.ceil(w / pitch), rows = (int) Math.ceil(h / pitch);
            double[] lons = lons(p, cols), lats = lats(p, rows);
            drawHalftone(c, sample(frame, lons, lats), cols, rows);
        }
        drawPlaces(c, p, b, w, h);
    }

    /** The widget's whole render. The location dot is static; readout may be null. */
    void paint(Canvas c, int w, int h, GeoProjection p, Basemap b, RadarTiles.Frame frame, String readout,
               double lat, double lon) {
        paintMap(c, w, h, p, b, frame);
        drawLocation(c, p.x(lon), p.y(lat));
        drawReadout(c, h, readout);
    }

    /** A solid location dot with a faint halo, no animation. */
    void drawLocation(Canvas c, float x, float y) {
        dot.draw(c, x, y, 2.5f * dotR, 1f);
        dot.draw(c, x, y, 4f * dotR, 0.25f);
    }

    /** Small Inter FG2 text, bottom left, on a PANEL pill so it reads over echoes. */
    void drawReadout(Canvas c, int h, String readout) {
        if (readout == null) return;
        text.setTypeface(Fonts.inter(ctx));
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(10 * u);
        float x = 12 * u, base = h - 12 * u;
        float tw = text.measureText(readout);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(DotStyle.PANEL);
        box.set(x - 6 * u, base - 14 * u, x + tw + 6 * u, base + 6 * u);
        c.drawRoundRect(box, 6 * u, 6 * u, fill);
        text.setColor(DotStyle.FG2);
        text.setAlpha(255);
        c.drawText(readout, x, base, text);
    }

    // ---- static layers -------------------------------------------------------------------------------------

    void drawBase(Canvas c, GeoProjection p, Basemap bm, int w, int h) {
        c.drawColor(DotStyle.BG);
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
        // land speckle first, so lake points are excluded from it. About one speckle per 4 pitch cells.
        Random rnd = new Random(region ? 9 : 5);
        int speckles = (int) Math.min(6000, w * (double) h / (4.0 * pitch * pitch));
        for (int i = 0; i < speckles; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h, a = 0.05f + rnd.nextFloat() * 0.1f;
            if (!inLake(px, py, bb, x, y)) dot.draw(c, x, y, 0.35f * dotR, a);
        }
        // lake stipple on the shared pitch grid, alternate rows offset by half a pitch
        int row = 0;
        for (float y = pitch * 0.5f; y < h; y += pitch, row++) {
            for (float x = pitch * 0.5f + (row % 2) * pitch * 0.5f; x < w; x += pitch) {
                if (inLake(px, py, bb, x, y)) dot.draw(c, x, y, 0.55f * dotR, 0.2f);
            }
        }
        // graticule: very faint dots, no labels
        double gstep = region ? 1.0 : 0.25;
        for (double la = Math.ceil(p.lat(h) / gstep) * gstep; la <= p.lat(0); la += gstep) {
            float y = p.y(la);
            for (float x = pitch; x < w; x += 2 * pitch) dot.draw(c, x, y, 0.4f * dotR, 0.10f);
        }
        for (double lo = Math.ceil(p.lon(0) / gstep) * gstep; lo <= p.lon(w); lo += gstep) {
            float x = p.x(lo);
            for (float y = pitch; y < h; y += 2 * pitch) dot.draw(c, x, y, 0.4f * dotR, 0.10f);
        }
        for (double[][] s : bm.states) poly(c, p, s, 1.5f * pitch, 0.65f * dotR, 0.38f, w, h);
        for (Basemap.Road r : bm.roads) poly(c, p, r.pts, pitch, 0.6f * dotR, 0.42f, w, h);
        for (double[][] s : bm.shore) poly(c, p, s, pitch, dotR, 0.85f, w, h);

        text.setTypeface(Fonts.interMedium(ctx));
        text.setColor(DotStyle.FG);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * u);
        for (Basemap.Label l : bm.regions) {
            if (!region && !l.name.startsWith("LAKE")) continue;
            float x = p.x(l.lon), y = p.y(l.lat);
            if (y < topPad || y > h - bottomPad || x < 0 || x > w) continue;
            text.setAlpha(Math.round(0.42f * 255));
            c.drawText(spaced(l.name), x, y, text);
        }
        text.setTextSize(9 * u);
        BoxSet drawn = new BoxSet();
        for (Basemap.Road r : bm.roads) {
            if (r.pts.length == 0) continue;
            double[] q = r.pts[r.pts.length / 2];
            float x = p.x(q[0]), y = p.y(q[1]);
            if (x < 16 * u || x > w - 16 * u || y < topPad || y > h - bottomPad) continue;
            float tw = text.measureText(r.ref) + 8 * u;
            if (!drawn.tryAdd(x - tw / 2, y - 7 * u, x + tw / 2, y + 6 * u)) continue; // a duplicate nearby
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(DotStyle.BG);
            fill.setAlpha(Math.round(0.95f * 255));
            c.drawRect(x - tw / 2, y - 7 * u, x + tw / 2, y + 6 * u, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1f, 0.8f * u));
            fill.setColor(DotStyle.FG);
            fill.setAlpha(Math.round(0.6f * 255));
            c.drawRect(x - tw / 2 + 0.5f * u, y - 6.5f * u, x + tw / 2 - 0.5f * u, y + 5.5f * u, fill);
            text.setAlpha(Math.round(0.8f * 255));
            c.drawText(r.ref, x, y + 3 * u, text);
        }
    }

    void drawPlaces(Canvas c, GeoProjection p, Basemap bm, int w, int h) {
        text.setTypeface(Fonts.inter(ctx));
        text.setColor(DotStyle.FG);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(11 * u);
        int maxRank = region ? 2 : 3;
        for (Basemap.Label l : bm.places) {
            if (l.rank > maxRank) continue;
            float x = p.x(l.lon), y = p.y(l.lat);
            if (x < 8 * u || x > w - 8 * u || y < topPad || y > h - bottomPad) continue;
            dot.draw(c, x, y, 1.3f * dotR, 0.75f);
            text.setAlpha(Math.round(0.62f * 255));
            c.drawText(l.name, x + 8 * u, y + 4 * u, text);
        }
    }

    // ---- echoes --------------------------------------------------------------------------------------------

    /** Longitudes of the pitch-grid column centres. */
    double[] lons(GeoProjection p, int cols) {
        double[] a = new double[cols];
        for (int i = 0; i < cols; i++) a[i] = p.lon(pitch * (i + 0.5f));
        return a;
    }

    /** Latitudes of the pitch-grid row centres. */
    double[] lats(GeoProjection p, int rows) {
        double[] a = new double[rows];
        for (int j = 0; j < rows; j++) a[j] = p.lat(pitch * (j + 0.5f));
        return a;
    }

    /** Echo intensity (0..1) of one frame on the pitch grid, row-major cols x rows. */
    static float[] sample(RadarTiles.Frame fr, double[] lons, double[] lats) {
        float[] cell = new float[lons.length * lats.length];
        for (int j = 0; j < lats.length; j++) {
            for (int i = 0; i < lons.length; i++) cell[j * lons.length + i] = RadarScale.intensity(fr.argbAt(lons[i], lats[j]));
        }
        return cell;
    }

    /** One FG soft dot per lit cell: radius from {@link Halftone}, so heavy echoes nearly touch. */
    void drawHalftone(Canvas c, float[] cell, int cols, int rows) {
        for (int j = 0; j < rows; j++) {
            float y = pitch * (j + 0.5f);
            for (int i = 0; i < cols; i++) {
                float in = cell[j * cols + i];
                if (in < 0.08f) continue;
                dot.draw(c, pitch * (i + 0.5f), y, Halftone.radius(in, pitch), 0.4f + 0.6f * in);
            }
        }
    }

    private static String spaced(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) b.append("  ");
            b.append(s.charAt(i));
        }
        return b.toString();
    }

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

    private void poly(Canvas c, GeoProjection p, double[][] pts, float step, float r, float a, int w, int h) {
        float m = pitch;
        for (int i = 0; i < pts.length - 1; i++) {
            float x1 = p.x(pts[i][0]), y1 = p.y(pts[i][1]), x2 = p.x(pts[i + 1][0]), y2 = p.y(pts[i + 1][1]);
            if ((x1 < -step && x2 < -step) || (x1 > w + step && x2 > w + step)
                || (y1 < -step && y2 < -step) || (y1 > h + step && y2 > h + step)) continue;
            float len = (float) Math.hypot(x2 - x1, y2 - y1);
            int n = Math.max(1, Math.round(len / step));
            for (int j = 0; j < n; j++) {
                float t = j / (float) n, xx = x1 + (x2 - x1) * t, yy = y1 + (y2 - y1) * t;
                if (xx > -m && xx < w + m && yy > -m && yy < h + m) dot.draw(c, xx, yy, r, a);
            }
        }
    }
}
