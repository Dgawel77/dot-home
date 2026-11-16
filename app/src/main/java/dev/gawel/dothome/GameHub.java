package dev.gawel.dothome;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import dev.gawel.dothome.core.FireMode;
import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.IntroGate;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.TiltInput;
import dev.gawel.dothome.core.ScoreBook;

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
    private DotRenderer renderer;
    private long hiddenAtMs = -1;
    private final KeyguardManager keyguard;
    /** When the opening dot plays: the first show after a screen-off and unlock; folds and flips skip it. */
    private final IntroGate introGate = new IntroGate();

    static synchronized GameHub get(Context context) {
        if (instance == null) instance = new GameHub(context.getApplicationContext());
        return instance;
    }

    private GameHub(Context appContext) {
        this.appContext = appContext;
        prefs = new PrefsStore(appContext);
        keyguard = (KeyguardManager) appContext.getSystemService(Context.KEYGUARD_SERVICE);
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        fireMode = readFireMode();
        tilt = new SensorTilt(appContext, new TiltInput(), new SensorTilt.Listener() {
            @Override
            public void onTilt(float gravX, float gravY, float parX, float parY) {
                world.setTilt(gravX, gravY);
                if (renderer != null) renderer.setParallax(parX, parY);
            }

            @Override
            public void onShake() { world.shockwave(); }
        });
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

    /** The renderer that receives parallax updates. */
    void setRenderer(DotRenderer r) { renderer = r; }

    GameWorld world() { return world; }

    ScoreBook scoreBook() { return scoreBook; }

    FireMode fireMode() { return fireMode; }

    /** True when this engine is the most recently visible one that is actually rendering (has a surface). */
    boolean isActive(DotWallpaperService.DotEngine e) {
        for (int i = visible.size() - 1; i >= 0; i--) {
            if (visible.get(i).isRendering()) return visible.get(i) == e;
        }
        return false;
    }

    void onVisible(DotWallpaperService.DotEngine e) {
        if (hiddenAtMs >= 0) { // the shockwave cooldown runs in real time, including while hidden
            world.elapseHidden((SystemClock.elapsedRealtime() - hiddenAtMs) / 1000f);
            hiddenAtMs = -1;
        }
        visible.remove(e);
        visible.add(e);
        IntroGate.Action intro = introGate.visible(keyguardLocked());
        if (intro == IntroGate.Action.PLAY) world.restartIntro();
        else if (intro == IntroGate.Action.HOLD) world.holdIntro(); // one dot until USER_PRESENT plays it
        else world.skipIntro();
        if (renderer != null) renderer.setOutlineRocks(prefs.outlineRocks());
        fireMode = readFireMode();
        // the zone may have changed since the last visible period; keep the persisted keys/values
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        world.setAutoFire(fireMode == FireMode.AUTO);
        WeatherJob.schedule(appContext); // idempotent; restores the periodic job after a reboot
        Weather wx = WeatherRepo.cached(appContext);
        if (wx == null || System.currentTimeMillis() - wx.fetchedAtMs > 30L * 60 * 1000) {
            WeatherJob.refreshSoon(appContext);
        }
        WidgetUpdater.updateAll(appContext);
        tilt.start(e.displayRotation()); // restart = recalibrate on the first sample
    }

    /** Restarts (recalibrates) tilt with the active engine's current rotation. */
    void restartTiltForActive() {
        for (int i = visible.size() - 1; i >= 0; i--) {
            DotWallpaperService.DotEngine e = visible.get(i);
            if (e.isRendering()) {
                tilt.start(e.displayRotation());
                return;
            }
        }
    }

    /** The active engine's surface rotated or resized while visible. */
    void onSurfaceChanged(DotWallpaperService.DotEngine e) {
        if (visible.contains(e) && isActive(e)) tilt.start(e.displayRotation());
    }

    void onHidden(DotWallpaperService.DotEngine e) {
        visible.remove(e);
        scoreBook.refresh(); // score writes are already applied to prefs; this just rolls the day over
        if (visible.isEmpty()) {
            tilt.stop();
            world.setTilt(0f, 0f);
            if (renderer != null) renderer.resetParallax();
            hiddenAtMs = SystemClock.elapsedRealtime();
            world.setAutoFire(false);
        } else {
            restartTiltForActive(); // another engine took over: its rotation may differ
        }
    }

    /** The screen went off: the first show after it comes back on and unlocks plays the opening dot. */
    void onScreenOff() { introGate.screenOff(); }

    /** The keyguard was dismissed: an engine already showing (holding on the first dot) plays the intro now. */
    void onUserPresent() {
        if (introGate.userPresent(!visible.isEmpty()) == IntroGate.Action.PLAY) world.restartIntro();
    }

    private boolean keyguardLocked() {
        try {
            return keyguard != null && keyguard.isKeyguardLocked();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private FireMode readFireMode() {
        try {
            return FireMode.valueOf(prefs.get("fire.mode", FireMode.TAP_TOUCH.name()));
        } catch (IllegalArgumentException ex) {
            return FireMode.TAP_TOUCH;
        }
    }

}
