package dev.gawel.dothome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.gawel.dothome.core.GameWorld;

/** Exports black-and-white stills of the current world, one per built-in display, for use as lock wallpapers. */
final class LockStills {
    private static final String TAG = "DotHome";
    private static final float DIM = 0.62f;
    private static final float CLEAR_TOP = 0.42f;
    private static final String REL_DIR = "Pictures/DotHome";

    interface Callback { void onDone(String message); }

    private LockStills() {}

    /** Call on the main thread. Renders here, compresses and writes on a worker, then reports on the main thread. */
    static void export(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        final List<String> names = new ArrayList<>();
        final List<Bitmap> bitmaps = new ArrayList<>();
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display[] displays = dm.getDisplays();
            GameWorld world = GameHub.get(app).world();
            if (!(world.width() > 0 && world.height() > 0)) {
                Display def = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (def == null && displays.length > 0) def = displays[0];
                Point p = realSize(def);
                world.resize(p.x, p.y);
            }
            DotRenderer renderer = new DotRenderer(app);
            for (Display d : displays) {
                if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) continue; // not a built-in screen
                Point p = realSize(d);
                if (p.x <= 0 || p.y <= 0) continue;
                String name = (float) p.x / p.y < 0.6f ? "lock-cover.png" : "lock-main.png";
                if (names.contains(name)) continue; // e.g. a virtual display with the same shape
                names.add(name);
                bitmaps.add(renderer.renderStill(world, p.x, p.y, DIM, CLEAR_TOP));
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "lock still render failed: " + e);
            cb.onDone("Export failed: " + e.getMessage());
            return;
        }
        if (names.isEmpty()) {
            cb.onDone("No displays found");
            return;
        }
        if (names.size() == 1) Log.i(TAG, "only one display reported; exporting " + names.get(0));
        new Thread(() -> {
            String msg;
            try {
                String where = null;
                for (int i = 0; i < names.size(); i++) where = save(app, names.get(i), bitmaps.get(i));
                msg = "Saved " + names.size() + " lock still" + (names.size() == 1 ? "" : "s") + " to " + where;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "lock still save failed: " + e);
                msg = "Export failed: " + e.getMessage();
            } finally {
                for (Bitmap b : bitmaps) b.recycle();
            }
            final String result = msg;
            main.post(() -> cb.onDone(result));
        }, "lock-stills").start();
    }

    /** Native (unrotated) pixel size from the display mode; getRealSize is deprecated from API 31. */
    private static Point realSize(Display d) {
        Display.Mode m = d.getMode();
        int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
        // lock screens are portrait: a landscape-native panel (e.g. 2316x904) is swapped to 904x2316
        return w > h ? new Point(h, w) : new Point(w, h);
    }

    /** Returns a human-readable location. */
    private static String save(Context ctx, String name, Bitmap bmp) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) return Api29.save(ctx, name, bmp);
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) throw new IOException("no external storage");
        File f = new File(dir, name);
        try (OutputStream out = new FileOutputStream(f)) {
            compress(bmp, out);
        }
        Log.i(TAG, "lock still written to " + f);
        return f.getParent();
    }

    private static void compress(Bitmap bmp, OutputStream out) throws IOException {
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
    }

    /** Guarded so MediaStore.MediaColumns.RELATIVE_PATH / IS_PENDING are only touched on API 29+. */
    private static final class Api29 {
        static String save(Context ctx, String name, Bitmap bmp) throws IOException {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            // replace an earlier export of ours (queries only see our own files without storage permission)
            try (android.database.Cursor c = cr.query(collection, new String[] {MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[] {name, REL_DIR + "%"}, null)) {
                while (c != null && c.moveToNext()) {
                    cr.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "could not clear old " + name + ": " + e);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, REL_DIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(collection, v);
            if (uri == null) throw new IOException("MediaStore insert failed for " + name);
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("cannot open " + uri);
                compress(bmp, out);
            } catch (IOException | RuntimeException e) {
                cr.delete(uri, null, null);
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            int updated;
            try {
                updated = cr.update(uri, done, null, null);
            } catch (RuntimeException e) {
                cr.delete(uri, null, null);
                throw new IOException("publish failed for " + name + ": " + e);
            }
            if (updated < 1) {
                cr.delete(uri, null, null); // would stay pending and invisible
                throw new IOException("could not publish " + name);
            }
            return REL_DIR;
        }
    }
}
