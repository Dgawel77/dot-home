package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.gawel.dothome.core.ClearZones;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Lattice;

/**
 * The flat dotted map with the last hour of NEXRAD echoes rolling in as halftone. Static layers are drawn once per
 * view into bitmaps on a worker thread; the storm is a precomputed dot list per frame, drawn into a cached layer
 * only when the frame changes. The activity's header, credits and this view's own readout and legend are clear zones.
 */
final class RadarView extends View {
    private static final String TAG = "DotHome";
    private static final int FRAMES = 12;
    private static final float FRAME_S = 0.5f, HOLD_S = 1.5f; // 12 x 0.5 = 6 s, then a 1.5 s hold on NOW

    private static final float TOP_PAD_DP = 64, BOTTOM_PAD_DP = 100; // labels keep clear of the header and readout
    private static final float CLEAR_EDGE_DP = 16;
    private static final int SCRUB_DOTS = 60;

    private final float u; // display density
    private final RadarPainter ui; // UI-thread painter (halftone, location pulse, readout dots)
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private double lat = 41.88, lon = -87.63;
    private boolean haveLocation, region, running;
    private volatile int gen;

    private GeoProjection proj;
    private Bitmap base, overlay;
    private float[][] grids; // per frame: storm dots (cx, cy, diameter) in lattice cells
    private Bitmap layer; // the current frame's halftone, redrawn only when the frame changes
    private Canvas layerCanvas;
    private float[] layerSrc;
    private int[] minutes;
    private float[][] overlays = new float[0][]; // header titles, header controls, credits: x, y, w, h in view px
    private boolean loading, unavailable;
    private long t0;

