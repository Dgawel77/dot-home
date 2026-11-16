package dev.gawel.dothome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import dev.gawel.dothome.core.FireMode;
import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.TiltInput;
import dev.gawel.dothome.core.ScoreBook;
import dev.gawel.dothome.core.Zones;

/**
 * Per-process hub: one GameWorld shared by every wallpaper engine, so a fold/unfold carries the same run.
 * Only the most recently visible engine drives the simulation; the others just draw.
 * All methods are called on the main thread.
 */
final class GameHub {
    private static GameHub instance;

    private final Context appContext;
    private final PrefsStore prefs;
    private final GameWorld world = new GameWorld(new Rng(System.nanoTime()));
    private ScoreBook scoreBook;
    private final SensorTilt tilt;
    private final List<DotWallpaperService.DotEngine> visible = new ArrayList<>();
    private FireMode fireMode;
    private String zonesJson;
    private boolean widgetReceiversOn;
    private final BroadcastReceiver widgetTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            WidgetUpdater.updateAll(appContext);
        }
    };

    static synchronized GameHub get(Context context) {
        if (instance == null) instance = new GameHub(context.getApplicationContext());
        return instance;
    }

    private GameHub(Context appContext) {
        this.appContext = appContext;
        prefs = new PrefsStore(appContext);
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        fireMode = readFireMode();
        tilt = new SensorTilt(appContext, new TiltInput(), (tx, ty) -> world.setTilt(tx, ty));
        world.setListener(new GameWorld.Listener() {
            @Override
            public void onRockDestroyed() {
                scoreBook.addRock();
                scoreBook.refresh();
            }

            @Override
            public void onShipHit() {}
        });
    }

    GameWorld world() { return world; }

    ScoreBook scoreBook() { return scoreBook; }

    FireMode fireMode() { return fireMode; }

    /** Zones in surface pixels for the given size: zones.json when present and valid, else the defaults. */
    List<float[]> zonesPx(float w, float h) {
        return zonesJson == null ? Zones.defaults(w, h) : Zones.parse(zonesJson, w, h);
    }

    /** True when this engine is the most recently visible one that is actually rendering (has a surface). */
    boolean isActive(DotWallpaperService.DotEngine e) {
        for (int i = visible.size() - 1; i >= 0; i--) {
            if (visible.get(i).isRendering()) return visible.get(i) == e;
        }
        return false;
    }

    void onVisible(DotWallpaperService.DotEngine e) {
        visible.remove(e);
        visible.add(e);
        fireMode = readFireMode();
        zonesJson = readZonesJson();
        // the zone may have changed since the last visible period; keep the persisted keys/values
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        world.setAutoFire(fireMode == FireMode.AUTO);
        WeatherJob.schedule(appContext); // idempotent; restores the periodic job after a reboot
        Weather wx = WeatherRepo.cached(appContext);
        if (wx == null || System.currentTimeMillis() - wx.fetchedAtMs > 30L * 60 * 1000) {
            WeatherJob.refreshSoon(appContext);
        }
        registerWidgetReceivers();
        WidgetUpdater.updateAll(appContext);
        tilt.start(e.displayRotation()); // restart = recalibrate on the first sample
    }

    void onHidden(DotWallpaperService.DotEngine e) {
        visible.remove(e);
        scoreBook.refresh(); // score writes are already applied to prefs; this just rolls the day over
        if (visible.isEmpty()) {
            tilt.stop();
            world.setTilt(0f, 0f);
            world.setAutoFire(false);
            unregisterWidgetReceivers();
        }
    }

    /** TIME_TICK can only be received by a dynamic receiver, so it lives exactly while a wallpaper engine is visible. */
    private void registerWidgetReceivers() {
        if (widgetReceiversOn) return;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try {
            registerReceiverCompat(f);
            widgetReceiversOn = true;
        } catch (RuntimeException ex) {
            Log.w("DotHome", "widget receivers not registered: " + ex);
        }
    }

    private void unregisterWidgetReceivers() {
        if (!widgetReceiversOn) return;
        widgetReceiversOn = false;
        try {
            appContext.unregisterReceiver(widgetTick);
        } catch (RuntimeException ex) {
            Log.w("DotHome", "widget receivers unregister failed: " + ex);
        }
    }

    private void registerReceiverCompat(IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.register(appContext, widgetTick, f);
        } else {
            appContext.registerReceiver(widgetTick, f);
        }
    }

    /** Guarded so the RECEIVER_NOT_EXPORTED overload is only touched on API 33+. */
    private static final class Api33 {
        static void register(Context c, BroadcastReceiver r, IntentFilter f) {
            c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private FireMode readFireMode() {
        try {
            return FireMode.valueOf(prefs.get("fire.mode", FireMode.TAP_COMMAND.name()));
        } catch (IllegalArgumentException ex) {
            return FireMode.TAP_COMMAND;
        }
    }

    private String readZonesJson() {
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, "zones.json");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException ex) {
            Log.w("DotHome", "zones.json unreadable, using defaults: " + ex);
            return null;
        }
    }
}
