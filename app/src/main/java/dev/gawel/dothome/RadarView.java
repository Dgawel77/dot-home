package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.List;
import java.util.Random;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Halftone;

/**
 * The flat dotted map (the mockup's Radar board) with the last hour of NEXRAD echoes rolling in as halftone.
 * Static layers are drawn once per view into bitmaps on a worker thread; the storm is a precomputed intensity
 * grid per frame, so each animation frame is just bitmap blits plus a few thousand dots.
 */
final class RadarView extends View {
    private static final String TAG = "DotHome";
    private static final int FRAMES = 12;
    private static final float FRAME_S = 0.5f, HOLD_S = 1.5f; // 12 x 0.5 = 6 s, then a 1.5 s hold on NOW

    private static final float TOP_PAD_DP = 24, BOTTOM_PAD_DP = 100; // labels keep clear of the readout panel

    private final float u; // display density
    private final RadarPainter ui; // UI-thread painter (halftone, location pulse, readout dots)
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
    private boolean loading, unavailable;
    private long t0;

    RadarView(Context ctx) {
        super(ctx);
        u = ctx.getResources().getDisplayMetrics().density;
        ui = new RadarPainter(ctx, u, false, TOP_PAD_DP, BOTTOM_PAD_DP);
        setBackgroundColor(DotStyle.BG);
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
            RadarPainter rp = new RadarPainter(getContext(), u, reg, TOP_PAD_DP, BOTTOM_PAD_DP);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rp.drawBase(new Canvas(b), p, bm, w, h);
            Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rp.drawPlaces(new Canvas(o), p, bm, w, h);
            post(() -> {
                if (g != gen) return;
                proj = p;
                base = b;
                overlay = o;
                invalidate();
            });

            List<RadarTiles.Frame> frames = RadarTiles.load(p, reg ? 6 : 9, () -> g != gen);
            if (g != gen) return;
            float gp = rp.pitch(); // the shared dot pitch
            int c = (int) Math.ceil(w / gp), r = (int) Math.ceil(h / gp);
            double[] lons = rp.lons(p, c), lats = rp.lats(p, r);
            float[][] gr = new float[frames.size()][];
            int[] mins = new int[frames.size()];
            for (int f = 0; f < gr.length; f++) {
                mins[f] = frames.get(f).minutesAgo;
                gr[f] = RadarPainter.sample(frames.get(f), lons, lats);
            }
            final boolean none = gr.length == 0;
            post(() -> {
                if (g != gen) return;
                grids = gr;
                minutes = mins;
                cols = c;
                rows = r;
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
        c.drawColor(DotStyle.BG);
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        if (base != null) c.drawBitmap(base, 0, 0, bmpPaint);

        int frame = -1;
        float[][] gr = grids;
        if (gr != null && gr.length > 0) {
            float cycle = gr.length * FRAME_S + HOLD_S;
            frame = Math.min(gr.length - 1, (int) ((t % cycle) / FRAME_S));
            ui.drawHalftone(c, gr[frame], cols, rows);
        }
        if (overlay != null) c.drawBitmap(overlay, 0, 0, bmpPaint);

        if (proj != null) {
            float yx = proj.x(lon), yy = proj.y(lat), r = ui.dotRadius();
            ui.dot(c, yx, yy, 2.5f * r, 1f);
            float pr = (t % 2f) / 2f, rad = (8 + pr * 22) * u;
            for (int i = 0; i < 20; i++) {
                double a = i / 20.0 * Math.PI * 2;
                ui.dot(c, yx + (float) Math.cos(a) * rad, yy + (float) Math.sin(a) * rad, r, (1 - pr) * 0.9f);
            }
        }
        drawReadout(c, w, h, frame, gr);
        if (running) postInvalidateOnAnimation();
    }

    private void drawReadout(Canvas c, int w, int h, int frame, float[][] gr) {
        float r = ui.dotRadius(), pitch = ui.pitch();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(DotStyle.BG);
        paint.setAlpha(Math.round(0.8f * 255));
        c.drawRect(0, h - 92 * u, 300 * u, h, paint);
        float lineY = h - 66 * u;
        for (float x = 28 * u; x <= 268 * u; x += pitch) ui.dot(c, x, lineY, 0.5f * r, 0.35f);
        float k = frame < 0 || gr.length < 2 ? 1f : frame / (float) (gr.length - 1);
        ui.dot(c, 28 * u + k * 240 * u, lineY, 2.4f * r, 1f);
        if (frame >= 0) {
            text.setTypeface(Fonts.interMedium(getContext()));
            text.setColor(DotStyle.FG);
            text.setAlpha(255);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(24 * u);
            int m = minutes[frame];
            c.drawText(m == 0 ? "NOW" : "−" + m + " MIN", 28 * u, h - 28 * u, text);
        }
        // legend and status, bottom right; the legend dots are the halftone's own light / medium / heavy sizes
        text.setTypeface(Fonts.inter(getContext()));
        text.setTextSize(11 * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(DotStyle.FG2);
        float ly = h - 26 * u;
        float x = w - 26 * u;
        float heavy = Halftone.radius(1f, pitch), mid = Halftone.radius(0.6f, pitch), light = Halftone.radius(0.25f, pitch);
        text.getTextBounds("HEAVY", 0, 5, bounds);
        c.drawText("HEAVY", x, ly + 4 * u, text);
        x -= bounds.width() + 8 * u;
        ui.dot(c, x - heavy, ly, heavy, 1f);
        ui.dot(c, x - heavy - pitch * 1.5f, ly, mid, 0.8f);
        ui.dot(c, x - heavy - pitch * 3f, ly, light, 0.6f);
        c.drawText("LIGHT", x - heavy - pitch * 3f - light - 8 * u, ly + 4 * u, text);
        String status = unavailable ? "Radar unavailable" : loading ? "Loading radar…" : null;
        if (status != null) {
            text.setColor(DotStyle.FG);
            text.setAlpha(Math.round(0.7f * 255));
            c.drawText(status, w - 26 * u, ly - 24 * u, text);
        }
    }
}