    RadarView(Context ctx) {
        super(ctx);
        u = ctx.getResources().getDisplayMetrics().density;
        ui = new RadarPainter(ctx, u, false, TOP_PAD_DP, BOTTOM_PAD_DP);
        setBackgroundColor(DotStyle.GROUND);
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

    /** The activity's overlaid blocks (header titles, header controls, credits) in view px; null entries are skipped. */
    void setOverlays(float[]... rects) {
        List<float[]> keep = new ArrayList<>();
        for (float[] r : rects) if (r != null && r[2] > 0 && r[3] > 0) keep.add(r);
        float[][] next = keep.toArray(new float[0][]);
        if (Arrays.deepEquals(next, overlays)) return;
        overlays = next;
        rebuild();
    }

    /** Top of the credits block (or the view bottom): the readout and legend sit above it. */
    private float bottomY(int h, float[][] ov) {
        float b = h;
        for (int i = 2; i < ov.length; i++) b = Math.min(b, ov[i][1]);
        return b;
    }

    /** Where the readout and legend sit, as clear-zone rects, for a view of this size. */
    private ClearZones zonesFor(int w, int h, float[][] ov) {
        List<float[]> rects = new ArrayList<>(Arrays.asList(ov));
        float b = bottomY(h, ov), sy = scrubY(b);
        rects.add(new float[] {0, sy - 56 * u, 280 * u, b - (sy - 56 * u)});
        rects.add(new float[] {w - 176 * u, sy - 32 * u, 176 * u, 48 * u});
        return ClearZones.of(rects, CLEAR_EDGE_DP * u, Lattice.stepPx(Lattice.Step.S4, u));
    }

    private float scrubY(float bottom) {
        float s = Lattice.stepPx(Lattice.Step.S4, u);
        return (float) Math.floor((bottom - 24 * u) / s) * s + s / 2f;
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

    /** Cancels any in-flight build and tile download (also on detach); the view is reusable via setPlace. */
    void release() {
        running = false;
        gen++;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rebuild(); // no-op until sized; recovers a view that was detached (and cancelled) mid-load
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    double lat() { return lat; }

    double lon() { return lon; }

    private void rebuild() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !haveLocation) return;
        final int g = ++gen;
        final boolean reg = region;
        final double la = lat, lo = lon;
        final ClearZones zones = zonesFor(w, h, overlays);
        loading = true;
        unavailable = false;
        grids = null;
        new Thread(() -> build(g, w, h, reg, la, lo, zones), "dothome-radar").start();
    }

    // ---- worker thread ------------------------------------------------------------------------------------

    private void build(int g, int w, int h, boolean reg, double la, double lo, ClearZones zones) {
        try {
            Basemap bm = Basemap.get(getContext());
            GeoProjection p = reg ? GeoProjection.region(la, lo, w, h) : GeoProjection.area(la, lo, w, h);
            RadarPainter rp = new RadarPainter(getContext(), u, reg, TOP_PAD_DP, BOTTOM_PAD_DP);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rp.setZones(zones);
            rp.drawBase(new Canvas(b), p, bm, w, h);
            Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rp.drawPlaces(new Canvas(o), p, bm, w, h, la, lo);
            post(() -> {
                if (g != gen) return;
                proj = p;
                ui.setZones(zones);
                layerSrc = null;
                base = b;
                overlay = o;
                invalidate();
            });

            List<RadarTiles.Frame> frames = RadarTiles.load(p, reg ? 6 : 9, () -> g != gen);
            if (g != gen) return;
            float gp = rp.step(); // the S4 lattice
            int c = (int) Math.ceil(w / gp), r = (int) Math.ceil(h / gp);
            double[] lons = rp.lons(p, c), lats = rp.lats(p, r);
            float[][] gr = new float[frames.size()][];
            int[] mins = new int[frames.size()];
            for (int f = 0; f < gr.length; f++) {
                mins[f] = frames.get(f).minutesAgo;
                gr[f] = RadarPainter.storm(frames.get(f), lons, lats);
            }
            final boolean none = gr.length == 0;
            post(() -> {
                if (g != gen) return;
                grids = gr;
                minutes = mins;
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

    // ---- UI thread ----------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(DotStyle.GROUND);
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        if (base != null) c.drawBitmap(base, 0, 0, bmpPaint);

        int frame = -1;
        float[][] gr = grids;
        if (gr != null && gr.length > 0) {
            float cycle = gr.length * FRAME_S + HOLD_S;
            frame = Math.min(gr.length - 1, (int) ((t % cycle) / FRAME_S));
            if (layer == null || layer.getWidth() != w || layer.getHeight() != h) {
                layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                layerCanvas = new Canvas(layer);
                layerSrc = null;
            }
            if (layerSrc != gr[frame]) {
                layer.eraseColor(0);
                ui.drawStorm(layerCanvas, gr[frame]);
                layerSrc = gr[frame];
            }
            c.drawBitmap(layer, 0, 0, bmpPaint);
        }
        if (overlay != null) c.drawBitmap(overlay, 0, 0, bmpPaint);

        if (proj != null) {
            float yx = proj.x(lon), yy = proj.y(lat);
            ui.drawLocation(c, yx, yy);
            ui.drawPing(c, yx, yy, (t % 2f) / 2f); // the 2 s signal tempo
        }
        drawReadout(c, w, h, frame, gr);
        if (running) postInvalidateOnAnimation();
    }

    private void drawReadout(Canvas c, int w, int h, int frame, float[][] gr) {
        float s = Lattice.stepPx(Lattice.Step.S4, u);
        float x0 = Lattice.snap(16 * u, u), sy = scrubY(bottomY(h, overlays));
        // scrubber: a dot row, the head heavy in L100
        float reg = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.REGULAR, u);
        for (int i = 0; i < SCRUB_DOTS; i++) ui.dotAt(c, x0 + (i + 0.5f) * s, sy, reg, DotStyle.L46);
        float k = frame < 0 || gr.length < 2 ? 1f : frame / (float) (gr.length - 1);
        ui.dotAt(c, x0 + (Math.round(k * (SCRUB_DOTS - 1)) + 0.5f) * s, sy,
            Lattice.radiusPx(Lattice.Step.S8, Lattice.Weight.HEAVY, u), DotStyle.L100);
        if (frame >= 0) {
            int m = minutes[frame];
            float oy = Lattice.snap(sy - 40 * u, u);
            if (m == 0) {
                ui.label(c, "NOW", x0, oy + 7 * s, Paint.Align.LEFT, DotStyle.L65);
            } else {
                float nw = ui.matrix(c, "\u2212" + m, x0, oy, DotStyle.L65, DotStyle.L10);
                ui.label(c, "MIN", x0 + nw + 8 * u, oy + 7 * s, Paint.Align.LEFT, DotStyle.L65);
            }
        }
        // legend, bottom right: the storm's own light / medium / heavy dot sizes between mono labels
        float xr = w - Lattice.snap(16 * u, u);
        ui.label(c, "HEAVY", xr, sy + 4 * u, Paint.Align.RIGHT, DotStyle.L46);
        float x = ((float) Math.floor((xr - ui.labelWidth("HEAVY") - 12 * u) / s) + 0.5f) * s;
        float[] levels = {1f, 0.6f, 0.25f};
        for (float b : levels) {
            ui.dotAt(c, x, sy, ui.stormDiameter(b) / 2f, DotStyle.L65);
            x -= 2 * s;
        }
        ui.label(c, "LIGHT", x - 4 * u, sy + 4 * u, Paint.Align.RIGHT, DotStyle.L46);
        String status = unavailable ? "Radar unavailable" : loading ? "Loading radar" : null;
        if (status != null) ui.label(c, status, xr, sy - 24 * u, Paint.Align.RIGHT, DotStyle.L46);
    }
}
