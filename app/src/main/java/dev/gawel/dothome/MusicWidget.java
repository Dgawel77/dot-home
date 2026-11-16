package dev.gawel.dothome;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import dev.gawel.dothome.core.NowPlaying;

/** The music home-screen widget: shows the chosen media session, hidden when nothing is playing. */
public class MusicWidget extends AppWidgetProvider {
    private static volatile NowPlaying last;

    /** Called by MediaWatcher whenever the chosen session changes. */
    static void update(Context ctx, NowPlaying np) {
        last = np;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, MusicWidget.class));
            if (ids == null) return;
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, WidgetUpdater.musicViews(ctx, mgr, id, np));
                } catch (RuntimeException e) {
                    Log.w("DotHome", "music widget " + id + " update failed: " + e);
                }
            }
        } catch (RuntimeException e) {
            Log.w("DotHome", "music widget update failed: " + e);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        update(context, last);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id, Bundle newOptions) {
        update(context, last);
    }
}
