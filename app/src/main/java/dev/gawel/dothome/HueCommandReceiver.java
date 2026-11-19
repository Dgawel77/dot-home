package dev.gawel.dothome;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Dot Hue's taps: TOGGLE (the name) or DIM (step 1..20) for one grouped_light and ALL_OFF (every light), sent by
 * HueRepo off the main thread; FLIP (‹ ›) only changes which room a widget shows, with no network.
 */
public class HueCommandReceiver extends BroadcastReceiver {
    static final String TOGGLE = "dev.gawel.dothome.HUE_TOGGLE";
    static final String DIM = "dev.gawel.dothome.HUE_DIM";
    static final String ALL_OFF = "dev.gawel.dothome.HUE_ALL_OFF";
    static final String FLIP = "dev.gawel.dothome.HUE_FLIP";
    static final String EXTRA_LIGHT = "light", EXTRA_DOT = "dot", EXTRA_DELTA = "delta";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (FLIP.equals(action)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            int delta = intent.getIntExtra(EXTRA_DELTA, 0);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && (delta == 1 || delta == -1)) {
                HueWidget.flip(context, id, delta);
            }
            return;
        }
        if (!TOGGLE.equals(action) && !DIM.equals(action) && !ALL_OFF.equals(action)) return;
        PendingResult pr = goAsync();
        try {
            Runnable redraw = HueWidget.redraw(context, false);
            if (ALL_OFF.equals(action)) {
                HueRepo.allOff(context, redraw, pr);
            } else {
                int dot = TOGGLE.equals(action) ? -1 : intent.getIntExtra(EXTRA_DOT, 0);
                HueRepo.tap(context, intent.getStringExtra(EXTRA_LIGHT), dot, redraw, pr);
            }
        } catch (RuntimeException e) {
            Log.w("DotHome", "hue tap failed: " + e.getClass().getSimpleName());
            try {
                pr.finish();
            } catch (IllegalStateException ignored) {
                // already finished
            }
        }
    }
}
