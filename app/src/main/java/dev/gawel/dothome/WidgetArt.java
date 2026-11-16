package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import dev.gawel.dothome.core.CardShape;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.TextFit;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherCodes;

/**
 * Draws the widget bitmaps on the 4 dp lattice: the clear glass card, the clock's weather row (and its bitmap time
 * fallback), the music widget and the radar's glass finish. Every function takes the
 * effective density (display density times the shrink applied to the bitmap), so sizes come out as true dp once
 * the launcher stretches the bitmap back. Content offsets snap to multiples of the step; no containers, glows or
 * gradients.
 */
final class WidgetArt {
    private static final long STALE_MS = 90L * 60 * 1000;
    static final String DATE_PATTERN = "EEEE, MMMM d"; // as the date TextClock in widget_clock.xml
    static final float DATE_SIZE_DP = 15f;
    private static final float WEATHER_ICON_DP = 20f;
    /** Below this much room the weather row drops its text and keeps the icon and temperature. */
    private static final float MIN_TEXT_DP = 24f;
    private static final String SEP = " \u00B7 ";
    private static final float ART_RADIUS_DP = 12f;
    private static final float ICON_STROKE_DP = 1.6f;
    /** Clear glass card: 75% near-black tint, diagonal white sheen from the top-left, gradient white rim. */
    static final int GLASS_FILL = 0xBF0E0E0E;
    static final float GLASS_SHEEN_ALPHA = 0.12f;
    static final float GLASS_SHEEN_X = 0.6f, GLASS_SHEEN_Y = 0.9f;
    static final float GLASS_RIM_MAX = 0.40f, GLASS_RIM_MIN = 0.10f;
    static final float GLASS_RIM_X = 0.5f, GLASS_RIM_Y = 0.8f;
    static final float GLASS_RIM_DP = 1.2f;

    private WidgetArt() {}

