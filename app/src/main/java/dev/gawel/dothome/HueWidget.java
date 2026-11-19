package dev.gawel.dothome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import dev.gawel.dothome.core.HueLayout;
import dev.gawel.dothome.core.HueRows;

/**
 * Dot Hue, a 1 x 4 strip: one picked room at a time (‹ › flip through them, per widget), its name (tap: on/off),
 * ALL OFF (every light in the home), and 20 dots below (tap dot n: n x 5%), like the music widget's seek zones.
 * The art is one bitmap (HueArt); transparent tap cells are placed over it at runtime in HueLayout's geometry, one
 * layout per reported widget size (needs API 31; older devices get the art without taps). PAIR HUE IN DOT HOME
 * until paired, PICK ROOMS IN DOT HOME until rooms are picked. State comes from {@link HueRepo}.
 */
public class HueWidget extends AppWidgetProvider {
    private static final String TAG = "DotHome";
    /** The 4 x 1 cell default placement, used when the launcher reports no size. */
    private static final int DEFAULT_W_DP = 300, DEFAULT_H_DP = 95;
    /** Which picked room each widget shows: "hue.idx.<appWidgetId>". */
    private static final String K_INDEX = "hue.idx.";
    /** At most this many sizes get their own layout (each is a bitmap in the update). */
    private static final int MAX_SIZES = 4;

    private static final int[] DOT_IDS = {
        R.id.hue_d0, R.id.hue_d1, R.id.hue_d2, R.id.hue_d3, R.id.hue_d4, R.id.hue_d5, R.id.hue_d6, R.id.hue_d7,
        R.id.hue_d8, R.id.hue_d9, R.id.hue_d10, R.id.hue_d11, R.id.hue_d12, R.id.hue_d13, R.id.hue_d14, R.id.hue_d15,
        R.id.hue_d16, R.id.hue_d17, R.id.hue_d18, R.id.hue_d19};

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

