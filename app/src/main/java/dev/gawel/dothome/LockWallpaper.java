package dev.gawel.dothome;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.LockGate;

/**
 * Beta: on screen-off, when the Setup switch is on, sets the lock wallpaper to a dimmed still of the current
 * frame, at most once a minute and only if the frame changed. Rendering happens on the main thread (the renderer's
 * pools are main-thread state); only setBitmap and the recycle run on the worker.
 */
final class LockWallpaper {
    private static final String TAG = "DotHome";
    private static final float CLEAR_TOP = 0.42f, CLEAR_BOTTOM = 0.18f;

    /** Per process. */
    private static final LockGate GATE = new LockGate();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "lock-wallpaper"));
    private static boolean logged;

    private LockWallpaper() {}

    /** Call on the main thread. Never throws. */
    static void onScreenOff(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            PrefsStore prefs = new PrefsStore(app);
            if (!"true".equals(prefs.get(PrefsStore.KEY_LOCK_LIVE, "false"))) return;
            GameHub hub = GameHub.get(app);
            GameWorld world = hub.world();
            if (world == null || !(world.width() > 0 && world.height() > 0)) return;
            long now = System.currentTimeMillis();
            GameWorld.Ship ship = world.ship();
            int fp = LockGate.fingerprint(hub.scoreBook().today(), ship.x, ship.y, world.width(), world.height());
            if (!GATE.shouldPush(now, fp)) return;
            Point size = matchingSize(app, world.width() / world.height());
            if (size == null) return;
            DotRenderer renderer = hub.renderer();
            if (renderer == null) renderer = new DotRenderer(app);
            renderer.setOutlineRocks(prefs.outlineRocks());
            final WallpaperManager wm = WallpaperManager.getInstance(app); // before the render: nothing can fail between it and the hand-off
            final Bitmap bmp = renderer.renderStill(world, size.x, size.y, CLEAR_TOP, CLEAR_BOTTOM);
            // recorded before setBitmap on purpose: a failing set must not be retried on every screen-off
            GATE.pushed(now, fp);
            try {
                WORKER.execute(() -> {
                    try {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK);
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        Log.w(TAG, "lock wallpaper failed: " + e);
                    } finally {
                        bmp.recycle();
                    }
                });
            } catch (RuntimeException e) {
                bmp.recycle(); // the worker never took it
                throw e;
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            if (!logged) {
                logged = true;
                Log.w(TAG, "lock wallpaper skipped: " + e);
            }
        }
    }

    /** The built-in display whose aspect is closest to the world's, at its real size (as LockStills finds it). */
    private static Point matchingSize(Context app, float aspect) {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        Point best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Display d : dm.getDisplays()) {
            if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) continue;
            Point p = LockStills.realSize(d);
            if (p.x <= 0 || p.y <= 0) continue;
            float diff = Math.abs((float) p.x / p.y - aspect);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        return best;
    }
}
