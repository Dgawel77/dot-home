package dev.gawel.dothome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;
import java.util.Map;

/**
 * A soft-edged dot of one colour: radial falloff, opaque out to 70% of the radius, fading to nothing at the
 * radius. Sprites are cached per colour and size bucket (about 4x the drawn diameter, so they are never shrunk
 * much), so drawing allocates nothing per frame.
 */
final class SoftDot {
    private static final Map<Long, Bitmap> SPRITES = new HashMap<>();

    private final int color;
    private final Bitmap[] local = new Bitmap[17]; // by size / 16; filled lazily, so draw() neither boxes nor locks
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();

    SoftDot(int color) {
        this.color = color;
    }

    /** Builds the sprite for this radius now, so the first frame does not pay for it. */
    SoftDot prewarm(float radius) {
        bucket(spriteSize(radius));
        return this;
    }

    private Bitmap bucket(int size) {
        Bitmap b = local[size / 16];
        if (b == null) {
            b = sprite(color, size);
            local[size / 16] = b;
        }
        return b;
    }

    /** Draws the dot centred at (x, y); alpha in 0..1 scales the colour. */
    void draw(Canvas c, float x, float y, float radius, float alpha) {
        if (alpha <= 0.01f || radius <= 0.05f) return;
        Bitmap s = bucket(spriteSize(radius));
        paint.setAlpha(Math.round((alpha > 1f ? 1f : alpha) * 255));
        src.set(0, 0, s.getWidth(), s.getHeight());
        dst.set(x - radius, y - radius, x + radius, y + radius);
        c.drawBitmap(s, src, dst, paint);
    }

    /** About 4x the diameter, in steps of 16 px, clamped to 16..256. */
    static int spriteSize(float radius) {
        int px = Math.round(8f * radius / 16f) * 16;
        return Math.max(16, Math.min(256, px));
    }

    private static synchronized Bitmap sprite(int color, int size) {
        long key = ((long) color << 16) ^ size;
        Bitmap b = SPRITES.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float h = size / 2f;
        p.setShader(new RadialGradient(h, h, h, new int[] {color, color, color & 0x00FFFFFF},
            new float[] {0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(h, h, h, p);
        SPRITES.put(key, b);
        return b;
    }
}
