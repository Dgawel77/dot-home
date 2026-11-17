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
import dev.gawel.dothome.core.ClockFit;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.TextFit;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherCodes;

/**
 * Draws the widget bitmaps on the 4 dp lattice: the clear glass card, the clock's time and text row,
 * the music widget and the radar's glass finish. Every function takes the
 * effective density (display density times the shrink applied to the bitmap), so sizes come out as true dp once
 * the launcher stretches the bitmap back. Content offsets snap to multiples of the step; no containers, glows or
 * gradients.
 */
final class WidgetArt {
    private static final long STALE_MS = 90L * 60 * 1000;
    static final String DATE_PATTERN = "EEEE, MMMM d"; // the clock's date, drawn by date()
    static final float DATE_SIZE_DP = 15f;
    private static final float ART_RADIUS_DP = 12f;
    private static final float ICON_STROKE_DP = 1.6f;
    /** Right part of the music widget kept clear for widget_music.xml's three 56 dp buttons and 4 dp end margin. */
    static final float MUSIC_TRANSPORT_DP = 3 * 56f + 4f;
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
     * The clock's time (One UI ignores custom fonts in widgets, so it is always a bitmap): "hh:mm" as dots, lit L100 over unlit L10, cellDp per cell, in a bitmap exactly as wide
     * as the text and seven cells tall, so the layout shows it at true size.
     */
    static Bitmap timeBitmap(Context ctx, long nowMs, int cellDp, float dens) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        String t = DotMatrixFont.clockText(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(ctx));
        byte[] cells = DotMatrixFont.cells(t);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = cellDp * dens;
        float r = s * Lattice.Weight.REGULAR.ratio / 2f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(cols * s)),
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

    /** The seconds beside the time: "ss" as dots at step SECS_STEP_DP, lit L65 over unlit L10, SECS_W_DP x SECS_H_DP. */
    static Bitmap secondsBitmap(int second, float dens) {
        byte[] cells = DotMatrixFont.cells((second < 10 ? "0" : "") + second);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = ClockFit.SECS_STEP_DP * dens;
        float r = s * Lattice.Weight.REGULAR.ratio / 2f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(ClockFit.SECS_W_DP * dens)),
            Math.max(1, Math.round(ClockFit.SECS_H_DP * dens)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint lit = fill(DotStyle.L65), unlit = fill(DotStyle.L10);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                byte v = cells[row * cols + col];
                if (v < 0) continue;
                dot(c, 0, 0, s, col, row, r, v == 1 ? lit : unlit);
            }
        }
        return bmp;
    }

    /** The date as the clock's bottom row shows it ("EEEE, MMMM d", Inter 15 dp): its width in dp. */
    static float dateWidthDp(Context ctx, long nowMs) {
        Paint p = text(Fonts.inter(ctx), DATE_SIZE_DP, 1f, DotStyle.L65);
        return p.measureText(new SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(new Date(nowMs)));
    }

    /** The date text, Inter 15 at L65, vertically centred in a rowDp-tall bitmap of wDp (true dp at density dens). */
    static Bitmap date(Context ctx, long nowMs, float rowDp, float wDp, float dens) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(wDp * dens)), Math.max(1, Math.round(rowDp * dens)),
            Bitmap.Config.ARGB_8888);
        Paint p = text(Fonts.inter(ctx), DATE_SIZE_DP, dens, DotStyle.L65);
        new Canvas(bmp).drawText(new SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(new Date(nowMs)),
            0f, baseline(p, 0f, rowDp * dens), p);
        return bmp;
    }

    /**
     * The right-hand weather block (as it was before the text row): the line-art icon and the temperature in 5x7
     * matrix dots (lit cells only, L65) at stepDp (8, or 4 when narrow), then the condition with H/L (Inter 13, L65),
     * the place and data age (Inter 13, L46) and an alert (Inter 13, L100). Right-aligned in a wDp-wide bitmap whose
     * height fits the block. dens is the effective density, so every size is true dp.
     */
    static Bitmap weatherBlock(Context ctx, Weather w, long nowMs, int stepDp, float wDp, float dens) {
        String temp = w == null ? "\u2212\u2212\u00B0" : tempText(Math.round(w.tempF));
        byte[] cells = DotMatrixFont.cells(temp);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = stepDp * dens, r = s * Lattice.Weight.REGULAR.ratio / 2f;
        float wPx = wDp * dens, maxW = Math.max(1f, wPx - 4f * dens);
        Paint line = text(Fonts.inter(ctx), 13f, dens, DotStyle.L65);
        line.setTextAlign(Paint.Align.RIGHT);
        float lineH = 16f * dens, gap = 8f * dens, icon = ClockFit.WX_ICON_DP * dens;

        String cond = null, place = "", age = null;
        String alert = w == null ? null : w.alert;
        if (w != null) {
            cond = w.condition + " \u00B7 H " + Math.round(w.highF) + "\u00B0 L " + Math.round(w.lowF) + "\u00B0";
            place = w.place == null ? "" : w.place;
            age = ageText(nowMs - w.fetchedAtMs);
        }
        boolean hasAlert = alert != null && !alert.isEmpty();
        boolean hasPlace = !place.isEmpty() || age != null;
        int lines = w == null ? 1 : 1 + (hasPlace ? 1 : 0) + (hasAlert ? 1 : 0);
        float gridH = Math.max(DotMatrixFont.ROWS * s, w == null ? 0f : icon);
        float gridTop = (gridH - DotMatrixFont.ROWS * s) / 2f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(wPx)),
            Math.max(1, Math.round(gridH + gap + lines * lineH)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        float right = wPx;
        float gx = right - cols * s;
        Paint dots = fill(DotStyle.L65);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] == 1) dot(c, gx, gridTop, s, col, row, r, dots); // lit cells only
            }
        }
        if (w != null) {
            float ix = gx - ClockFit.WX_ICON_GAP_DP * dens - icon;
            if (ix >= 0f) drawGlyph(c, WeatherCodes.glyph(w.code), ix, (gridH - icon) / 2f, icon, dens);
        }
        float y = gridH + gap;
        if (w == null) {
            Paint lab = label(ctx, dens, DotStyle.L46);
            lab.setTextAlign(Paint.Align.RIGHT);
            c.drawText("NO DATA", right, baseline(lab, y, lineH), lab);
            return bmp;
        }
        c.drawText(TextFit.fit(cond, maxW, line::measureText), right, baseline(line, y, lineH), line);
        y += lineH;
        if (hasPlace) {
            line.setColor(DotStyle.L46);
            String suffix = age == null ? "" : (place.isEmpty() ? age : " \u00B7 " + age);
            c.drawText(TextFit.fitWithSuffix(place, suffix, maxW, line::measureText), right, baseline(line, y, lineH), line);
            y += lineH;
        }
        if (hasAlert) {
            line.setColor(DotStyle.L100);
            c.drawText(TextFit.fit(alert, maxW, line::measureText), right, baseline(line, y, lineH), line);
        }
        return bmp;
    }

    /**
     * The compact weather for the cover, under the date row: icon (24 dp), the temperature in matrix dots at step 4
     * (L65) and the condition line (Inter 13, L65; an alert replaces it in L100), left-aligned, rowDp tall, wDp wide.
     */
    static Bitmap weatherCompact(Context ctx, Weather w, float rowDp, float wDp, float dens) {
        String temp = w == null ? "\u2212\u2212\u00B0" : tempText(Math.round(w.tempF));
        byte[] cells = DotMatrixFont.cells(temp);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = 4f * dens, r = s * Lattice.Weight.REGULAR.ratio / 2f, h = rowDp * dens;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(wDp * dens)), Math.max(1, Math.round(h)),
            Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float icon = w == null ? 0f : 24f * dens;
        if (w != null) drawGlyph(c, WeatherCodes.glyph(w.code), 0f, (h - icon) / 2f, icon, dens);
        float gx = icon == 0f ? 0f : icon + 8f * dens, gy = (h - DotMatrixFont.ROWS * s) / 2f;
        Paint dots = fill(DotStyle.L65);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] == 1) dot(c, gx, gy, s, col, row, r, dots);
            }
        }
        float tx = gx + cols * s + 12f * dens;
        boolean alert = w != null && w.alert != null && !w.alert.isEmpty();
        Paint line = w == null ? label(ctx, dens, DotStyle.L46)
            : text(Fonts.inter(ctx), 13f, dens, alert ? DotStyle.L100 : DotStyle.L65);
        String t = w == null ? "NO DATA" : (alert ? w.alert : w.condition);
        float room = wDp * dens - tx - 4f * dens;
        if (room > 0f) c.drawText(TextFit.fit(t, room, line::measureText), tx, baseline(line, 0f, h), line);
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
     * artist (Inter 13, L65) and an S4 dot-row progress bar. The right MUSIC_TRANSPORT_DP (172 dp) is left clear for
     * the transport buttons. dens is the effective density, so every size is true dp; radiusDp is the card's corner radius.
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

        float[] row = musicRow(wPx, hPx, dens);
        float textX = row[0], maxW = row[4];
        Paint title = text(Fonts.interMedium(ctx), 15f, dens, DotStyle.L100);
        Paint artist = text(Fonts.inter(ctx), 13f, dens, DotStyle.L65);

        float titleH = 20f * dens, artistH = 16f * dens, gap = 8f * dens;
        float y = row[1] - (titleH + artistH + gap);
        c.drawText(TextFit.fit(np.title, maxW, title::measureText), textX, baseline(title, y, titleH), title);
        y += titleH;
        c.drawText(TextFit.fit(np.artist, maxW, artist::measureText), textX, baseline(artist, y, artistH), artist);
        y += artistH + gap;

        // progress: one S4 dot row, elapsed L100, remainder L10
        int total = (int) row[3];
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

    /**
     * Geometry of the music widget's progress dot row in px: {left, top, width, cell count, max text width}.
     * Shared by {@link #music} and the seek zones, so the zones sit exactly over the dots.
     */
    static float[] musicRow(int wPx, int hPx, float dens) {
        float s4 = Lattice.stepPx(Lattice.Step.S4, dens);
        float textX = (16f + 64f + 16f) * dens;
        float maxW = Math.max(1f, wPx - MUSIC_TRANSPORT_DP * dens - textX - 4f * dens);
        float titleH = 20f * dens, artistH = 16f * dens, gap = 8f * dens;
        float top = Lattice.snap((hPx - (titleH + artistH + gap + s4)) / 2f, dens) + titleH + artistH + gap;
        int total = Math.max(1, (int) (maxW / s4));
        return new float[] {textX, top, total * s4, total, maxW};
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
