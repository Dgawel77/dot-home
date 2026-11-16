package dev.gawel.dothome;

import android.graphics.Canvas;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import java.util.List;

import dev.gawel.dothome.core.GameWorld;

/** The Dot Home live wallpaper: a Choreographer-driven render loop that runs only while visible. */
public class DotWallpaperService extends WallpaperService {
    static final String TAG = "DotHome";

    private DotRenderer renderer;

    @Override
    public Engine onCreateEngine() {
        if (renderer == null) renderer = new DotRenderer(this);
        return new DotEngine();
    }

    final class DotEngine extends Engine implements Choreographer.FrameCallback {
        private final GameHub hub = GameHub.get(DotWallpaperService.this);
        private boolean visible, surfaceValid, rendering;
        private int width, height;
        private long lastNanos;
        private List<float[]> zones;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            surfaceValid = true;
            maybeStart();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w;
            height = h;
            zones = hub.zonesPx(w, h);
            boolean wasRendering = rendering;
            surfaceValid = true;
            maybeStart();
            if (wasRendering) Log.i(TAG, "render on " + w + "x" + h);
            fitWorld();
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
                if (width > 0 && height > 0) zones = hub.zonesPx(width, height);
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

        /** The shared world follows the active engine's size; inactive engines never resize it. */
        private void fitWorld() {
            GameWorld world = hub.world();
            if (width > 0 && height > 0 && hub.isActive(this)
                    && (world.width() != width || world.height() != height)) {
                world.resize(width, height);
                zones = hub.zonesPx(width, height);
            }
        }

        private void maybeStart() {
            if (rendering || !visible || !surfaceValid || width <= 0 || height <= 0) return;
            rendering = true;
            lastNanos = 0;
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
            GameWorld world = hub.world();
            fitWorld(); // covers control passing back to this engine when another one hides
            if (world.width() > 0) {
                if (hub.isActive(this)) {
                    world.step(dt);
                    renderer.advance(world, dt);
                }
                drawFrame(world);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void drawFrame(GameWorld world) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.getSurface().isValid() ? holder.lockHardwareCanvas() : null;
                if (c != null) renderer.draw(c, world, zones, hub.scoreBook());
            } catch (IllegalStateException | IllegalArgumentException e) {
                Log.w(TAG, "draw failed: " + e);
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (IllegalStateException | IllegalArgumentException e) {
                        Log.w(TAG, "post failed: " + e);
                    }
                }
            }
        }
    }
}
