package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.gawel.dothome.core.ClearZones;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.RadarMode;

/**
 * The flat dotted map with the last hour of NEXRAD echoes rolling in as halftone. Static layers are drawn once per
 * view into bitmaps on a worker thread; the storm is a precomputed dot list per frame, drawn into a cached layer
 * only when the frame changes. The activity's header, credits and this view's own readout and legend are clear zones.
 */
final class RadarView extends View {
    private static final String TAG = "DotHome";
    private static final int FRAMES = 12;
    private static final float FRAME_S = 0.4f, HOLD_S = 1.5f; // data playback: 0.4 s a frame, NOW held 1.5 s
    private static final float PILL_W_DP = 64, PILL_H_DP = 48, SCRUB_GAP_DP = 12;

    private static final float TOP_PAD_DP = 64, BOTTOM_PAD_DP = 100; // labels keep clear of the header and readout
    private static final float CLEAR_EDGE_DP = 16;
    private static final int SCRUB_DOTS = 60, SCRUB_MIN_DOTS = 8;
    private static final String NOW = "NOW", MIN = "MIN", HEAVY = "HEAVY", LIGHT = "LIGHT",
        UNAVAILABLE = "RADAR UNAVAILABLE", LOADING = "LOADING RADAR";
    private static final float[] LEVELS = {1f, 0.6f, 0.25f};

    private final float u; // display density
    private final RadarPainter ui; // UI-thread painter (halftone, location pulse, readout dots)
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private double lat = 41.88, lon = -87.63;
    private boolean haveLocation, region, running;
    private volatile int gen;

    private GeoProjection proj;
    private Bitmap base, overlay;
    private float[][] raw; // per frame: all storm dots (cx, cy, diameter) in lattice cells
    private float[][] grids; // raw, minus the clear zones
    private byte[][] readoutCells; // per frame: matrix cells of the minus and minutes, null for NOW
    private boolean projRegion;
    private volatile ClearZones zonesNow;
    private volatile int zgen;
    private boolean refreshPending;
    private float wHeavy = -1, wLight;
    private final Runnable refresh = this::refreshZones;
    private Bitmap layer; // the current frame's halftone, redrawn only when the frame changes
    private Canvas layerCanvas;
    private float[] layerSrc;
    private int[] minutes;
    private float[][] overlays = new float[0][]; // header titles, header controls, credits: x, y, w, h in view px
    private boolean loading, unavailable;
    private long t0;

    // playback: opens on the latest frame, paused
    private boolean playing;
    private int frameIdx = FRAMES - 1;
    private long playT0;
    private volatile boolean[] loaded = new boolean[0]; // per frame: has any tile
    private String[] suffix = new String[0]; // per frame: "MIN \u00B7 4:32 PM" / "NOW \u00B7 4:47 PM"
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private float pillL, pillT, pillR, pillB; // play button rect, set by drawReadout
    private float scrubX0, scrubStep, scrubY;
    private int scrubDots;
    private int touchMode; // 0 none, 1 play button, 2 scrubber

