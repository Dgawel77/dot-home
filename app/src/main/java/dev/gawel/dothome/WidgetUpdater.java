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
import android.widget.RemoteViews;

import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.Weather;

/** Pushes fresh data to the home-screen widgets. */
final class WidgetUpdater {
    private static final String TAG = "DotHome";
    private static final String CLOCK_PKG = "com.sec.android.app.clockpackage";
    private static final int DEFAULT_W_DP = 250, DEFAULT_H_DP = 110;
    static final int MAX_SIDE_PX = 1200; // keeps each RemoteViews bitmap far below the binder/memory limit
    private static final float CLOCK_WEIGHT = 1.4f, WEATHER_WEIGHT = 1f;

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
        float wPx = wDp * density, hPx = hDp * density;
        float shrink = Math.min(1f, MAX_SIDE_PX / Math.max(wPx, hPx));
        wPx *= shrink;
        hPx *= shrink;
        float eff = density * shrink; // bitmap px per dp: dot scale must come out right after the launcher stretches it back
        float total = CLOCK_WEIGHT + WEATHER_WEIGHT;
        int clockW = Math.max(1, Math.round(wPx * CLOCK_WEIGHT / total));
        int weatherW = Math.max(1, Math.round(wPx * WEATHER_WEIGHT / total));
        int h = Math.max(1, Math.round(hPx));

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_clock);
        Bitmap clock = safe(h, clockW, () -> WidgetArt.clock(ctx, clockW, h, now, eff));
        Bitmap weather = safe(h, weatherW, () -> WidgetArt.weather(ctx, weatherW, h, wx, now, eff));
        rv.setImageViewBitmap(R.id.clock_img, clock);
        rv.setImageViewBitmap(R.id.weather_img, weather);
        rv.setOnClickPendingIntent(R.id.clock_img, PendingIntent.getActivity(ctx, 1, clockIntent(ctx), flags()));
        rv.setOnClickPendingIntent(R.id.weather_img, radarTap(ctx));
        return rv;
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
        Bitmap art = safe(h, w, () -> WidgetArt.music(ctx, w, h, np, eff));
        rv.setViewVisibility(R.id.music_root, android.view.View.VISIBLE);
        rv.setImageViewBitmap(R.id.music_img, art);
        rv.setImageViewResource(R.id.music_play, np.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        rv.setOnClickPendingIntent(R.id.music_prev, commandIntent(ctx, 10, MediaCommandReceiver.PREV));
        rv.setOnClickPendingIntent(R.id.music_play, commandIntent(ctx, 11, MediaCommandReceiver.PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.music_next, commandIntent(ctx, 12, MediaCommandReceiver.NEXT));
        return rv;
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
