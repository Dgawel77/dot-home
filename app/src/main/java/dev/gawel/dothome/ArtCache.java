package dev.gawel.dothome;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;

import dev.gawel.dothome.core.ArtMath;

/**
 * Album art for the music widget: small centre-cropped squares (ArtMath.STORE_PX) as ARGB pixels, for at most
 * ArtMath.MAX_TRACKS tracks (LRU). Full-size bitmaps are never kept; only content:// and android.resource:// URIs load.
 */
final class ArtCache {
    private ArtCache() {}

    private static final ArtMath.Lru<String, int[]> PIXELS = new ArtMath.Lru<>(ArtMath.MAX_TRACKS);

    static synchronized int[] get(String key) { return key == null ? null : PIXELS.get(key); }

    static synchronized void put(String key, int[] px) { if (key != null && px != null) PIXELS.put(key, px); }

    /** Centre-crops and scales a (borrowed, not recycled here) bitmap to STORE_PX squared; null if unusable. */
    static int[] fromBitmap(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int[] r = ArtMath.cropSquare(src.getWidth(), src.getHeight());
        if (r[2] <= 0) return null;
        int n = ArtMath.STORE_PX;
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        try {
            new Canvas(out).drawBitmap(src, new Rect(r[0], r[1], r[0] + r[2], r[1] + r[2]), new Rect(0, 0, n, n),
                new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[n * n];
            out.getPixels(px, 0, n, 0, 0, n, n);
            return px;
        } finally {
            out.recycle();
        }
    }

    /** Loads a content:// or android.resource:// URI, sampled down to about 256 px; blocking, call off the main thread. */
    static int[] fromUri(Context ctx, String uriStr) {
        if (uriStr == null) return null;
        Uri uri = Uri.parse(uriStr);
        String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme) && !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            return null; // never http(s) or anything else
        }
        ContentResolver cr = ctx.getContentResolver();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = ArtMath.sampleSize(o.outWidth, o.outHeight, 256);
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) return null;
            try {
                return fromBitmap(bmp);
            } finally {
                bmp.recycle();
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w("DotHome", "album art load failed: " + e);
            return null;
        }
    }
}