    @Override
    public void onDeleted(Context context, int[] ids) {
        PrefsStore p = new PrefsStore(context);
        for (int id : ids) p.remove(K_INDEX + id);
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

    private static int index(Context ctx, int id) {
        try {
            return Integer.parseInt(new PrefsStore(ctx).get(K_INDEX + id, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ‹ (-1) or › (+1) on widget id: steps through the picked rooms (wrapping) and redraws. Main thread, no network. */
    static void flip(Context ctx, int id, int delta) {
        int n = HueRepo.current(ctx).rows.size();
        new PrefsStore(ctx).put(K_INDEX + id, String.valueOf(HueRows.flip(index(ctx, id), delta, n)));
        push(ctx.getApplicationContext(), true);
    }

    /** Draws every Dot Hue widget when what it shows changed since the last push, or always when force. */
    static synchronized void push(Context ctx, boolean force) { // taps push on the main thread, refreshes on the executor
        try {
            int[] ids = ids(ctx);
            if (ids.length == 0) return;
            HueRepo.Shown s = HueRepo.current(ctx);
            boolean backdrop = WidgetArt.backdropOn(ctx);
            StringBuilder k = new StringBuilder(key(s, backdrop));
            for (int id : ids) k.append('|').append(id).append('=').append(HueRows.index(index(ctx, id), s.rows.size()));
            String key = k.toString();
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

    /**
     * The views: on API 31+ one layout per size the launcher reports (portrait and landscape, cover and main), so
     * the tap cells always sit over what is drawn at the size actually shown; else one for MIN_WIDTH x MAX_HEIGHT.
     */
    @SuppressWarnings("deprecation") // the untyped getParcelableArrayList: the typed one needs API 33
    private static RemoteViews views(Context ctx, AppWidgetManager mgr, int id, HueRepo.Shown s, boolean backdrop) {
        Bundle o = mgr.getAppWidgetOptions(id);
        int row = HueRows.index(index(ctx, id), s.rows.size());
        if (Build.VERSION.SDK_INT >= 31 && o != null) {
            ArrayList<SizeF> sizes = o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                Map<SizeF, RemoteViews> bySize = new HashMap<>();
                for (SizeF sz : sizes) {
                    if (bySize.size() >= MAX_SIZES || sz == null || bySize.containsKey(sz)) continue;
                    if (sz.getWidth() <= 0 || sz.getHeight() <= 0) continue;
                    bySize.put(sz, sized(ctx, o, id, sz.getWidth(), sz.getHeight(), s, row, backdrop));
                }
                if (!bySize.isEmpty()) return new RemoteViews(bySize);
            }
        }
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return sized(ctx, o, id, wDp <= 0 ? DEFAULT_W_DP : wDp, hDp <= 0 ? DEFAULT_H_DP : hDp, s, row, backdrop);
    }

    /** The layout for a widget shown at wDp x hDp: card, art and tap cells all in that size's geometry. */
    private static RemoteViews sized(Context ctx, Bundle o, int id, float wDp, float hDp, HueRepo.Shown s, int row,
                                     boolean backdrop) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_hue);
        float density = ctx.getResources().getDisplayMetrics().density;
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
        rv.setImageViewBitmap(R.id.hue_img, HueArt.render(ctx, w, h, eff, s, row));
        rv.setContentDescription(R.id.hue_img, description(s, row));
        attachTaps(ctx, rv, id, s, row, w / eff, h / eff);
        return rv;
    }

    /** Places the tap cells over what HueArt drew; hidden when there is no room to act on (or before API 31). */
    private static void attachTaps(Context ctx, RemoteViews rv, int id, HueRepo.Shown s, int row, float wDp,
                                   float hDp) {
        boolean rows = s.mode == HueRepo.Mode.ROWS && !s.rows.isEmpty() && Build.VERSION.SDK_INT >= 31;
        if (!rows) {
            for (int v : new int[] {R.id.hue_prev, R.id.hue_name, R.id.hue_next, R.id.hue_off, R.id.hue_dots}) {
                rv.setViewVisibility(v, View.GONE);
            }
            return;
        }
        HueRows.Row r = s.rows.get(row);
        float nameW = HueArt.nameCellDp(ctx, r.name, wDp);
        boolean flips = s.rows.size() > 1;
        String name = r.name.toLowerCase(Locale.ROOT);

        place(rv, R.id.hue_name, HueLayout.nameCell(wDp, hDp, nameW));
        rv.setOnClickPendingIntent(R.id.hue_name, command(ctx, HueCommandReceiver.TOGGLE, id, "name", r.groupedLightId, 0, 0));
        rv.setContentDescription(R.id.hue_name, name + (r.state.on ? ": on, tap to turn off" : ": off, tap to turn on"));

        place(rv, R.id.hue_off, HueLayout.offCell(wDp, hDp));
        rv.setOnClickPendingIntent(R.id.hue_off, command(ctx, HueCommandReceiver.ALL_OFF, id, "off", null, 0, 0));
        rv.setContentDescription(R.id.hue_off, "turn every light off");

        if (flips) {
            place(rv, R.id.hue_prev, HueLayout.prevCell(wDp, hDp));
            place(rv, R.id.hue_next, HueLayout.nextCell(wDp, hDp, nameW));
            rv.setOnClickPendingIntent(R.id.hue_prev, command(ctx, HueCommandReceiver.FLIP, id, "prev", null, 0, -1));
            rv.setOnClickPendingIntent(R.id.hue_next, command(ctx, HueCommandReceiver.FLIP, id, "next", null, 0, 1));
            rv.setContentDescription(R.id.hue_prev, "previous room");
            rv.setContentDescription(R.id.hue_next, "next room");
        } else {
            rv.setViewVisibility(R.id.hue_prev, View.GONE);
            rv.setViewVisibility(R.id.hue_next, View.GONE);
        }

        place(rv, R.id.hue_dots, HueLayout.dotsRow(wDp, hDp));
        for (int i = 0; i < HueRows.STEPS; i++) {
            int step = i + 1;
            rv.setOnClickPendingIntent(DOT_IDS[i],
                command(ctx, HueCommandReceiver.DIM, id, "d" + i, r.groupedLightId, step, 0));
            rv.setContentDescription(DOT_IDS[i], name + " " + (step * 5) + " percent");
        }
    }

    /** Sizes and places a tap cell at rect {x, y, w, h} dp from the widget's top left, and shows it. */
    private static void place(RemoteViews rv, int view, float[] rect) {
        int u = TypedValue.COMPLEX_UNIT_DIP;
        rv.setViewVisibility(view, View.VISIBLE);
        rv.setViewLayoutWidth(view, rect[2], u);
        rv.setViewLayoutHeight(view, rect[3], u);
        rv.setViewLayoutMargin(view, RemoteViews.MARGIN_START, rect[0], u);
        rv.setViewLayoutMargin(view, RemoteViews.MARGIN_TOP, rect[1], u);
    }

    /**
     * A tap's broadcast. The data URI (widget id + cell) makes each PendingIntent distinct, so one widget's extras
     * never replace another's.
     */
    private static PendingIntent command(Context ctx, String action, int widgetId, String cell, String light, int step,
                                         int delta) {
        Intent in = new Intent(action).setClass(ctx, HueCommandReceiver.class)
            .setData(Uri.parse("dothome://hue/" + widgetId + "/" + cell))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(HueCommandReceiver.EXTRA_DOT, step)
            .putExtra(HueCommandReceiver.EXTRA_DELTA, delta);
        if (light != null) in.putExtra(HueCommandReceiver.EXTRA_LIGHT, light);
        return PendingIntent.getBroadcast(ctx, 0, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String description(HueRepo.Shown s, int row) {
        if (s.mode == HueRepo.Mode.UNPAIRED) return "Hue lights: pair your Hue bridge in Dot Home";
        if (s.mode == HueRepo.Mode.NO_ROOMS || s.rows.isEmpty()) return "Hue lights: pick rooms in Dot Home";
        HueRows.Row r = s.rows.get(row);
        StringBuilder b = new StringBuilder("Hue lights, ").append(r.name.toLowerCase(Locale.ROOT)).append(' ');
        b.append(r.state.on ? HueRows.litDots(r.state) * 5 + " percent" : "off");
        if (!s.reachable) b.append(", bridge not reachable");
        return b.append('.').toString();
    }
}
