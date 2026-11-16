package dev.gawel.dothome;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.Place;

/**
 * The radar home-screen widget: the area map of the latest echo frame at the shared dot scale, centred on the
 * cached location. Rendering fetches tiles, so {@link #updateAll} runs on a worker thread (WeatherJob's); the
 * provider callbacks only ask for that job and never touch the network themselves.
 */
public class RadarWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    private static final int DEFAULT_W_DP = 250, DEFAULT_H_DP = 190;
    private static final int ZOOM = 9; // as the full-screen area view
    private static final String NO_DATA = "RADAR · no data";

    @Override
    public void onEnabled(Context context) {
        WeatherJob.schedule(context); // the periodic job also refreshes this widget
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

    /** Blocking (network). Call from a worker thread. Never throws; clears the tile cache when done. */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, RadarWidget.class));
            if (ids == null || ids.length == 0) return; // no widgets: no network work
            try {
                Place place = LocationSource.cached(ctx);
                for (int id : ids) {
                    try {
                        mgr.updateAppWidget(id, views(ctx, mgr, id, place));
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

    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, Place place) {
        float density = ctx.getResources().getDisplayMetrics().density;
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = DEFAULT_W_DP;
        if (hDp <= 0) hDp = DEFAULT_H_DP;
        float wPx = wDp * density, hPx = hDp * density;
        float shrink = Math.min(1f, WidgetUpdater.MAX_SIDE_PX / Math.max(wPx, hPx));
        int w = Math.max(1, Math.round(wPx * shrink)), h = Math.max(1, Math.round(hPx * shrink));
        float eff = density * shrink; // bitmap px per dp, so the dots come out at DotScale after the launcher scales

        Bitmap bmp;
        try {
            bmp = render(ctx, w, h, eff, place);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget art failed: " + e);
            bmp = Bitmap.createBitmap(Math.max(1, Math.min(w, 64)), Math.max(1, Math.min(h, 64)), Bitmap.Config.ARGB_8888);
        }
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_radar);
        rv.setImageViewBitmap(R.id.radar_img, bmp);
        rv.setOnClickPendingIntent(R.id.radar_img, WidgetUpdater.radarTap(ctx));
        return rv;
    }

    /** The map bitmap; any failure along the way degrades to less (map only, then blank BG), never to a throw. */
    static Bitmap render(Context ctx, int w, int h, float eff, Place place) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float r = 24f * h / 150f; // the clock/weather panel radius (WidgetArt: 24 on a 150-unit height)
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(DotStyle.BG);
        RadarPainter rp = new RadarPainter(ctx, eff, false, 12, 26);
        if (place == null) {
            rp.drawReadout(c, h, NO_DATA);
            return bmp;
        }
        GeoProjection p = GeoProjection.area(place.lat, place.lon, w, h);
        RadarTiles.Frame frame = null;
        try {
            List<RadarTiles.Frame> frames = RadarTiles.loadLatest(p, ZOOM);
            if (!frames.isEmpty()) frame = frames.get(frames.size() - 1);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget tiles failed: " + e);
        }
        String readout = frame == null ? NO_DATA : "RADAR · " + timeText(ctx, System.currentTimeMillis());
        try {
            rp.paint(c, w, h, p, Basemap.get(ctx), frame, readout, place.lat, place.lon);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar widget map failed: " + e);
            c.drawColor(DotStyle.BG);
            rp.drawReadout(c, h, NO_DATA);
        }
        return bmp;
    }

    private static String timeText(Context ctx, long ms) {
        String pattern = DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(ms));
    }
}
