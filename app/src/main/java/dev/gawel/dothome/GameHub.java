package dev.gawel.dothome;

import android.content.Context;
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
    private final ScoreBook scoreBook;
    private final List<DotWallpaperService.DotEngine> visible = new ArrayList<>();
    private FireMode fireMode;
    private String zonesJson;

    static synchronized GameHub get(Context context) {
        if (instance == null) instance = new GameHub(context.getApplicationContext());
        return instance;
    }

    private GameHub(Context appContext) {
        this.appContext = appContext;
        prefs = new PrefsStore(appContext);
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        fireMode = readFireMode();
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

    /** True when this engine is the one that should call world.step(). */
    boolean isActive(DotWallpaperService.DotEngine e) {
        return !visible.isEmpty() && visible.get(visible.size() - 1) == e;
    }

    void onVisible(DotWallpaperService.DotEngine e) {
        visible.remove(e);
        visible.add(e);
        fireMode = readFireMode();
        zonesJson = readZonesJson();
        scoreBook.refresh();
        // Task 6: start SensorTilt here (tilt -> world.setTilt) for the active engine.
    }

    void onHidden(DotWallpaperService.DotEngine e) {
        visible.remove(e);
        scoreBook.refresh(); // score writes are already applied to prefs; this just rolls the day over
        // Task 6: stop SensorTilt here when no engine remains visible.
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
