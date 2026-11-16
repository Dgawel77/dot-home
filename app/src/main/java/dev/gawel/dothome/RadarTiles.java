package dev.gawel.dothome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.gawel.dothome.core.GeoProjection;
import dev.gawel.dothome.core.TileMath;

/**
 * Fetches the last hour of NEXRAD n0q composite tiles from the Iowa Environmental Mesonet (public-domain data,
 * attribution requested). Tiles are cached in memory for 5 minutes and the cache is bounded.
 */
final class RadarTiles {
    private static final String TAG = "DotHome";
    private static final String BASE = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/";
    private static final long TTL_MS = 5L * 60 * 1000;
    private static final int MAX_TILES = 144; // 12 frames x 12 tiles, about 36 MB at 256 KB each
    private static final int FRAMES = 12;

    /** One time step: the tiles covering the view, parallel to tx/ty. A null tile failed or is missing. */
    static final class Frame {
        final int minutesAgo, z;
        final Bitmap[] tiles;
        final int[] tx, ty;

        Frame(int minutesAgo, int z, Bitmap[] tiles, int[] tx, int[] ty) {
            this.minutesAgo = minutesAgo;
            this.z = z;
            this.tiles = tiles;
            this.tx = tx;
            this.ty = ty;
        }

        /** ARGB of the radar pixel at lon/lat, or 0 (transparent) when no tile covers it. */
        int argbAt(double lon, double lat) {
            double fx = TileMath.tileXf(lon, z), fy = TileMath.tileYf(lat, z);
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
            for (int i = 0; i < tiles.length; i++) {
                if (tx[i] != ix || ty[i] != iy || tiles[i] == null) continue;
                Bitmap b = tiles[i];
                int px = Math.min(b.getWidth() - 1, (int) ((fx - ix) * b.getWidth()));
                int py = Math.min(b.getHeight() - 1, (int) ((fy - iy) * b.getHeight()));
                return b.getPixel(px, py);
            }
            return 0;
        }
    }

    private static final class Cached {
        final Bitmap bmp; // null = a tile that does not exist (404): remembered so it is not refetched
        final long at;
        Cached(Bitmap bmp, long at) { this.bmp = bmp; this.at = at; }
    }

    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > MAX_TILES; }
    };

    private RadarTiles() {}

    static String layer(int minutesAgo) {
        return minutesAgo == 0 ? "nexrad-n0q-900913" : String.format("nexrad-n0q-900913-m%02dm", minutesAgo);
    }

    static String url(String layer, int z, int x, int y) {
        return BASE + layer + "/" + z + "/" + x + "/" + y + ".png";
    }

    /**
     * Blocking: 12 frames (55 minutes ago ... now) of the tiles covering the view. Returns an empty list when the
     * network is unreachable so the caller can show "Radar unavailable".
     */
    static List<Frame> load(GeoProjection p, int zoom) {
        int n = 1 << zoom;
        double[] lons = {p.lon(0), p.lon(p.w)}, lats = {p.lat(0), p.lat(p.h)};
        int x0 = clamp(TileMath.tileX(lons[0], zoom), n), x1 = clamp(TileMath.tileX(lons[1], zoom), n);
        int y0 = clamp(TileMath.tileY(lats[0], zoom), n), y1 = clamp(TileMath.tileY(lats[1], zoom), n);
        int count = (x1 - x0 + 1) * (y1 - y0 + 1);
        final int[] tx = new int[count], ty = new int[count];
        int k = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) { tx[k] = x; ty[k] = y; k++; }

        final boolean[] down = {false};
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Bitmap[]>> futures = new ArrayList<>();
            int[] minutes = new int[FRAMES];
            for (int f = 0; f < FRAMES; f++) {
                final int minutesAgo = 5 * (FRAMES - 1 - f);
                minutes[f] = minutesAgo;
                futures.add(pool.submit((Callable<Bitmap[]>) () -> {
                    Bitmap[] out = new Bitmap[tx.length];
                    for (int i = 0; i < tx.length; i++) out[i] = tile(layer(minutesAgo), zoom, tx[i], ty[i], down);
                    return out;
                }));
            }
            List<Frame> frames = new ArrayList<>();
            for (int f = 0; f < FRAMES; f++) {
                try {
                    frames.add(new Frame(minutes[f], zoom, futures.get(f).get(), tx, ty));
                } catch (Exception e) {
                    Log.w(TAG, "radar frame failed: " + e);
                }
            }
            if (down[0] && !anyTile(frames)) return new ArrayList<>();
            return frames;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean anyTile(List<Frame> frames) {
        for (Frame f : frames) for (Bitmap b : f.tiles) if (b != null) return true;
        return false;
    }

    private static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    private static Bitmap tile(String layer, int z, int x, int y, boolean[] down) {
        String key = layer + "/" + z + "/" + x + "/" + y;
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Cached e = CACHE.get(key);
            if (e != null && now - e.at < TTL_MS) return e.bmp;
        }
        if (down[0]) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url(layer, z, x, y)).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", "DotHome/0.1 (personal Android app)");
            int code = c.getResponseCode();
            Bitmap bmp = null;
            if (code == 200) {
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } else if (code != 404) {
                return null; // server trouble: draw without this tile, retry next time
            }
            synchronized (CACHE) { CACHE.put(key, new Cached(bmp, now)); }
            return bmp;
        } catch (IOException e) {
            down[0] = true;
            Log.w(TAG, "radar tile failed: " + e);
            return null;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "radar tile decode failed: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
