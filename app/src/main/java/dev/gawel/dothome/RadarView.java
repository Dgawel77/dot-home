package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.List;
import java.util.Random;

import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.RadarScale;

/**
 * The flat dotted map (the mockup's Radar board) with the last hour of NEXRAD echoes rolling in as halftone.
 * Static layers are drawn once per view into bitmaps on a worker thread; the storm is a precomputed intensity
 * grid per frame, so each animation frame is just bitmap blits plus a few thousand dots.
 */
final class RadarView extends View {
    private static final String TAG = "DotHome";
    private static final int FRAMES = 12;
    private static final float FRAME_S = 0.5f, HOLD_S = 1.5f; // 12 x 0.5 = 6 s, then a 1.5 s hold on NOW

    private final float u; // one mockup px
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();

    private double lat = 41.88, lon = -87.63;
    private boolean haveLocation, region, running;
    private volatile int gen;

    private GeoProjection proj;
    private Bitmap base, overlay;
    private float[][] grids;
    private int[] minutes;
    private int cols, rows;
    private float pitch;
    private boolean loading, unavailable;
    private long t0;

    RadarView(Context ctx) {
        super(ctx);
        u = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
    }

    boolean isRegion() { return region; }

    void setRegion(boolean region) {
        if (this.region == region) return;
        this.region = region;
        rebuild();
    }

    void setPlace(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        haveLocation = true;
        rebuild();
    }