    /** Dot-filled grid cell at (col, row) of a lattice with the given step, origin (x0, y0). */
    private static void dot(Canvas c, float x0, float y0, float stepPx, int col, int row, float radius, Paint p) {
        c.drawCircle(x0 + (col + 0.5f) * stepPx, y0 + (row + 0.5f) * stepPx, radius, p);
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint text(Typeface tf, float sizeDp, float dens, int color) {
        Paint p = fill(color);
        p.setTypeface(tf);
        p.setTextSize(sizeDp * dens);
        return p;
    }

    /** Baseline that centres the text vertically in a line box of lineH starting at top. */
    private static float baseline(Paint p, float top, float lineH) {
        Paint.FontMetrics m = p.getFontMetrics();
        return top + (lineH - (m.descent - m.ascent)) / 2f - m.ascent;
    }

    /** Mono caps label: 11 dp, +1 dp letter-spacing (in em), never shown below 11 dp. */
    private static Paint label(Context ctx, float dens, int color) {
        Paint p = text(Fonts.mono(ctx), 11f, dens, color);
        p.setLetterSpacing(1f / 11f);
        return p;
    }

    /**
     * The per-minute bitmap fallback for the time (pref widget.clockMode = bitmap, for a launcher that cannot render
     * the matrix font): "hh:mm" as dots, lit L100 over unlit L10, cellDp per cell, left-aligned in a wPx-wide bitmap
     * seven cells tall, so the layout's fitStart + adjustViewBounds shows it at true size.
     */
    static Bitmap timeBitmap(Context ctx, int wPx, long nowMs, int cellDp, float dens) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        String t = DotMatrixFont.clockText(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(ctx));
        byte[] cells = DotMatrixFont.cells(t);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = cellDp * dens;
        float r = s * Lattice.Weight.REGULAR.ratio / 2f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.max(wPx, Math.round(cols * s))),
            Math.max(1, Math.round(DotMatrixFont.ROWS * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint lit = fill(DotStyle.L100), unlit = fill(DotStyle.L10);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                byte v = cells[row * cols + col];
                if (v < 0) continue;
                dot(c, 0, 0, s, col, row, r, v == 1 ? lit : unlit);
            }
        }
        return bmp;
    }

    /** The date as the clock's bottom row shows it (TextClock "EEEE, MMMM d", Inter 15 dp): its width in dp. */
    static float dateWidthDp(Context ctx, long nowMs) {
        Paint p = text(Fonts.inter(ctx), DATE_SIZE_DP, 1f, DotStyle.L65);
        return p.measureText(new SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(new Date(nowMs)));
    }

    /**
     * The weather for the clock's bottom row, one line rowDp tall, as wide as its content and at most maxWDp: the
     * line-art icon, the temperature as matrix dots (lit only, L65) at the step whose seven rows fill the row, then
     * the condition (Inter 13, L65; an active alert replaces it in L100) and the place and data age (L46). Text is
     * cut with an ellipsis, the place before the condition; with too little room only the icon and temperature
     * remain. dens is the effective density, so every size is true dp.
     */
    static Bitmap weather(Context ctx, Weather w, long nowMs, float rowDp, float maxWDp, float dens) {
        float h = rowDp * dens;
        float s = Math.max(1, (int) (rowDp / DotMatrixFont.ROWS)) * dens;
        float r = s * Lattice.Weight.REGULAR.ratio / 2f;
        String temp = w == null ? "\u2212\u2212\u00B0" : tempText(Math.round(w.tempF));
        byte[] cells = DotMatrixFont.cells(temp);
        int cols = cells.length / DotMatrixFont.ROWS;

        float icon = w == null ? 0f : WEATHER_ICON_DP * dens;
        float tempX = w == null ? 0f : icon + 8f * dens;
        float textX = tempX + cols * s + 12f * dens;
        float textMax = maxWDp * dens - textX;

        boolean alert = w != null && w.alert != null && !w.alert.isEmpty();
        Paint first = w == null ? label(ctx, dens, DotStyle.L46) : text(Fonts.inter(ctx), 13f, dens, alert ? DotStyle.L100 : DotStyle.L65);
        Paint second = text(Fonts.inter(ctx), 13f, dens, DotStyle.L46);
        String one = "", two = "";
        if (textMax >= MIN_TEXT_DP * dens) {
            if (w == null) {
                one = TextFit.fit("NO DATA", textMax, first::measureText);
            } else {
                one = TextFit.fit(alert ? w.alert : w.condition, textMax, first::measureText);
                String place = w.place == null ? "" : w.place;
                String age = ageText(nowMs - w.fetchedAtMs);
                String suffix = age == null ? "" : (place.isEmpty() ? age : " \u00B7 " + age);
                float room = textMax - first.measureText(one) - second.measureText(SEP);
                // the place and age follow the whole condition, and only once three letters of the place fit
                String least = place.length() > 3 ? place.substring(0, 3) + "\u2026" : place;
                if (!one.endsWith("\u2026") && !(place.isEmpty() && suffix.isEmpty())
                        && room >= second.measureText(least + suffix)) {
                    two = SEP + TextFit.fitWithSuffix(place, suffix, room, second::measureText);
                }
            }
        }
        float oneW = first.measureText(one), twoW = second.measureText(two);
        float right = one.isEmpty() ? tempX + cols * s : textX + oneW + twoW;

        Bitmap bmp = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(right)), Math.max(1, Math.round(h)),
            Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (w != null) drawGlyph(c, WeatherCodes.glyph(w.code), 0f, (h - icon) / 2f, icon, dens);
        float gy = (h - DotMatrixFont.ROWS * s) / 2f;
        Paint dots = fill(DotStyle.L65);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] == 1) dot(c, tempX, gy, s, col, row, r, dots); // lit cells only
            }
        }
        if (!one.isEmpty()) {
            float base = baseline(second, 0f, h);
            c.drawText(one, textX, w == null ? baseline(first, 0f, h) : base, first);
            if (!two.isEmpty()) c.drawText(two, textX + oneW, base, second);
        }
        return bmp;
    }

    /** "41\u00B0" or "\u22125\u00B0": the matrix font has a true minus, not a hyphen. */
    private static String tempText(long t) {
        return (t < 0 ? "\u2212" + (-t) : String.valueOf(t)) + "\u00B0";
    }

    private static float[] artDotsCache;

    /** Halftone of the placeholder art (a sun and a lower band; no cover bitmap is available), 1 px per dp, step 4. */
    private static synchronized float[] artDots() {
        if (artDotsCache != null) return artDotsCache;
        int n = 64;
        int[] img = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double d1 = Math.hypot(x - 42, y - 22) / 18, sun = Math.max(0, 1 - d1 * d1);
                double band = Math.max(0, Math.sin((x + y) * 0.22)) * Math.max(0, (y - 34) / 30.0);
                int g = (int) Math.round(255 * Math.min(1, sun + band * 0.8 + 0.06));
                img[y * n + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        }
        artDotsCache = Halftone.dots(img, n, n, 4);
        return artDotsCache;
    }

    /** Whether a disc at (cx, cy) with radius rd lies wholly inside a size x size square with corner radius rad. */
    private static boolean discInsideRounded(float cx, float cy, float rd, float size, float rad) {
        if (cx - rd < 0 || cy - rd < 0 || cx + rd > size || cy + rd > size) return false;
        float qx = cx < rad ? rad : (cx > size - rad ? size - rad : cx);
        float qy = cy < rad ? rad : (cy > size - rad ? size - rad : cy);
        return Math.hypot(cx - qx, cy - qy) + rd <= rad;
    }

    /**
     * The music widget body: 64 dp halftone art square (S4, L100 dots, 12 dp corners), title (Inter 15, L100),
     * artist (Inter 13, L65) and an S4 dot-row progress bar. The right 132 dp is left clear for the transport
     * buttons. dens is the effective density, so every size is true dp; radiusDp is the card's corner radius.
     */
    static Bitmap music(Context ctx, int wPx, int hPx, NowPlaying np, float dens, float radiusDp) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (backdropOn(ctx)) drawCard(c, bmp.getWidth(), bmp.getHeight(), dens, radiusDp);
        float s4 = Lattice.stepPx(Lattice.Step.S4, dens);
        float artS = 64f * dens;
        float artX = 16f * dens, artY = Lattice.snap((hPx - artS) / 2f, dens);

        float[] dots = artDots();
        float sc = s4 / 4f, rad = ART_RADIUS_DP * dens;
        Paint p = fill(DotStyle.L100);
        for (int i = 0; i < dots.length; i += 3) {
            float cx = dots[i] * sc, cy = dots[i + 1] * sc, rd = dots[i + 2] * sc / 2f;
            if (!discInsideRounded(cx, cy, rd, artS, rad)) continue; // dots stay round: skip, never clip
            c.drawCircle(artX + cx, artY + cy, rd, p);
        }

        float textX = artX + artS + 16f * dens;
        float maxW = Math.max(1f, wPx - 132f * dens - textX - 4f * dens);
        Paint title = text(Fonts.interMedium(ctx), 15f, dens, DotStyle.L100);
        Paint artist = text(Fonts.inter(ctx), 13f, dens, DotStyle.L65);

        float titleH = 20f * dens, artistH = 16f * dens, gap = 8f * dens;
        float y = Lattice.snap((hPx - (titleH + artistH + gap + s4)) / 2f, dens);
        c.drawText(TextFit.fit(np.title, maxW, title::measureText), textX, baseline(title, y, titleH), title);
        y += titleH;
        c.drawText(TextFit.fit(np.artist, maxW, artist::measureText), textX, baseline(artist, y, artistH), artist);
        y += artistH + gap;

        // progress: one S4 dot row, elapsed L100, remainder L10
        int total = Math.max(1, (int) (maxW / s4));
        int done = 0;
        if (np.durationMs > 0) {
            long pos = np.positionAt(android.os.SystemClock.elapsedRealtime());
            done = Math.round(Math.max(0f, Math.min(1f, pos / (float) np.durationMs)) * total);
        }
        float rr = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.REGULAR, dens);
        Paint on = fill(DotStyle.L100), off = fill(DotStyle.L10);
        for (int i = 0; i < total; i++) dot(c, textX, y, s4, i, 0, rr, i < done ? on : off);
        return bmp;
    }

    /** Pref "widget.backdrop" (default on): whether widgets draw their own clear glass card. */
    static boolean backdropOn(Context ctx) {
        return !"false".equals(new PrefsStore(ctx).get("widget.backdrop", "true"));
    }

    /** The clear glass card over a whole wPx x hPx widget, corners at the launcher's clip radius ({@link CardShape}). */
    static Bitmap card(int wPx, int hPx, float dens, float radiusDp) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        drawCard(new Canvas(bmp), bmp.getWidth(), bmp.getHeight(), dens, radiusDp);
        return bmp;
    }

    /** Clear glass card at the origin: 75% tint, then the sheen and rim. */
    private static void drawCard(Canvas c, float w, float h, float dens, float radiusDp) {
        c.drawPath(panel(0, 0, w, h, radiusDp * dens), fill(GLASS_FILL));
        glassFinish(c, w, h, dens, radiusDp);
    }

    /**
     * Diagonal sheen (clipped to the rounded card) and gradient rim over a w x h card at the origin, both with the
     * launcher's clip radius. The rim's centre line is inset by half the stroke with a concentric radius, so the
     * whole stroke lies inside the clip and the corners are not cut off.
     */
    private static void glassFinish(Canvas c, float w, float h, float dens, float radiusDp) {
        float rad = radiusDp * dens;
        // sheen: alpha = 0.12 * (1 - s)^2 along s = x*0.6/W + y*0.9/H, sampled at five stops
        float ax = GLASS_SHEEN_X / w, ay = GLASS_SHEEN_Y / h, n = ax * ax + ay * ay;
        int[] sc = new int[5];
        float[] sp = new float[5];
        for (int i = 0; i < 5; i++) {
            sp[i] = i / 4f;
            float t = 1f - sp[i];
            sc[i] = Color.argb(Math.round(GLASS_SHEEN_ALPHA * t * t * 255f), 255, 255, 255);
        }
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, ax / n, ay / n, sc, sp, Shader.TileMode.CLAMP));
        c.drawPath(panel(0, 0, w, h, rad), sheen);
        // rim: alpha 0.40 at the top-left easing to 0.10 along s = x*0.5/W + y*0.8/H
        float sw = Math.max(1f, GLASS_RIM_DP * dens);
        float rx = GLASS_RIM_X / w, ry = GLASS_RIM_Y / h, rn = rx * rx + ry * ry;
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(0, 0, rx / rn, ry / rn,
            Color.argb(Math.round(GLASS_RIM_MAX * 255f), 255, 255, 255), Color.argb(Math.round(GLASS_RIM_MIN * 255f), 255, 255, 255),
            Shader.TileMode.CLAMP));
        float in = CardShape.rimInset(sw);
        c.drawPath(panel(in, in, w - in, h - in, Math.max(0f, rad - in)), rim);
    }

    private static Path panel(float l, float t, float r, float b, float rad) {
        Path path = new Path();
        path.addRoundRect(new RectF(l, t, r, b), rad, rad, Path.Direction.CW);
        return path;
    }

    /**
     * Glass finish for an opaque map bitmap when the switch is on: transparent rounded corners, then the same
     * diagonal sheen and gradient rim as the cards, all with the launcher's clip radius. No fill over the map.
     */
    static void glassMap(Context ctx, Bitmap bmp, float dens, float radiusDp) {
        if (!backdropOn(ctx)) return;
        Canvas cv = new Canvas(bmp);
        int w = bmp.getWidth(), h = bmp.getHeight();
        Path outside = panel(0, 0, w, h, radiusDp * dens);
        outside.setFillType(Path.FillType.INVERSE_WINDING);
        Paint clr = new Paint(Paint.ANTI_ALIAS_FLAG);
        clr.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        cv.drawPath(outside, clr);
        glassFinish(cv, w, h, dens, radiusDp);
    }

    /** " 2h ago"-style text once the data is older than 90 minutes; null while fresh. Hours, then days. */
    static String ageText(long ageMs) {
        if (ageMs <= STALE_MS) return null;
        long hours = (ageMs + 30L * 60 * 1000) / (60L * 60 * 1000);
        if (hours >= 48) return (hours / 24) + "d ago";
        return hours + "h ago";
    }

    /** Line-art weather icon in a size x size box at (x, y), drawn on the mockup's 24-unit grid. */
    private static void drawGlyph(Canvas c, WeatherCodes.Glyph glyph, float x, float y, float size, float dens) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(DotStyle.L65);
        float u = size / 24f;
        c.save();
        c.translate(x, y);
        c.scale(u, u);
        p.setStrokeWidth(ICON_STROKE_DP * dens / u);
        switch (glyph) {
            case CLEAR:
                c.drawCircle(12, 12, 4.5f, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(12 + cs * 7.5f, 12 + sn * 7.5f, 12 + cs * 10f, 12 + sn * 10f, p);
                }
                break;
            case FOG:
                c.drawLine(4, 8, 20, 8, p);
                c.drawLine(3, 12, 21, 12, p);
                c.drawLine(5, 16, 19, 16, p);
                break;
            default:
                c.drawPath(cloud(), p);
                if (glyph == WeatherCodes.Glyph.RAIN) {
                    c.drawLine(8, 20, 7, 23, p);
                    c.drawLine(12, 20, 11, 23, p);
                    c.drawLine(16, 20, 15, 23, p);
                } else if (glyph == WeatherCodes.Glyph.SNOW) {
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(8, 21.5f, 1f, p);
                    c.drawCircle(12, 21.5f, 1f, p);
                    c.drawCircle(16, 21.5f, 1f, p);
                } else if (glyph == WeatherCodes.Glyph.STORM) {
                    Path bolt = new Path();
                    bolt.moveTo(13, 14);
                    bolt.lineTo(10, 19);
                    bolt.lineTo(14, 19);
                    bolt.lineTo(11, 23.5f);
                    c.drawPath(bolt, p);
                }
        }
        c.restore();
    }

    /** Cloud outline on the 24-unit grid: three lobes and a flat base, merged so only the outer edge strokes. */
    private static Path cloud() {
        Path out = new Path();
        out.addCircle(7.5f, 14.5f, 3.5f, Path.Direction.CW);
        Path part = new Path();
        part.addCircle(12f, 10.5f, 5.5f, Path.Direction.CW);
        out.op(part, Path.Op.UNION);
        part.rewind();
        part.addCircle(16.5f, 14f, 4f, Path.Direction.CW);
        out.op(part, Path.Op.UNION);
        part.rewind();
        part.addRect(7.5f, 14.5f, 16.5f, 18f, Path.Direction.CW);
        out.op(part, Path.Op.UNION);
        return out;
    }
}
