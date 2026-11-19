package dev.gawel.dothome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Dot Hue's taps: TOGGLE (a name) or DIM (dot 1..5) for one grouped_light, sent by HueRepo off the main thread. */
public class HueCommandReceiver extends BroadcastReceiver {
    static final String TOGGLE = "dev.gawel.dothome.HUE_TOGGLE";
    static final String DIM = "dev.gawel.dothome.HUE_DIM";
    static final String EXTRA_LIGHT = "light", EXTRA_DOT = "dot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String light = intent.getStringExtra(EXTRA_LIGHT);
        int dot;
        switch (intent.getAction()) {
            case TOGGLE: dot = -1; break;
            case DIM: dot = intent.getIntExtra(EXTRA_DOT, 0); break;
            default: return;
        }
        PendingResult pr = goAsync();
        try {
            HueRepo.tap(context, light, dot, HueWidget.redraw(context, false), pr);
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