    void start() {
        running = true;
        t0 = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    void stop() { running = false; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild();
    }

    @Override
    protected void onDetachedFromWindow() {
        gen++; // abandons any in-flight build
        super.onDetachedFromWindow();
    }

    private void rebuild() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !haveLocation) return;
        final int g = ++gen;
        final boolean reg = region;
        final double la = lat, lo = lon;
        loading = true;
        unavailable = false;
        grids = null;
        new Thread(() -> build(g, w, h, reg, la, lo), "dothome-radar").start();
    }

    // ---- worker thread ------------------------------------------------------------------------------------

    private void build(int g, int w, int h, boolean reg, double la, double lo) {
        try {
            Basemap bm = Basemap.get(getContext());
            GeoProjection p = reg ? GeoProjection.region(la, lo, w, h) : GeoProjection.area(la, lo, w, h);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            drawBase(new Canvas(b), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), bm, p, w, h, reg);
            Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            drawPlaces(new Canvas(o), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), bm, p, w, h, reg);
            post(() -> {
                if (g != gen) return;
                proj = p;
                base = b;
                overlay = o;
                invalidate();
            });

            float gp = (reg ? 7f : 8f) * u;
            List<RadarTiles.Frame> frames = RadarTiles.load(p, reg ? 6 : 9);
            if (g != gen) return;
            int c = (int) Math.ceil(w / gp), r = (int) Math.ceil(h / gp);
            double[] lons = new double[c], lats = new double[r];
            for (int i = 0; i < c; i++) lons[i] = p.lon(gp * (i + 0.5f));
            for (int j = 0; j < r; j++) lats[j] = p.lat(gp * (j + 0.5f));
            float[][] gr = new float[frames.size()][];
            int[] mins = new int[frames.size()];
            for (int f = 0; f < gr.length; f++) {
                RadarTiles.Frame fr = frames.get(f);
                mins[f] = fr.minutesAgo;
                float[] cell = new float[c * r];
                for (int j = 0; j < r; j++) {
                    for (int i = 0; i < c; i++) cell[j * c + i] = RadarScale.intensity(fr.argbAt(lons[i], lats[j]));
                }
                gr[f] = cell;
            }
            final boolean none = gr.length == 0;
            post(() -> {
                if (g != gen) return;
                grids = gr;
                minutes = mins;
                cols = c;
                rows = r;
                pitch = gp;
                loading = false;
                unavailable = none;
                invalidate();
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar build failed: " + e);
            post(() -> {
                if (g != gen) return;
                loading = false;
                unavailable = true;
                invalidate();
            });
        }
    }

    private void dot(Canvas c, Paint pt, float x, float y, float r, float a) {
        if (a <= 0.01f || r <= 0.05f) return;
        pt.setStyle(Paint.Style.FILL);
        pt.setColor(Color.WHITE);
        pt.setAlpha(Math.round(Math.min(1f, a) * 255));
        c.drawCircle(x, y, r, pt);
    }

    private void poly(Canvas c, Paint pt, GeoProjection p, double[][] pts, float step, float r, float a, int w, int h) {
        for (int i = 0; i < pts.length - 1; i++) {
            float x1 = p.x(pts[i][0]), y1 = p.y(pts[i][1]), x2 = p.x(pts[i + 1][0]), y2 = p.y(pts[i + 1][1]);
            if ((x1 < -step && x2 < -step) || (x1 > w + step && x2 > w + step)
                || (y1 < -step && y2 < -step) || (y1 > h + step && y2 > h + step)) continue;
            float len = (float) Math.hypot(x2 - x1, y2 - y1);
            int n = Math.max(1, Math.round(len / step));
            for (int j = 0; j < n; j++) {
                float t = j / (float) n, xx = x1 + (x2 - x1) * t, yy = y1 + (y2 - y1) * t;
                if (xx > -4 * u && xx < w + 4 * u && yy > -4 * u && yy < h + 4 * u) dot(c, pt, xx, yy, r, a);
            }
        }
    }

    private void drawBase(Canvas c, Paint pt, Paint text, Basemap bm, GeoProjection p, int w, int h, boolean reg) {
        c.drawColor(Color.BLACK);
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
        // land speckle first, so lake points are excluded from it as in the mockup
        Random rnd = new Random(reg ? 9 : 5);
        for (int i = 0; i < 1400; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h, a = 0.05f + rnd.nextFloat() * 0.1f;
            if (!inLake(px, py, bb, x, y)) dot(c, pt, x, y, 0.65f * u, a);
        }
        int row = 0;
        for (float y = 3 * u; y < h; y += 5 * u, row++) {
            for (float x = 3 * u + (row % 2) * 2.5f * u; x < w; x += 5 * u) {
                if (inLake(px, py, bb, x, y)) dot(c, pt, x, y, 0.75f * u, 0.2f);
            }
        }
        // graticule: very faint dots, no labels
        double gstep = reg ? 1.0 : 0.25;
        for (double la = Math.ceil(p.lat(h) / gstep) * gstep; la <= p.lat(0); la += gstep) {
            float y = p.y(la);
            for (float x = 4 * u; x < w; x += 9 * u) dot(c, pt, x, y, 0.55f * u, 0.10f);
        }
        for (double lo = Math.ceil(p.lon(0) / gstep) * gstep; lo <= p.lon(w); lo += gstep) {
            float x = p.x(lo);
            for (float y = 4 * u; y < h; y += 9 * u) dot(c, pt, x, y, 0.55f * u, 0.10f);
        }
        for (double[][] s : bm.states) poly(c, pt, p, s, 6 * u, 0.9f * u, 0.38f, w, h);
        for (Basemap.Road r : bm.roads) poly(c, pt, p, r.pts, 3.4f * u, 0.8f * u, 0.42f, w, h);
        for (double[][] s : bm.shore) poly(c, pt, p, s, 3.2f * u, 1.2f * u, 0.85f, w, h);

        text.setTypeface(Fonts.interMedium(getContext()));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * u);
        for (Basemap.Label l : bm.regions) {
            if (!reg && !l.name.startsWith("LAKE")) continue;
            float x = p.x(l.lon), y = p.y(l.lat);
            if (y < 24 * u || y > h - 100 * u || x < 0 || x > w) continue;
            text.setAlpha(Math.round(0.42f * 255));
            c.drawText(spaced(l.name), x, y, text);
        }
        text.setTextSize(9 * u);
        for (Basemap.Road r : bm.roads) {
            if (r.pts.length == 0) continue;
            double[] q = r.pts[r.pts.length / 2];
            float x = p.x(q[0]), y = p.y(q[1]);
            if (x < 16 * u || x > w - 16 * u || y < 24 * u || y > h - 100 * u) continue;
            float tw = text.measureText(r.ref) + 8 * u;
            pt.setStyle(Paint.Style.FILL);
            pt.setColor(Color.BLACK);
            pt.setAlpha(Math.round(0.95f * 255));
            c.drawRect(x - tw / 2, y - 7 * u, x + tw / 2, y + 6 * u, pt);
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(1f, 0.8f * u));
            pt.setColor(Color.WHITE);
            pt.setAlpha(Math.round(0.6f * 255));
            c.drawRect(x - tw / 2 + 0.5f * u, y - 6.5f * u, x + tw / 2 - 0.5f * u, y + 5.5f * u, pt);
            text.setAlpha(Math.round(0.8f * 255));
            c.drawText(r.ref, x, y + 3 * u, text);
        }
    }

    private void drawPlaces(Canvas c, Paint pt, Paint text, Basemap bm, GeoProjection p, int w, int h, boolean reg) {
        text.setTypeface(Fonts.inter(getContext()));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(11 * u);
        int maxRank = reg ? 2 : 3;
        for (Basemap.Label l : bm.places) {
            if (l.rank > maxRank) continue;
            float x = p.x(l.lon), y = p.y(l.lat);
            if (x < 8 * u || x > w - 8 * u || y < 24 * u || y > h - 100 * u) continue;
            dot(c, pt, x, y, 1.8f * u, 0.75f);
            text.setAlpha(Math.round(0.62f * 255));
            c.drawText(l.name, x + 8 * u, y + 4 * u, text);
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

    // ---- UI thread ----------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        if (base != null) c.drawBitmap(base, 0, 0, bmpPaint);

        int frame = -1;
        float[][] gr = grids;
        if (gr != null && gr.length > 0) {
            float cycle = gr.length * FRAME_S + HOLD_S;
            frame = Math.min(gr.length - 1, (int) ((t % cycle) / FRAME_S));
            float[] cell = gr[frame];
            for (int j = 0; j < rows; j++) {
                float y = pitch * (j + 0.5f);
                for (int i = 0; i < cols; i++) {
                    float in = cell[j * cols + i];
                    if (in < 0.08f) continue;
                    dot(c, paint, pitch * (i + 0.5f), y, Halftone.radius(in, pitch), 0.4f + 0.6f * in);
                }
            }
        }
        if (overlay != null) c.drawBitmap(overlay, 0, 0, bmpPaint);

        if (proj != null) {
            float yx = proj.x(lon), yy = proj.y(lat);
            dot(c, paint, yx, yy, 3.4f * u, 1f);
            float pr = (t % 2f) / 2f, rad = (8 + pr * 22) * u;
            for (int i = 0; i < 20; i++) {
                double a = i / 20.0 * Math.PI * 2;
                dot(c, paint, yx + (float) Math.cos(a) * rad, yy + (float) Math.sin(a) * rad, 1.3f * u, (1 - pr) * 0.9f);
            }
        }
        drawReadout(c, w, h, frame, gr);
        if (running) postInvalidateOnAnimation();
    }

    private void drawReadout(Canvas c, int w, int h, int frame, float[][] gr) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setAlpha(Math.round(0.8f * 255));
        c.drawRect(0, h - 92 * u, 300 * u, h, paint);
        float lineY = h - 66 * u;
        for (float x = 28 * u; x <= 268 * u; x += 6 * u) dot(c, paint, x, lineY, 1f * u, 0.35f);
        float k = frame < 0 || gr.length < 2 ? 1f : frame / (float) (gr.length - 1);
        dot(c, paint, 28 * u + k * 240 * u, lineY, 3.2f * u, 1f);
        if (frame >= 0) {
            text.setTypeface(Fonts.doto(getContext()));
            text.setColor(Color.WHITE);
            text.setAlpha(255);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(24 * u);
            int m = minutes[frame];
            c.drawText(m == 0 ? "NOW" : "−" + m + " MIN", 28 * u, h - 28 * u, text);
        }
        // legend and status, bottom right
        text.setTypeface(Fonts.inter(getContext()));
        text.setTextSize(11 * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFA6A6A6);
        float ly = h - 26 * u;
        float x = w - 26 * u;
        text.getTextBounds("HEAVY", 0, 5, bounds);
        c.drawText("HEAVY", x, ly + 4 * u, text);
        x -= bounds.width() + 8 * u;
        dot(c, paint, x - 3.5f * u, ly, 3.5f * u, 1f);
        dot(c, paint, x - 3.5f * u - 14 * u, ly, 2.5f * u, 0.8f);
        dot(c, paint, x - 3.5f * u - 26 * u, ly, 1.5f * u, 0.6f);
        c.drawText("LIGHT", x - 36 * u, ly + 4 * u, text);
        String status = unavailable ? "Radar unavailable" : loading ? "Loading radar…" : null;
        if (status != null) {
            text.setColor(Color.WHITE);
            text.setAlpha(Math.round(0.7f * 255));
            c.drawText(status, w - 26 * u, ly - 24 * u, text);
        }
    }
}
