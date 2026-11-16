package dev.gawel.dothome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import dev.gawel.dothome.core.CardShape;
import dev.gawel.dothome.core.ClockFit;
import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.Weather;

/** Pushes fresh data to the home-screen widgets. */
final class WidgetUpdater {
    private static final String TAG = "DotHome";
    private static final String CLOCK_PKG = "com.sec.android.app.clockpackage";
    private static final int DEFAULT_W_DP = 250, DEFAULT_H_DP = 110;
    static final int MAX_SIDE_PX = 1200; // keeps each RemoteViews bitmap far below the binder/memory limit
    /** Clock bottom row: the weather's left margin, the least it gets, and slack on the measured date width. */
    private static final float WEATHER_MARGIN_DP = 12f, MIN_WEATHER_DP = 64f, DATE_SLACK_DP = 4f;

    private WidgetUpdater() {}

    static void updateAll(Context ctx) {
        NowPlaying np = MusicWidget.last();
        if (np != null || !MusicWidget.goneAlreadySent()) {
            MusicWidget.update(ctx, np); // re-extrapolates the progress bar on every tick
        }
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ClockWidget.class));
            if (ids == null || ids.length == 0) return;
            Weather wx = WeatherRepo.cached(ctx);
            long now = System.currentTimeMillis();
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, clockViews(ctx, mgr, id, wx, now));
                } catch (RuntimeException e) {
                    Log.w(TAG, "widget " + id + " update failed: " + e);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "widget update failed: " + e);
        }
    }

    private static RemoteViews clockViews(Context ctx, AppWidgetManager mgr, int id, Weather wx, long now) {
        float density = ctx.getResources().getDisplayMetrics().density;
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = DEFAULT_W_DP;
        if (hDp <= 0) hDp = DEFAULT_H_DP;
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_clock);

        // the glass card behind everything, one piece over the whole widget
        if (WidgetArt.backdropOn(ctx)) {
            float eff = density * shrink(Math.max(wDp, hDp) * density); // bitmap px per dp
            int w = Math.max(1, Math.round(wDp * eff)), h = Math.max(1, Math.round(hDp * eff));
            float radiusDp = shapeRadiusDp(o, wDp, hDp);
            rv.setImageViewBitmap(R.id.card_img, safe(h, w, () -> WidgetArt.card(w, h, eff, radiusDp)));
            rv.setViewVisibility(R.id.card_img, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.card_img, View.GONE);
        }

        // the time: TextClocks in the matrix font (they tick the seconds themselves), or the per-minute fallback
        int innerDp = wDp - 2 * ClockFit.PAD_DP;
        if (new PrefsStore(ctx).bitmapClock()) {
            int cell = ClockFit.cellDp(wDp, hDp, ClockFit.MINUTES_COLS);
            float eff = density * shrink(Math.max(1, innerDp) * density);
            int w = Math.max(1, Math.round(innerDp * eff));
            int h = Math.max(1, Math.round(ClockFit.timeHeightDp(cell) * eff));
            rv.setViewVisibility(R.id.time_grid, View.GONE);
            rv.setViewVisibility(R.id.time_lit, View.GONE);
            rv.setViewVisibility(R.id.time_img, View.VISIBLE);
            rv.setImageViewBitmap(R.id.time_img, safe(h, w, () -> WidgetArt.timeBitmap(ctx, w, now, cell, eff)));
        } else {
            int cell = ClockFit.cellDp(wDp, hDp, ClockFit.SECONDS_COLS);
            float size = ClockFit.textSizeDp(cell); // both layers get the same size, so the dots stay registered
            rv.setTextViewTextSize(R.id.time_grid, TypedValue.COMPLEX_UNIT_DIP, size);
            rv.setTextViewTextSize(R.id.time_lit, TypedValue.COMPLEX_UNIT_DIP, size);
            rv.setViewVisibility(R.id.time_grid, View.VISIBLE);
            rv.setViewVisibility(R.id.time_lit, View.VISIBLE);
            rv.setViewVisibility(R.id.time_img, View.GONE);
        }

        // the weather, right of the date: whatever the date leaves, so the date stays whole
        float dateDp = WidgetArt.dateWidthDp(ctx, now) + DATE_SLACK_DP;
        float maxDp = Math.max(MIN_WEATHER_DP, innerDp - WEATHER_MARGIN_DP - dateDp);
        float effRow = density * shrink(maxDp * density);
        int rowH = Math.max(1, Math.round(ClockFit.ROW_DP * effRow));
        rv.setImageViewBitmap(R.id.weather_img, safe(rowH, Math.round(maxDp * effRow),
            () -> WidgetArt.weather(ctx, wx, now, ClockFit.ROW_DP, maxDp, effRow)));

        rv.setOnClickPendingIntent(R.id.clock_root, PendingIntent.getActivity(ctx, 1, clockIntent(ctx), flags()));
        rv.setOnClickPendingIntent(R.id.weather_img, radarTap(ctx));
        return rv;
    }

    /** Scale that keeps a bitmap side of sidePx within MAX_SIDE_PX (1 when it already fits). */
    private static float shrink(float sidePx) {
        return Math.min(1f, MAX_SIDE_PX / Math.max(1f, sidePx));
    }

    private static final int MUSIC_DEFAULT_W_DP = 330, MUSIC_DEFAULT_H_DP = 76;

    /** The music widget's views: hidden when np is null, otherwise art/text bitmap plus transport buttons. */
    static RemoteViews musicViews(Context ctx, AppWidgetManager mgr, int id, NowPlaying np) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_music);
        if (np == null) {
            rv.setViewVisibility(R.id.music_root, android.view.View.GONE);
            return rv;
        }
        float density = ctx.getResources().getDisplayMetrics().density;
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = MUSIC_DEFAULT_W_DP;
        if (hDp <= 0) hDp = MUSIC_DEFAULT_H_DP;
        float wPx = wDp * density, hPx = hDp * density;
        float shrink = Math.min(1f, MAX_SIDE_PX / Math.max(wPx, hPx));
        int w = Math.max(1, Math.round(wPx * shrink)), h = Math.max(1, Math.round(hPx * shrink));
        float eff = density * shrink;
        float radiusDp = shapeRadiusDp(o, wDp, hDp);
        Bitmap art = safe(h, w, () -> WidgetArt.music(ctx, w, h, np, eff, radiusDp));
        rv.setViewVisibility(R.id.music_root, android.view.View.VISIBLE);
        rv.setImageViewBitmap(R.id.music_img, art);
        rv.setImageViewResource(R.id.music_play, np.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        rv.setOnClickPendingIntent(R.id.music_prev, commandIntent(ctx, 10, MediaCommandReceiver.PREV));
        rv.setOnClickPendingIntent(R.id.music_play, commandIntent(ctx, 11, MediaCommandReceiver.PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.music_next, commandIntent(ctx, 12, MediaCommandReceiver.NEXT));
        return rv;
    }

    /**
     * The launcher's clip radius for this widget in dp: One UI's "semShapeRadius" option (a float), 24 dp when it
     * is absent. See {@link CardShape}.
     */
    static float shapeRadiusDp(Bundle o, float wDp, float hDp) {
        float raw = Float.NaN;
        if (o != null && o.containsKey(CardShape.OPTION_KEY)) {
            raw = o.getFloat(CardShape.OPTION_KEY, Float.NaN);
            if (Float.isNaN(raw)) raw = (float) o.getDouble(CardShape.OPTION_KEY, Double.NaN); // stored as a double
        }
        return CardShape.radiusDp(raw, wDp, hDp);
    }

    private static PendingIntent commandIntent(Context ctx, int code, String action) {
        Intent i = new Intent(action).setClass(ctx, MediaCommandReceiver.class);
        return PendingIntent.getBroadcast(ctx, code, i, flags());
    }

    private interface Art { Bitmap draw(); }

    /** Never lets a drawing problem reach the widget host: falls back to a transparent bitmap. */
    private static Bitmap safe(int h, int w, Art art) {
        try {
            return art.draw();
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "widget art failed: " + e);
            return Bitmap.createBitmap(Math.max(1, Math.min(w, 64)), Math.max(1, Math.min(h, 64)), Bitmap.Config.ARGB_8888);
        }
    }

    private static Intent clockIntent(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(CLOCK_PKG);
        if (i == null) i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** What a tap on the radar widget opens: RadarActivity (singleTask, so taps never stack copies). */
    static PendingIntent radarTap(Context ctx) {
        return PendingIntent.getActivity(ctx, 2, new Intent(ctx, RadarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
