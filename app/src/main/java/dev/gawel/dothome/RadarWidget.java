package dev.gawel.dothome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.LocationChooser;
import dev.gawel.dothome.core.Place;
import dev.gawel.dothome.core.RadarMode;

/**
 * The radar home-screen widget: the map of the latest echo frame on the 4 dp lattice, centred on the cached
 * location, in the widget's own view (local or region, {@link RadarMode}). A LOCAL · REGION pill at the top right
 * switches it; a tap anywhere else opens {@link RadarActivity} in the same view. Rendering fetches tiles, so
 * {@link #updateAll} runs on a worker thread (WeatherJob's) and a toggle renders on its own goAsync() worker; the
 * provider callbacks never touch the network on the main thread.
 */
public class RadarWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    private static final int DEFAULT_W_DP = 250, DEFAULT_H_DP = 190;
    private static final String NO_DATA = "RADAR · no data";
    static final String ACTION_TOGGLE = "dev.gawel.dothome.action.RADAR_TOGGLE";
    /** Toggle geometry (dp): the pill is 24 tall, 16 in from the top and right edges, inside a 48 tall touch target. */
    private static final float PILL_H_DP = 24f, PILL_PAD_X_DP = 12f, PILL_INSET_DP = 16f, TOUCH_H_DP = 48f;
    /** What a toggle's own render may take before it leaves the rest to the weather job (goAsync allows ~10 s). */
    private static final long TOGGLE_BUDGET_MS = 8000;

    @Override
    public void onEnabled(Context context) {
        WeatherJob.schedule(context); // the periodic job also refreshes this widget
    }

    @Override
    public void onDisabled(Context context) {
        WeatherJob.schedule(context); // last radar widget gone: back to the slow period
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        WeatherJob.schedule(context);
        WeatherJob.refreshSoon(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id, Bundle newOptions) {
        WeatherJob.refreshSoon(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        PrefsStore prefs = new PrefsStore(context);
        for (int id : ids) prefs.remove(RadarMode.key(id));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_TOGGLE.equals(intent.getAction())) {
            super.onReceive(context, intent);
            return;
        }
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (!isOurs(context, id)) return; // the receiver is exported for APPWIDGET_UPDATE; ignore stray ids
        toggle(context.getApplicationContext(), id, goAsync());
    }

    private static boolean isOurs(Context ctx, int id) {
        try {
            int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, RadarWidget.class));
            if (ids != null) for (int i : ids) if (i == id) return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "radar widget ids failed: " + e);
        }
        return false;
    }

    /**
     * Flips the widget's view, shows the new pill state at once (a partial update: the old map stays), then renders
     * the new map on a worker thread. If that runs past its budget the weather job finishes it.
     */
    private static void toggle(Context ctx, int id, PendingResult pending) {
        PrefsStore prefs = new PrefsStore(ctx);
        String next = RadarMode.toggled(prefs.get(RadarMode.key(id), RadarMode.LOCAL));
        prefs.put(RadarMode.key(id), next); // apply(): in memory at once, so the worker below reads it
        boolean region = RadarMode.isRegion(next);
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        try {
            RemoteViews pill = new RemoteViews(ctx.getPackageName(), R.layout.widget_radar);
            setToggle(ctx, pill, id, region);
            mgr.partiallyUpdateAppWidget(id, pill);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar toggle label failed: " + e);
        }
        new Thread(() -> {
            try {
                long deadline = SystemClock.uptimeMillis() + TOGGLE_BUDGET_MS;
                BooleanSupplier late = () -> SystemClock.uptimeMillis() > deadline;
                RemoteViews rv = views(ctx, mgr, id, place(ctx), late);
                if (late.getAsBoolean()) {
                    WeatherJob.refreshSoon(ctx); // keep the old map (with the new pill) until the job renders it
                } else if (rv != null) {
                    mgr.updateAppWidget(id, rv);
                }
            } catch (Throwable e) {
                Log.w(TAG, "radar toggle render failed: " + e);
            } finally {
                RadarTiles.clearCache(); // the wallpaper process must not keep tile memory resident
                pending.finish();
            }
        }, "dothome-radar-toggle").start();
    }

    private static Place place(Context ctx) {
        Place place = LocationSource.cached(ctx);
        return place != null ? place : new Place(LocationChooser.DEFAULT_LAT, LocationChooser.DEFAULT_LON, Place.Source.DEFAULT);
    }

    /** Blocking (network). Call from a worker thread. Never throws; clears the tile cache when done. */
    static void updateAll(Context ctx, BooleanSupplier cancelled) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, RadarWidget.class));
            if (ids == null || ids.length == 0) return; // no widgets: no network work
            try {
                Place place = place(ctx);
                for (int id : ids) {
                    try {
                        RemoteViews rv = views(ctx, mgr, id, place, cancelled);
                        if (cancelled.getAsBoolean()) return; // keep the last good image rather than a "no data" one
                        if (rv != null) mgr.updateAppWidget(id, rv);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        Log.w(TAG, "radar widget " + id + " update failed: " + e);
                    }
                }
            } finally {
                RadarTiles.clearCache(); // the wallpaper process must not keep tile memory resident
            }
        } catch (Throwable e) {
            Log.w(TAG, "radar widget update failed: " + e);
        }
    }

    private static boolean isRegion(Context ctx, int id) {
        return RadarMode.isRegion(new PrefsStore(ctx).get(RadarMode.key(id), RadarMode.LOCAL));
    }

    /** The widget's views in its current view, or null when the view was toggled while this one was rendering. */
    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, Place place,
                                      BooleanSupplier cancelled) {
        boolean region = isRegion(ctx, id);
        float density = ctx.getResources().getDisplayMetrics().density;
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = DEFAULT_W_DP;
        if (hDp <= 0) hDp = DEFAULT_H_DP;
        float wPx = wDp * density, hPx = hDp * density;
        float shrink = Math.min(1f, WidgetUpdater.MAX_SIDE_PX / Math.max(wPx, hPx));
        int w = Math.max(1, Math.round(wPx * shrink)), h = Math.max(1, Math.round(hPx * shrink));
        float eff = density * shrink; // bitmap px per dp, so the dots come out at lattice size after the launcher scales

        Bitmap bmp;
        try {
            bmp = render(ctx, w, h, eff, place, region, cancelled);
            WidgetArt.glassMap(ctx, bmp, eff, WidgetUpdater.shapeRadiusDp(o, wDp, hDp));
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget art failed: " + e);
            bmp = Bitmap.createBitmap(Math.max(1, Math.min(w, 64)), Math.max(1, Math.min(h, 64)), Bitmap.Config.ARGB_8888);
        }
        if (isRegion(ctx, id) != region) return null; // toggled meanwhile: that toggle's render pushes instead
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_radar);
        rv.setImageViewBitmap(R.id.radar_img, bmp);
        rv.setOnClickPendingIntent(R.id.radar_img, openRadar(ctx, id, region));
        setToggle(ctx, rv, id, region);
        return rv;
    }

    /**
     * The LOCAL · REGION pill: its own bitmap in the radar_toggle ImageView (also the 48 dp touch target), so a
     * toggle can swap it at once with a partial update while the map re-renders.
     */
    private static void setToggle(Context ctx, RemoteViews rv, int id, boolean region) {
        float d = ctx.getResources().getDisplayMetrics().density;
        RadarPainter rp = new RadarPainter(ctx, d, region, 0, 0);
        float pad = PILL_PAD_X_DP * d, pillW = rp.toggleWidth(pad);
        float margin = (TOUCH_H_DP - PILL_H_DP) / 2f * d; // pill centred in the touch target, 12 dp around it
        Bitmap b = Bitmap.createBitmap(Math.round(pillW + 2 * margin), Math.round(TOUCH_H_DP * d), Bitmap.Config.ARGB_8888);
        rp.drawToggle(new Canvas(b), margin, margin, pad, PILL_H_DP * d, region);
        rv.setImageViewBitmap(R.id.radar_toggle, b);
        rv.setContentDescription(R.id.radar_toggle, region
            ? "Radar view: region. Tap for local." : "Radar view: local. Tap for region.");
        Intent i = new Intent(ACTION_TOGGLE).setClass(ctx, RadarWidget.class)
            .setData(Uri.parse("dothome://radar-toggle/" + id)) // one PendingIntent per widget
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        rv.setOnClickPendingIntent(R.id.radar_toggle, PendingIntent.getBroadcast(ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    /** RadarActivity in the widget's current view; one PendingIntent per widget, its extra kept current. */
    private static PendingIntent openRadar(Context ctx, int id, boolean region) {
        Intent i = new Intent(ctx, RadarActivity.class)
            .setData(Uri.parse("dothome://radar/" + id))
            .putExtra(RadarActivity.EXTRA_REGION, region)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The map bitmap; any failure along the way degrades to less (map only, then blank BG), never to a throw. The
     * toggle's corner (top right) is kept clear of dots and labels.
     */
    static Bitmap render(Context ctx, int w, int h, float eff, Place place, boolean region, BooleanSupplier cancelled) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(DotStyle.GROUND);
        RadarPainter rp = new RadarPainter(ctx, eff, region, 12, 26);
        float pillW = rp.toggleWidth(PILL_PAD_X_DP * eff), inset = PILL_INSET_DP * eff;
        float[] pill = {w - inset - pillW, inset, pillW, PILL_H_DP * eff};
        if (place == null) {
            rp.drawReadout(c, h, NO_DATA);
            return bmp;
        }
        GeoProjection p = region ? GeoProjection.region(place.lat, place.lon, w, h) : GeoProjection.area(place.lat, place.lon, w, h);
        RadarTiles.Frame frame = null;
        try {
            List<RadarTiles.Frame> frames = RadarTiles.loadLatest(p, RadarMode.zoom(region), cancelled);
            if (!frames.isEmpty()) frame = frames.get(frames.size() - 1);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget tiles failed: " + e);
        }
        String readout = frame == null ? NO_DATA : "RADAR · " + timeText(ctx, System.currentTimeMillis());
        try {
            rp.paint(c, w, h, p, Basemap.get(ctx), frame, readout, place.lat, place.lon, pill);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget paint failed, retrying map only: " + e);
            try {
                c.drawColor(DotStyle.GROUND);
                rp.paint(c, w, h, p, Basemap.get(ctx), null, NO_DATA, place.lat, place.lon, pill);
            } catch (RuntimeException | OutOfMemoryError e2) {
                Log.w(TAG, "radar widget map failed: " + e2);
                c.drawColor(DotStyle.GROUND);
                rp.drawReadout(c, h, NO_DATA);
            }
        }
        return bmp;
    }

    private static String timeText(Context ctx, long ms) {
        String pattern = DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(ms));
    }
}
