package dev.gawel.dothome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import dev.gawel.dothome.core.HueLayout;
import dev.gawel.dothome.core.HueRows;

/**
 * Dot Hue: up to HueRows.MAX Hue rooms or zones, each a name (tap: on/off) and five dots (tap dot n: n x 20%).
 * The art is one bitmap (HueArt); transparent tap cells are laid over it at runtime in HueLayout's geometry (needs
 * API 31 for the layout calls; older devices get the art without taps). PAIR HUE IN DOT HOME until paired, PICK
 * ROOMS IN DOT HOME until rooms are picked. State comes from {@link HueRepo}.
 */
public class HueWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    /** The 4 x 2 cell default placement, used when the launcher reports no size. */
    private static final int DEFAULT_W_DP = 300, DEFAULT_H_DP = 180;
    /** Tap cells take request codes 200 + row * 6 + cell (cell 0 = the name, 1..5 = the dots). */
    private static final int CODE_BASE = 200;

    private static final int[] ROWS = {R.id.hue_row_0, R.id.hue_row_1, R.id.hue_row_2, R.id.hue_row_3};
    private static final int[][] CELLS = {
        {R.id.hue_r0_name, R.id.hue_r0_d0, R.id.hue_r0_d1, R.id.hue_r0_d2, R.id.hue_r0_d3, R.id.hue_r0_d4},
        {R.id.hue_r1_name, R.id.hue_r1_d0, R.id.hue_r1_d1, R.id.hue_r1_d2, R.id.hue_r1_d3, R.id.hue_r1_d4},
        {R.id.hue_r2_name, R.id.hue_r2_d0, R.id.hue_r2_d1, R.id.hue_r2_d2, R.id.hue_r2_d3, R.id.hue_r2_d4},
        {R.id.hue_r3_name, R.id.hue_r3_d0, R.id.hue_r3_d1, R.id.hue_r3_d2, R.id.hue_r3_d3, R.id.hue_r3_d4}};

    /** What the widgets last showed; the same content pushes nothing. */
    private static volatile String lastKey;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        HueRepo.kick(context, true, redraw(context, true));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id, Bundle newOptions) {
        push(context, true);
    }

    private static int[] ids(Context ctx) {
        try {
            int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, HueWidget.class));
            return ids == null ? new int[0] : ids;
        } catch (RuntimeException e) {
            Log.w(TAG, "hue widget ids failed: " + e);
            return new int[0];
        }
    }

    static boolean exists(Context ctx) {
        return ids(ctx).length > 0;
    }

    /** A redraw for HueRepo to run after a refresh or a tap (any thread). */
    static Runnable redraw(Context ctx, boolean force) {
        final Context app = ctx.getApplicationContext();
        return () -> push(app, force);
    }

    /** Draws every Dot Hue widget when what it shows changed since the last push, or always when force. */
    static void push(Context ctx, boolean force) {
        try {
            int[] ids = ids(ctx);
            if (ids.length == 0) return;
            HueRepo.Shown s = HueRepo.current(ctx);
            boolean backdrop = WidgetArt.backdropOn(ctx);
            String key = key(s, backdrop);
            if (!force && key.equals(lastKey)) return;
            lastKey = key;
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, views(ctx, mgr, id, s, backdrop));
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "hue widget " + id + " update failed: " + e);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "hue widget update failed: " + e);
        }
    }

    private static String key(HueRepo.Shown s, boolean backdrop) {
        StringBuilder b = new StringBuilder().append(s.mode).append('|').append(s.reachable).append('|');
        for (HueRows.Row r : s.rows) {
            b.append(r.groupedLightId).append('=').append(r.name).append(':').append(r.state.on).append(':')
                .append(HueRows.litDots(r.state)).append(';');
        }
        return b.append('|').append(backdrop).toString();
    }

    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, HueRepo.Shown s, boolean backdrop) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_hue);
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
        rv.setImageViewBitmap(R.id.hue_img, HueArt.render(ctx, w, h, eff, s));
        rv.setContentDescription(R.id.hue_img, description(s));
        attachTaps(ctx, rv, s, w / eff, h / eff);
        return rv;
    }

    /** Places the tap cells over the drawn rows; rows that are not drawn (or any, before API 31) stay hidden. */
    private static void attachTaps(Context ctx, RemoteViews rv, HueRepo.Shown s, float wDp, float hDp) {
        int n = s.mode == HueRepo.Mode.ROWS ? HueLayout.rowsFit(hDp, s.rows.size()) : 0;
        for (int i = 0; i < HueRows.MAX; i++) {
            if (i >= n || Build.VERSION.SDK_INT < 31) {
                rv.setViewVisibility(ROWS[i], View.GONE);
                continue;
            }
            HueRows.Row row = s.rows.get(i);
            float[] r = HueLayout.row(wDp, hDp, n, i);
            int u = TypedValue.COMPLEX_UNIT_DIP;
            rv.setViewVisibility(ROWS[i], View.VISIBLE);
            rv.setViewLayoutWidth(ROWS[i], r[2], u);
            rv.setViewLayoutHeight(ROWS[i], r[3], u);
            rv.setViewLayoutMargin(ROWS[i], RemoteViews.MARGIN_START, r[0], u);
            rv.setViewLayoutMargin(ROWS[i], RemoteViews.MARGIN_TOP, r[1], u);
            String name = row.name.toLowerCase(java.util.Locale.ROOT);
            rv.setOnClickPendingIntent(CELLS[i][0], intent(ctx, i, 0, row.groupedLightId));
            rv.setContentDescription(CELLS[i][0], name + (row.state.on ? ": on, tap to turn off" : ": off, tap to turn on"));
            for (int d = 1; d <= HueRows.DOTS; d++) {
                rv.setOnClickPendingIntent(CELLS[i][d], intent(ctx, i, d, row.groupedLightId));
                rv.setContentDescription(CELLS[i][d], name + " " + (d * 20) + " percent");
            }
        }
    }

    /** cell 0: toggle; 1..5: that dot. */
    private static PendingIntent intent(Context ctx, int row, int cell, String light) {
        Intent in = new Intent(cell == 0 ? HueCommandReceiver.TOGGLE : HueCommandReceiver.DIM)
            .setClass(ctx, HueCommandReceiver.class)
            .putExtra(HueCommandReceiver.EXTRA_LIGHT, light)
            .putExtra(HueCommandReceiver.EXTRA_DOT, cell);
        return PendingIntent.getBroadcast(ctx, CODE_BASE + row * 6 + cell, in,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String description(HueRepo.Shown s) {
        if (s.mode == HueRepo.Mode.UNPAIRED) return "Hue lights: pair your Hue bridge in Dot Home";
        if (s.mode == HueRepo.Mode.NO_ROOMS) return "Hue lights: pick rooms in Dot Home";
        StringBuilder b = new StringBuilder("Hue lights");
        if (!s.reachable) b.append(", bridge not reachable");
        for (HueRows.Row r : s.rows) {
            b.append(". ").append(r.name.toLowerCase(java.util.Locale.ROOT)).append(' ');
            b.append(r.state.on ? HueRows.litDots(r.state) * 20 + " percent" : "off");
        }
        return b.append('.').toString();
    }
}