    RadarView(Context ctx) {
        super(ctx);
        u = ctx.getResources().getDisplayMetrics().density;
        ui = new RadarPainter(ctx, u, false, TOP_PAD_DP, BOTTOM_PAD_DP);
        setBackgroundColor(DotStyle.GROUND);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.6f * u);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setColor(DotStyle.L100);
        pillPaint.setStyle(Paint.Style.FILL);
        pillPaint.setColor(DotStyle.L10);
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
        if (refreshPending) return;
        refreshPending = true;
        post(refresh); // layout reports several blocks in one pass: one refresh for all of them
    }

    /** New clear zones: redraws base and overlay and refilters the storm, keeping the projection and tiles. */
    private void refreshZones() {
        refreshPending = false;
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !haveLocation) return;
        final ClearZones zones = zonesFor(w, h, overlays);
        zonesNow = zones;
        final GeoProjection p = proj;
        if (p == null) return; // the first build is still running; it checks zonesNow when it lands
        final int g = gen, zg = ++zgen;
        final boolean reg = projRegion;
        final double la = lat, lo = lon;
        final float[][] rawNow = raw;
        new Thread(() -> {
            try {
                Basemap bm = Basemap.get(getContext());
                RadarPainter rp = new RadarPainter(getContext(), u, reg, TOP_PAD_DP, BOTTOM_PAD_DP);
                rp.setZones(zones);
                if (g != gen || zg != zgen) return;
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                rp.drawBase(new Canvas(b), p, bm, w, h, la, lo);
                if (g != gen || zg != zgen) return;
                Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                rp.drawPlaces(new Canvas(o));
                float[][] f = null;
                if (rawNow != null) {
                    f = new float[rawNow.length][];
                    for (int i = 0; i < f.length; i++) f[i] = rp.filterStorm(rawNow[i]);
                }
                final float[][] filtered = f;
                post(() -> {
                    if (g != gen || zg != zgen) return;
                    base = b;
                    overlay = o;
                    if (filtered != null && raw == rawNow) grids = filtered;
                    layerSrc = null;
                    invalidate();
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "radar zone refresh failed: " + e);
            }
        }, "dothome-radar-zones").start();
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
        rects.add(new float[] {0, sy - 68 * u, 300 * u, b - (sy - 68 * u)});
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
        zonesNow = zones;
        zgen++;
        loading = true;
        unavailable = false;
        grids = null;
        raw = null;
        playing = false;
        frameIdx = FRAMES - 1;
        loaded = new boolean[0];
        readoutCells = null;
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
            if (g != gen) return;
            rp.drawBase(new Canvas(b), p, bm, w, h, la, lo);
            if (g != gen) return;
            Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rp.drawPlaces(new Canvas(o));
            post(() -> {
                if (g != gen) return;
                proj = p;
                projRegion = reg;
                layerSrc = null;
                base = b;
                overlay = o;
                invalidate();
                if (zonesNow != zones && !refreshPending) { // the overlays moved while this was drawing
                    refreshPending = true;
                    post(refresh);
                }
            });

            List<RadarTiles.Frame> frames = RadarTiles.load(p, RadarMode.zoom(reg), () -> g != gen);
            if (g != gen) return;
            float gp = rp.step(); // the S4 lattice
            final ClearZones usedZones = zonesNow;
            int c = (int) Math.ceil(w / gp), r = (int) Math.ceil(h / gp);
            double[] lons = rp.lons(p, c), lats = rp.lats(p, r);
            float[][] rw = new float[frames.size()][], gr = new float[frames.size()][];
            int[] mins = new int[frames.size()];
            byte[][] cells = new byte[frames.size()][];
            boolean[] have = new boolean[frames.size()];
            String[] sfx = new String[frames.size()];
            long loadedAt = System.currentTimeMillis();
            SimpleDateFormat clock = new SimpleDateFormat(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm a",
                Locale.getDefault());
            rp.setZones(usedZones);
            for (int f = 0; f < gr.length; f++) {
                mins[f] = frames.get(f).minutesAgo;
                rw[f] = RadarPainter.storm(frames.get(f), lons, lats);
                gr[f] = rp.filterStorm(rw[f]);
                cells[f] = mins[f] == 0 ? null : DotMatrixFont.cells("\u2212" + mins[f]);
                for (Bitmap tb : frames.get(f).tiles) if (tb != null) have[f] = true;
                sfx[f] = ((mins[f] == 0 ? NOW : MIN) + " \u00B7 "
                    + clock.format(new Date(loadedAt - mins[f] * 60_000L))).toUpperCase(Locale.ROOT);
            }
            final boolean none = gr.length == 0;
            post(() -> {
                if (g != gen) return;
                raw = rw;
                grids = gr;
                minutes = mins;
                readoutCells = cells;
                loaded = have;
                suffix = sfx;
                frameIdx = Math.max(0, gr.length - 1); // opens on NOW, paused unless play was already tapped
                playT0 = SystemClock.uptimeMillis();
                layerSrc = null;
                if (zonesNow != usedZones && !refreshPending) {
                    refreshPending = true;
                    post(refresh);
                }
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
            int n = gr.length;
            if (playing) {
                long cycleMs = (long) ((n - 1) * FRAME_S * 1000f + HOLD_S * 1000f);
                long ms = (SystemClock.uptimeMillis() - playT0) % cycleMs;
                frameIdx = Math.min(n - 1, (int) (ms / (FRAME_S * 1000f)));
            }
            frame = nearestLoaded(Math.max(0, Math.min(n - 1, frameIdx))); // skips frames that failed to load
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
        if (wHeavy < 0) {
            wHeavy = ui.labelWidth(HEAVY);
            wLight = ui.labelWidth(LIGHT);
        }
        // legend, bottom right: the storm's own light / medium / heavy dot sizes between mono labels
        float xr = w - Lattice.snap(16 * u, u);
        ui.label(c, HEAVY, xr, sy + 4 * u, Paint.Align.RIGHT, DotStyle.L46);
        float x = ((float) Math.floor((xr - wHeavy - 12 * u) / s) + 0.5f) * s;
        for (float b : LEVELS) {
            ui.dotAt(c, x, sy, ui.stormDiameter(b) / 2f, DotStyle.L65);
            x -= 2 * s;
        }
        ui.label(c, LIGHT, x - 4 * u, sy + 4 * u, Paint.Align.RIGHT, DotStyle.L46);
        float legendLeft = x - 4 * u - wLight;
        // play / pause pill, then the scrubber: a dot row ending 8 dp short of the legend, the head heavy in L100
        pillL = x0;
        pillR = x0 + PILL_W_DP * u;
        pillT = sy - PILL_H_DP * u / 2f;
        pillB = sy + PILL_H_DP * u / 2f;
        drawPlayPill(c);
        float sx0 = Lattice.snap(pillR + SCRUB_GAP_DP * u, u);
        int dots = Math.max(SCRUB_MIN_DOTS, Math.min(SCRUB_DOTS, (int) Math.floor((legendLeft - 8 * u - sx0) / s)));
        scrubX0 = sx0; scrubStep = s; scrubY = sy; scrubDots = dots;
        float reg = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.REGULAR, u);
        boolean[] have = loaded;
        for (int i = 0; i < dots; i++) {
            int f = Math.round(i / (float) (dots - 1) * (FRAMES - 1));
            ui.dotAt(c, sx0 + (i + 0.5f) * s, sy, reg, f < have.length && have[f] ? DotStyle.L46 : DotStyle.L10);
        }
        float k = frame < 0 || gr.length < 2 ? 1f : frame / (float) (gr.length - 1);
        ui.dotAt(c, sx0 + (Math.round(k * (dots - 1)) + 0.5f) * s, sy,
            Lattice.radiusPx(Lattice.Step.S8, Lattice.Weight.HEAVY, u), DotStyle.L100);
        byte[][] rc = readoutCells;
        String[] sfx = suffix;
        if (frame >= 0 && rc != null && frame < rc.length && frame < sfx.length) {
            float oy = Lattice.snap(sy - 60 * u, u);
            if (rc[frame] == null) {
                ui.label(c, sfx[frame], x0, oy + 7 * s, Paint.Align.LEFT, DotStyle.L65);
            } else {
                float nw = ui.matrix(c, rc[frame], x0, oy, DotStyle.L65);
                ui.label(c, sfx[frame], x0 + nw + 8 * u, oy + 7 * s, Paint.Align.LEFT, DotStyle.L65);
            }
        }
        if (unavailable) ui.label(c, UNAVAILABLE, xr, sy - 24 * u, Paint.Align.RIGHT, DotStyle.L46);
        else if (loading) ui.label(c, LOADING, xr, sy - 24 * u, Paint.Align.RIGHT, DotStyle.L46);
    }

    // ---- playback controls ---------------------------------------------------------------------------------

    /** Nearest loaded frame at or before i, else the nearest after it; i itself when none is loaded. */
    private int nearestLoaded(int i) {
        boolean[] have = loaded;
        if (i < have.length && have[i]) return i;
        for (int j = Math.min(i, have.length - 1); j >= 0; j--) if (have[j]) return j;
        for (int j = i + 1; j < have.length; j++) if (have[j]) return j;
        return i;
    }

    private void drawPlayPill(Canvas c) {
        float h = pillB - pillT;
        c.drawRoundRect(pillL, pillT, pillR, pillB, h / 2f, h / 2f, pillPaint);
        float cx = (pillL + pillR) / 2f, cy = (pillT + pillB) / 2f, g = 8 * u;
        iconPath.rewind();
        if (playing) { // pause: two bars
            iconPath.moveTo(cx - g * 0.5f, cy - g); iconPath.lineTo(cx - g * 0.5f, cy + g);
            iconPath.moveTo(cx + g * 0.5f, cy - g); iconPath.lineTo(cx + g * 0.5f, cy + g);
        } else { // play: an outlined triangle
            iconPath.moveTo(cx - g * 0.6f, cy - g); iconPath.lineTo(cx + g * 0.9f, cy);
            iconPath.lineTo(cx - g * 0.6f, cy + g); iconPath.close();
        }
        c.drawPath(iconPath, iconPaint);
    }

    private void togglePlay() {
        if (playing) {
            playing = false;
        } else {
            playing = true;
            playT0 = SystemClock.uptimeMillis(); // from the oldest frame
            frameIdx = 0;
        }
        invalidate();
    }

    /** Pauses on the loaded frame nearest the finger. */
    private void scrubTo(float x) {
        boolean[] have = loaded;
        if (have.length == 0 || scrubDots < 2) return;
        int dot = Math.max(0, Math.min(scrubDots - 1, (int) Math.floor((x - scrubX0) / scrubStep)));
        int f = Math.round(dot / (float) (scrubDots - 1) * (have.length - 1));
        playing = false;
        frameIdx = nearestLoaded(f);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float slop = 8 * u;
                if (x >= pillL - slop && x <= pillR + slop && y >= pillT - slop && y <= pillB + slop && pillR > 0) {
                    touchMode = 1;
                    return true;
                }
                if (scrubDots > 1 && Math.abs(y - scrubY) <= 24 * u
                    && x >= scrubX0 - 8 * u && x <= scrubX0 + scrubDots * scrubStep + 8 * u) {
                    touchMode = 2;
                    scrubTo(x);
                    return true;
                }
                touchMode = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchMode == 2) { scrubTo(x); return true; }
                if (touchMode == 1) return true;
                break;
            case MotionEvent.ACTION_UP:
                if (touchMode == 1 && x >= pillL - 8 * u && x <= pillR + 8 * u && y >= pillT - 8 * u && y <= pillB + 8 * u) togglePlay();
                if (touchMode != 0) { touchMode = 0; return true; }
                break;
            case MotionEvent.ACTION_CANCEL:
                touchMode = 0;
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
