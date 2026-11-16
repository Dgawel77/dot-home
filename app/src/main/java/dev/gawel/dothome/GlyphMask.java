package dev.gawel.dothome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/** Rasterises a short string into an alpha mask (0-255 per pixel) for {@link dev.gawel.dothome.core.DotFill}. */
final class GlyphMask {
    /** Cap height of Inter as a fraction of its font size. */
    private static final float CAP_RATIO = 0.727f;

    final int[] alpha;
    final int w, h;

    private GlyphMask(int[] alpha, int w, int h) {
        this.alpha = alpha;
        this.w = w;
        this.h = h;
    }

    /** Text size whose cap height is capPx, shrunk so the string is at most maxWidthPx wide. */
    static float fitSize(String text, Typeface face, float capPx, float maxWidthPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        float size = Math.max(1f, capPx / CAP_RATIO);
        p.setTextSize(size);
        float width = p.measureText(text);
        if (width > maxWidthPx && width > 0f) size = Math.max(1f, size * maxWidthPx / width);
        return size;
    }

    /** The tight ink box of text at textSize, as an ALPHA_8 raster read back into ints. */
    static GlyphMask render(String text, Typeface face, float textSize) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(textSize);
        Rect b = new Rect();
        p.getTextBounds(text, 0, text.length(), b);
        int w = Math.max(1, b.width()), h = Math.max(1, b.height());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        new Canvas(bmp).drawText(text, -b.left, -b.top, p);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();
        for (int i = 0; i < px.length; i++) px[i] >>>= 24;
        return new GlyphMask(px, w, h);
    }
}
