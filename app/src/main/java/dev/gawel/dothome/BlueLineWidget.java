package dev.gawel.dothome;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

import dev.gawel.dothome.core.Arrival;
import dev.gawel.dothome.core.PlatformBoard;

/**
 * Dot Blue Line: the CTA platform board for the pinned or nearest Blue Line station (both directions, up to
 * PlatformBoard.ROWS trains each); ADD CTA KEY IN DOT HOME without a key; blank (widget_blueline_empty) with no
 * station within 10 km or no recent board. Data comes from {@link CtaRepo}'s cache; fetching and rendering run on
 * {@link LiveFeeds}' executor, so the provider callbacks only hand work over.
 */
public class BlueLineWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    /** The 4 x 3 cell default placement (widget_blueline_info's target), used when the launcher reports no size. */
    private static final int DEFAULT_W_DP = 300, DEFAULT_H_DP = 180;

    /** What the widgets last showed; a minute with the same content pushes nothing. */
    private static volatile String lastKey;

    @Override
    public void onEnabled(Context context) {
        FeedJob.schedule(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        FeedJob.schedule(context);
        LiveFeeds.kick(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id, Bundle newOptions) {
        LiveFeeds.kick(context);
    }

    private static int[] ids(Context ctx) {
        try {
            int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, BlueLineWidget.class));
            return ids == null ? new int[0] : ids;
        } catch (RuntimeException e) {
            Log.w(TAG, "blue line widget ids failed: " + e);
            return new int[0];
        }
    }

    static boolean exists(Context ctx) {
        return ids(ctx).length > 0;
    }

    /** Redraws every Dot Blue Line widget from the cache. Renders bitmaps: call it from a worker thread. */
    static void updateAll(Context ctx) {
        push(ctx, true);
    }

    /** Draws the widgets from the cache when the content changed since the last push, or always when force. */
    static void push(Context ctx, boolean force) {
        try {
            int[] ids = ids(ctx);
            if (ids.length == 0) return;
            CtaRepo.Shown s = CtaRepo.current(ctx, System.currentTimeMillis());
            boolean backdrop = WidgetArt.backdropOn(ctx);
            String key = key(s, backdrop);
            if (!force && key.equals(lastKey)) return;
            lastKey = key;
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, views(ctx, mgr, id, s, backdrop));
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "blue line widget " + id + " update failed: " + e);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "blue line widget update failed: " + e);
        }
    }

    /** Every train is keyed, not only the rows shown: a resize forces a redraw anyway. */
    private static String key(CtaRepo.Shown s, boolean backdrop) {
        if (s.mode != CtaRepo.Mode.BOARD) return s.mode + "|" + backdrop;
        StringBuilder b = new StringBuilder("BOARD|").append(s.mapid).append('|');
        rows(b, s.ohare);
        b.append('|');
        rows(b, s.forestPark);
        return b.append('|').append(backdrop).toString();
    }

    private static void rows(StringBuilder b, List<Arrival> trains) {
        for (int i = 0; i < Math.min(PlatformBoard.ROWS, trains.size()); i++) {
            Arrival a = trains.get(i);
            b.append(a.dest).append('=').append(PlatformBoard.text(a)).append(';');
        }
    }

    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, CtaRepo.Shown s, boolean backdrop) {
        if (s.mode == CtaRepo.Mode.BLANK) return new RemoteViews(ctx.getPackageName(), R.layout.widget_blueline_empty);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_blueline);
        float density = ctx.getResources().getDisplayMetrics().density;
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = DEFAULT_W_DP;
        if (hDp <= 0) hDp = DEFAULT_H_DP;
        float wPx = wDp * density, hPx = hDp * density;
        float shrink = Math.min(1f, WidgetUpdater.MAX_SIDE_PX / Math.max(wPx, hPx));
        int w = Math.max(1, Math.round(wPx * shrink)), h = Math.max(1, Math.round(hPx * shrink));
        float eff = density * shrink; // bitmap px per dp, so sizes are true dp once the launcher scales the bitmap
        if (backdrop) {
            rv.setImageViewBitmap(R.id.card_img, WidgetArt.card(w, h, eff, WidgetUpdater.shapeRadiusDp(o, wDp, hDp)));
            rv.setViewVisibility(R.id.card_img, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.card_img, View.GONE);
        }
        Bitmap art = BoardArt.render(ctx, w, h, eff, s);
        rv.setImageViewBitmap(R.id.blueline_img, art);
        rv.setContentDescription(R.id.blueline_img, description(s, PlatformBoard.rowsFit(hDp)));
        return rv;
    }

    private static String description(CtaRepo.Shown s, int rows) {
        if (s.mode == CtaRepo.Mode.NO_KEY) return "Blue Line: add a CTA Train Tracker key in Dot Home";
        return "Blue Line at " + s.label + ". Toward O'Hare: " + spoken(s.ohare, rows)
            + ". Toward Forest Park: " + spoken(s.forestPark, rows) + ".";
    }

    private static String spoken(List<Arrival> trains, int rows) {
        if (trains.isEmpty()) return "no trains";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(Math.max(1, rows), trains.size()); i++) {
            Arrival a = trains.get(i);
            if (i > 0) b.append(", ");
            b.append(a.dest).append(' ').append(a.due ? "due" : a.minutes + " min");
        }
        return b.toString();
    }
}
