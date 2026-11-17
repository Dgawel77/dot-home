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

import java.util.TimeZone;

import dev.gawel.dothome.core.BearsGame;
import dev.gawel.dothome.core.BearsView;

/**
 * Dot Bears: on game day the kickoff time, then the live score, then the final; blank (widget_bears_empty) on every
 * other day. Data comes from {@link BearsRepo}'s cache; fetching and rendering run on {@link LiveFeeds}' executor,
 * so the provider callbacks only hand work over.
 */
public class BearsWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    private static final int DEFAULT_W_DP = 300, DEFAULT_H_DP = 120;

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
            int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, BearsWidget.class));
            return ids == null ? new int[0] : ids;
        } catch (RuntimeException e) {
            Log.w(TAG, "bears widget ids failed: " + e);
            return new int[0];
        }
    }

    static boolean exists(Context ctx) {
        return ids(ctx).length > 0;
    }

    /** Redraws every Dot Bears widget from the cache. Renders bitmaps: call it from a worker thread. */
    static void updateAll(Context ctx) {
        push(ctx, true);
    }

    /** Draws the widgets from the cache when the content changed since the last push, or always when force. */
    static void push(Context ctx, boolean force) {
        try {
            int[] ids = ids(ctx);
            if (ids.length == 0) return;
            TimeZone tz = TimeZone.getDefault();
            BearsGame g = BearsRepo.current(ctx, System.currentTimeMillis(), tz);
            BearsView.Mode mode = BearsView.mode(g);
            boolean backdrop = WidgetArt.backdropOn(ctx);
            String key = key(g, mode, tz, backdrop);
            if (!force && key.equals(lastKey)) return;
            lastKey = key;
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, views(ctx, mgr, id, g, mode, tz, backdrop));
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "bears widget " + id + " update failed: " + e);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bears widget update failed: " + e);
        }
    }

    private static String key(BearsGame g, BearsView.Mode mode, TimeZone tz, boolean backdrop) {
        if (mode == BearsView.Mode.BLANK) return "BLANK";
        String body = mode == BearsView.Mode.PRE ? BearsView.preText(g, tz)
            : g.opp + g.home + g.chi + ":" + g.oppScore + (mode == BearsView.Mode.LIVE ? BearsView.liveLabel(g) : "");
        return mode + "|" + body + "|" + backdrop;
    }

    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, BearsGame g, BearsView.Mode mode,
                                     TimeZone tz, boolean backdrop) {
        if (mode == BearsView.Mode.BLANK) return new RemoteViews(ctx.getPackageName(), R.layout.widget_bears_empty);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_bears);
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
        Bitmap art = BearsArt.render(ctx, w, h, eff, g, mode, tz);
        rv.setImageViewBitmap(R.id.bears_img, art);
        rv.setContentDescription(R.id.bears_img, description(g, mode, tz));
        return rv;
    }

    private static String description(BearsGame g, BearsView.Mode mode, TimeZone tz) {
        if (mode == BearsView.Mode.PRE) return "Bears: " + BearsView.preText(g, tz);
        String s = "Bears " + Math.max(0, g.chi) + ", " + g.opp + " " + Math.max(0, g.oppScore);
        return s + (mode == BearsView.Mode.LIVE ? ", " + BearsView.liveLabel(g) : ", final");
    }
}
