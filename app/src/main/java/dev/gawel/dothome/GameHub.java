package dev.gawel.dothome;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import dev.gawel.dothome.core.FireMode;
import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.IntroGate;
import dev.gawel.dothome.core.MilestoneShapes;
import dev.gawel.dothome.core.Milestones;
import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.Sky;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherFx;
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
    private Milestones milestones;
    /** The four milestone shapes, built once so a kill allocates nothing. */
    private static final float[][] SHAPES = new float[Milestones.COUNTS.length][];
    static {
        for (int i = 0; i < SHAPES.length; i++) SHAPES[i] = MilestoneShapes.points(Milestones.COUNTS[i]);
    }
    private final SensorTilt tilt;
    private final List<DotWallpaperService.DotEngine> visible = new ArrayList<>();
    private FireMode fireMode;
    private DotRenderer renderer;
    private long hiddenAtMs = -1;
    private final BeatPulse beat = new BeatPulse();
    private final KeyguardManager keyguard;
    /** When the opening dot plays: the first show after a screen-off and unlock; folds and flips skip it. */
    private final IntroGate introGate = new IntroGate();
    /** True while the intro waits on its first dot for the unlock; the frame loop then polls the keyguard. */
    private boolean holdingIntro;
    private long lastKeyguardPollNanos;
    private static final long WEATHER_REFRESH_MS = 10L * 60 * 1000;
    private long weatherFxAtMs;
    /** The sun's times from the cached weather (0 = unknown), for the day/night ramp. */
    private long sunriseMs, sunsetMs;
    /** The fetchedAtMs of the weather the game currently shows (0 = none); a newer fetch is re-applied at once. */
    private long appliedFetchedAtMs;

    private static final String TAG = "GameHub";
    private boolean batteryOn;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { applyBattery(intent); }
    };

    /** Guarded so the RECEIVER_NOT_EXPORTED overload is only touched on API 33+. */
    private static final class Api33 {
        static Intent register(Context c, BroadcastReceiver r, IntentFilter f) {
            return c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    /** Listens for battery changes while anything is visible; idempotent. The sticky intent gives the initial state. */
    private void setBattery(boolean on) {
        if (on == batteryOn) return;
        try {
            if (on) {
                IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent sticky = Build.VERSION.SDK_INT >= 33
                        ? Api33.register(appContext, batteryReceiver, f)
                        : appContext.registerReceiver(batteryReceiver, f);
                batteryOn = true;
                applyBattery(sticky);
            } else {
                batteryOn = false;
                appContext.unregisterReceiver(batteryReceiver);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "battery receiver toggle failed: " + ex);
        }
    }

    private void applyBattery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        boolean charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        world.setPower(charging, level >= 0 && scale > 0 ? level * 100 / scale : -1);
    }

    static synchronized GameHub get(Context context) {
        if (instance == null) instance = new GameHub(context.getApplicationContext());
        return instance;
    }

    private GameHub(Context appContext) {
        this.appContext = appContext;
        prefs = new PrefsStore(appContext);
        keyguard = (KeyguardManager) appContext.getSystemService(Context.KEYGUARD_SERVICE);
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        milestones = new Milestones(prefs);
        fireMode = readFireMode();
        tilt = new SensorTilt(appContext, new TiltInput(), new SensorTilt.Listener() {
            @Override
            public void onTilt(float gravX, float gravY, float parX, float parY) {
                world.setTilt(gravX, gravY);
                if (renderer != null) renderer.setParallax(parX, parY);
            }

            @Override
            public void onShake() { world.barrage(); }
        });
        world.setListener(new GameWorld.Listener() {
            @Override
            public void onRockDestroyed() {
                scoreBook.addRock();
                scoreBook.refresh();
                int m = milestones.reached(scoreBook.today(), scoreBook.day());
                if (m > 0) world.formShape(shape(m));
            }

            @Override
            public void onShipHit() {}
        });
    }

    /** The renderer that receives parallax updates. */
    void setRenderer(DotRenderer r) { renderer = r; }

    /** The wallpaper's renderer (it holds the live plumes and sparks), or null before any engine was created. */
    DotRenderer renderer() { return renderer; }

    GameWorld world() { return world; }

    private static float[] shape(int milestone) {
        for (int i = 0; i < Milestones.COUNTS.length; i++) {
            if (Milestones.COUNTS[i] != milestone) continue;
            return SHAPES[i];
        }
        return SHAPES[0];
    }

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
        if (hiddenAtMs >= 0) { // the barrage cooldown runs in real time, including while hidden
            world.elapseHidden((SystemClock.elapsedRealtime() - hiddenAtMs) / 1000f);
            hiddenAtMs = -1;
        }
        visible.remove(e);
        visible.add(e);
        IntroGate.Action intro = introGate.visible(keyguardLocked());
        holdingIntro = intro == IntroGate.Action.HOLD;
        if (intro == IntroGate.Action.PLAY) world.restartIntro();
        else if (holdingIntro) world.holdIntro(); // one dot until USER_PRESENT (or the keyguard poll) plays it
        else world.skipIntro();
        if (renderer != null) renderer.setOutlineRocks(prefs.outlineRocks());
        fireMode = readFireMode();
        // the zone may have changed since the last visible period; keep the persisted keys/values
        scoreBook = new ScoreBook(prefs, System::currentTimeMillis, TimeZone.getDefault());
        world.setAutoFire(fireMode == FireMode.AUTO);
        WeatherJob.schedule(appContext); // idempotent; restores the periodic job after a reboot
        FeedJob.schedule(appContext); // likewise for the live feeds (Dot Bears)
        Weather wx = WeatherRepo.cached(appContext);
        if (wx == null || System.currentTimeMillis() - wx.fetchedAtMs > 30L * 60 * 1000) {
            WeatherJob.refreshSoon(appContext);
        }
        refreshWeatherFx(System.currentTimeMillis());
        WidgetUpdater.updateAll(appContext);
        tilt.start(e.displayRotation()); // restart = recalibrate on the first sample
        setBattery(true);
    }

    /** Once a second from the active engine's frame loop; later tasks add their own periodic work here. */
    void tick1s(long nowMs) {
        scoreBook.refresh(); // rolls the day over at midnight while visible
        if (nowMs - weatherFxAtMs >= WEATHER_REFRESH_MS || WeatherRepo.lastFetchedAtMs() != appliedFetchedAtMs) {
            refreshWeatherFx(nowMs);
        }
        world.setDayness(Sky.dayness(nowMs, sunriseMs, sunsetMs));
        updateBeat();
    }

    /** Capture runs only while the pref is on, the permission is granted, something shows and music plays. */
    private void updateBeat() {
        NowPlaying np = MusicWidget.last();
        boolean want = "true".equals(prefs.get(PrefsStore.KEY_BEAT, "false"))
                && appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                && !visible.isEmpty() && np != null && np.playing;
        if (want) beat.start(); else beat.stop();
    }

    /** The current beat pulse, 0..1 (0 when not capturing). */
    float beatLevel(long nowMs) { return beat.level(nowMs); }

    /** Recomputes the game's weather from the cache (it allocates, so only on a refresh, never per frame). */
    private void refreshWeatherFx(long nowMs) {
        weatherFxAtMs = nowMs;
        Weather wx = WeatherRepo.cached(appContext);
        appliedFetchedAtMs = wx == null ? 0 : wx.fetchedAtMs;
        world.setWeather(WeatherFx.from(wx, nowMs));
        sunriseMs = wx == null ? 0 : wx.sunriseMs;
        sunsetMs = wx == null ? 0 : wx.sunsetMs;
        world.setDayness(Sky.dayness(nowMs, sunriseMs, sunsetMs));
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
        if (visible.isEmpty()) holdingIntro = false; // onVisible re-decides on the next show
        scoreBook.refresh(); // score writes are already applied to prefs; this just rolls the day over
        if (visible.isEmpty()) {
            tilt.stop();
            beat.stop();
            setBattery(false);
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
        if (introGate.userPresent(!visible.isEmpty()) == IntroGate.Action.PLAY) {
            holdingIntro = false;
            world.restartIntro();
        }
    }

    /**
     * Safety valve for a USER_PRESENT that never arrives: while holding the intro, checks the keyguard about
     * every 0.5 s and plays the intro once it is gone. Called from the frame loop; allocates nothing.
     */
    void pollKeyguard(long frameTimeNanos) {
        if (!holdingIntro) return;
        if (frameTimeNanos - lastKeyguardPollNanos < 500_000_000L) return;
        lastKeyguardPollNanos = frameTimeNanos;
        if (!keyguardLocked()) onUserPresent();
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
