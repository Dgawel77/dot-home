package dev.gawel.dothome;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.PowerManager;
import android.view.Surface;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;

import dev.gawel.dothome.core.FireMode;
import dev.gawel.dothome.core.GameWorld;

/** The Dot Home live wallpaper: a Choreographer-driven render loop that runs only while visible. */
public class DotWallpaperService extends WallpaperService {
    static final String TAG = "DotHome";

    private DotRenderer renderer;
    private boolean tickOn;

    /** TIME_TICK only reaches dynamic receivers, so the clock widget ticks while the screen is on. */
    private final BroadcastReceiver widgetTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            WidgetUpdater.updateAll(getApplicationContext());
        }
    };

    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                setTick(true);
                WidgetUpdater.updateAll(getApplicationContext());
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                setTick(false);
                // arm on screen-off, not screen-on: an engine can turn visible before SCREEN_ON arrives
                GameHub.get(DotWallpaperService.this).onScreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                GameHub.get(DotWallpaperService.this).onUserPresent();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            register(screenState, f, true);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) setTick(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "screen receivers not registered: " + e);
        }
    }

    @Override
    public void onDestroy() {
        setTick(false);
        try {
            unregisterReceiver(screenState);
        } catch (RuntimeException e) {
            Log.w(TAG, "screen receiver unregister failed: " + e);
        }
        super.onDestroy();
    }

    private void setTick(boolean on) {
        if (on == tickOn) return;
        try {
            if (on) {
                register(widgetTick, new IntentFilter(Intent.ACTION_TIME_TICK));
            } else {
                unregisterReceiver(widgetTick);
            }
            tickOn = on;
        } catch (RuntimeException e) {
            Log.w(TAG, "time tick toggle failed: " + e);
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        register(r, f, false);
    }

    /** exported: USER_PRESENT is sent by SystemUI, so a not-exported receiver may never see it (all three actions are protected). */
    private void register(BroadcastReceiver r, IntentFilter f, boolean exported) {
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.register(this, r, f, exported);
        } else {
            registerReceiver(r, f);
        }
    }

    /** Guarded so the RECEIVER_NOT_EXPORTED overload is only touched on API 33+. */
    private static final class Api33 {
        static void register(Context c, BroadcastReceiver r, IntentFilter f, boolean exported) {
            c.registerReceiver(r, f, exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
        }
    }

    /** Guarded so Surface.setFrameRate is only touched on API 30+. */
    private static final class Api30 {
        static void hint60(Surface s) {
            if (s != null && s.isValid()) s.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }
    }

    private static void frameRateHint(SurfaceHolder holder) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Api30.hint60(holder.getSurface());
        } catch (RuntimeException e) {
            Log.w(TAG, "frame rate hint failed: " + e);
        }
    }

    @Override
    public Engine onCreateEngine() {
        if (renderer == null) renderer = new DotRenderer(this);
        GameHub.get(this).setRenderer(renderer);
        return new DotEngine();
    }

    final class DotEngine extends Engine implements Choreographer.FrameCallback {
        private final GameHub hub = GameHub.get(DotWallpaperService.this);
        private boolean visible, surfaceValid, rendering;
        private int width, height;
        private long lastNanos;
        private long lastSecond;
        private long lastRefreshNanos, lastErrLogMs;
        private float downX, downY;
        private long downMs;
        private boolean touchValid;
        /** Status bar / cutout height in px for this engine's display; 0 until known. */
        private float topInset = -1;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            surfaceValid = true;
            frameRateHint(holder);
            maybeStart();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w;
            height = h;
            boolean wasRendering = rendering;
            surfaceValid = true;
            maybeStart();
            if (wasRendering) Log.i(TAG, "render on " + w + "x" + h);
            frameRateHint(holder);
            fitWorld();
            if (visible) hub.onSurfaceChanged(this); // rotation changed: recalibrate tilt axes
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceValid = false;
            stopRendering();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            if (isVisible == visible) return;
            visible = isVisible;
            if (isVisible) {
                hub.onVisible(this);
                Log.i(TAG, "fire mode=" + hub.fireMode());
                setTouchEventsEnabled(hub.fireMode() == FireMode.TAP_TOUCH);
                maybeStart();
                fitWorld();
            } else {
                stopRendering();
                hub.onHidden(this);
            }
        }

        @Override
        public void onDestroy() {
            surfaceValid = false;
            stopRendering();
            if (visible) {
                visible = false;
                hub.onHidden(this);
            }
        }

        boolean isRendering() { return rendering; }

        /** Surface.ROTATION_* of the display this engine is on. */
        @SuppressWarnings("deprecation")
        int displayRotation() {
            Display d = null;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    d = getDisplayContext().getDisplay();
                } catch (RuntimeException e) {
                    d = null;
                }
            }
            if (d == null) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) d = wm.getDefaultDisplay();
            }
            return d == null ? 0 : d.getRotation();
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            boolean fired = false;
            if (WallpaperManager.COMMAND_TAP.equals(action) && hub.fireMode() == FireMode.TAP_COMMAND
                    && visible && hub.isActive(this)) {
                hub.world().aim(x, y);
                fired = true;
            }
            Log.i(TAG, "onCommand action=" + action + " x=" + x + " y=" + y + " fired=" + fired);
            return null;
        }

        @Override
        public void onTouchEvent(MotionEvent ev) {
            if (hub.fireMode() != FireMode.TAP_TOUCH) return;
            if (!hub.isActive(this)) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) Log.i(TAG, "touch ignored (inactive engine)");
                return;
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    Log.i(TAG, "touch DOWN x=" + ev.getX() + " y=" + ev.getY());
                    touchValid = true;
                    downX = ev.getX();
                    downY = ev.getY();
                    downMs = ev.getEventTime();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    touchValid = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float slop = ViewConfiguration.get(DotWallpaperService.this).getScaledTouchSlop();
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    if (dx * dx + dy * dy >= slop * slop) touchValid = false;
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    float slop = ViewConfiguration.get(DotWallpaperService.this).getScaledTouchSlop();
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    boolean fired = false;
                    if (touchValid && ev.getPointerCount() == 1 && dx * dx + dy * dy < slop * slop
                            && ev.getEventTime() - downMs < 250) {
                        hub.world().aim(ev.getX(), ev.getY());
                        fired = true;
                    }
                    Log.i(TAG, "touch UP x=" + ev.getX() + " y=" + ev.getY() + " fired=" + fired);
                    touchValid = false;
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    Log.i(TAG, "touch CANCEL");
                    touchValid = false;
                    break;
                default:
                    touchValid = false;
            }
        }

        /** The shared world follows the active engine's size; inactive engines never resize it. */
        private void fitWorld() {
            GameWorld world = hub.world();
            if (width > 0 && height > 0 && hub.isActive(this)
                    && (world.width() != width || world.height() != height)) {
                world.resize(width, height);
            }
        }

        private void maybeStart() {
            if (rendering || !visible || !surfaceValid || width <= 0 || height <= 0) return;
            rendering = true;
            lastNanos = 0;
            lastSecond = 0;
            Log.i(TAG, "render on " + width + "x" + height);
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void stopRendering() {
            if (!rendering) return;
            rendering = false;
            Choreographer.getInstance().removeFrameCallback(this);
            Log.i(TAG, "render off");
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!rendering) return;
            float dt = lastNanos == 0 ? 0f : (frameTimeNanos - lastNanos) / 1e9f;
            lastNanos = frameTimeNanos;
            long sec = System.currentTimeMillis() / 1000;
            if (sec != lastSecond) { // the clock widget's seconds tick while the home screen shows
                lastSecond = sec;
                WidgetUpdater.updateSeconds(getApplicationContext());
            }
            GameWorld world = hub.world();
            fitWorld(); // covers control passing back to this engine when another one hides
            if (world.width() > 0) {
                if (hub.isActive(this)) {
                    hub.pollKeyguard(frameTimeNanos);
                    if (frameTimeNanos - lastRefreshNanos >= 1_000_000_000L) {
                        lastRefreshNanos = frameTimeNanos;
                        hub.tick1s(System.currentTimeMillis());
                    }
                    world.step(dt);
                    renderer.advance(world, dt);
                }
                drawFrame(world);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            if (insets == null) return;
            int top;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            if (top > 0) topInset = top;
        }

        /** The inset from the window insets, else the framework status_bar_height dimen. */
        private float topInset() {
            if (topInset >= 0) return topInset;
            int id = Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android");
            topInset = id > 0 ? Resources.getSystem().getDimensionPixelSize(id) : 0f;
            return topInset;
        }

        private void drawFrame(GameWorld world) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                if (holder.getSurface().isValid()) c = holder.lockHardwareCanvas();
            } catch (IllegalStateException | IllegalArgumentException e) {
                logRateLimited("lock failed: " + e);
            }
            if (c == null) return;
            try {
                renderer.draw(c, world, hub.scoreBook(), topInset());
            } finally {
                try {
                    holder.unlockCanvasAndPost(c);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    logRateLimited("post failed: " + e);
                }
            }
        }

        private void logRateLimited(String msg) {
            long now = System.currentTimeMillis();
            if (now - lastErrLogMs >= 5000) {
                lastErrLogMs = now;
                Log.w(TAG, msg);
            }
        }
    }
}
