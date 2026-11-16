package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Halftone;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.TextFit;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherCodes;

/**
 * Draws the clock, weather and music widgets as bitmaps (a solid GROUND panel when backdrops are on) on the 4 dp lattice. Every function takes the
 * effective density (display density times the shrink applied to the bitmap), so sizes come out as true dp once
 * the launcher stretches the bitmap back. Content offsets snap to multiples of the step; no containers, glows or
 * gradients.
 */
final class WidgetArt {
    private static final long STALE_MS = 90L * 60 * 1000;
    private static final int WORST_TEMP_COLS = 23;
    private static final float ART_RADIUS_DP = 12f;
    private static final float ICON_STROKE_DP = 1.6f;
    private static final float PANEL_RADIUS_DP = 24f;

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

    static Bitmap clock(Context ctx, int wPx, int hPx, long nowMs, Lattice.Step step, float dens) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        backdrop(ctx, c, bmp.getWidth(), bmp.getHeight(), dens, true, false);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        String t = DotMatrixFont.clockText(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(ctx));
        byte[] cells = DotMatrixFont.cells(t);
        int cols = cells.length / DotMatrixFont.ROWS;
        float s = Lattice.stepPx(step, dens);
        float r = Lattice.radiusPx(step, Lattice.Weight.REGULAR, dens);
        float padPx = Lattice.snap(16f * dens, dens);

        Paint date = text(Fonts.inter(ctx), 15f, dens, DotStyle.L65);
        float dateH = 20f * dens, gap = 8f * dens;
        float gridH = DotMatrixFont.ROWS * s;
        float top = Lattice.snap(Math.max(0f, (hPx - (gridH + gap + dateH)) / 2f), dens);

        Paint lit = fill(DotStyle.L100), unlit = fill(DotStyle.L10);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                byte v = cells[row * cols + col];
                if (v < 0) continue;
                dot(c, padPx, top, s, col, row, r, v == 1 ? lit : unlit);
            }
        }

        String d = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date(nowMs));
        d = TextFit.fit(d, wPx - padPx - 4f * dens, date::measureText);
        c.drawText(d, padPx, baseline(date, top + gridH + gap, dateH), date);
        return bmp;
    }

    static Bitmap weather(Context ctx, int wPx, int hPx, Weather w, long nowMs, float dens) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        backdrop(ctx, c, bmp.getWidth(), bmp.getHeight(), dens, false, true);

        String temp = w == null ? "\u2212\u2212\u00B0" : tempText(Math.round(w.tempF));
        byte[] cells = DotMatrixFont.cells(temp);
        int cols = cells.length / DotMatrixFont.ROWS;
        float wDp = wPx / dens;
        // S8 when the widest reading ("\u221200\u00B0", 23 columns) fits with a 16 dp margin each side, else S4;
        // fixed so the size does not flip as the temperature crosses -10
        Lattice.Step step = WORST_TEMP_COLS * 8f + 2 * 16f <= wDp ? Lattice.Step.S8 : Lattice.Step.S4;
        float s = Lattice.stepPx(step, dens);
        float r = Lattice.radiusPx(step, Lattice.Weight.REGULAR, dens);
        float pad = Lattice.snap((step == Lattice.Step.S8 ? 16f : 8f) * dens, dens);
        float right = wPx - pad, maxW = Math.max(1f, right - 4f * dens);

        Paint line = text(Fonts.inter(ctx), 13f, dens, DotStyle.L65);
        line.setTextAlign(Paint.Align.RIGHT);
        float lineH = 16f * dens, gap = 8f * dens;

        String cond = null, place = "", age = null;
        String alert = w == null ? null : w.alert;
        if (w != null) {
            cond = w.condition + " \u00B7 H " + Math.round(w.highF) + "\u00B0 L " + Math.round(w.lowF) + "\u00B0";
            place = w.place == null ? "" : w.place;
            age = ageText(nowMs - w.fetchedAtMs);
        }
        boolean hasAlert = alert != null && !alert.isEmpty();
        boolean hasPlace = !place.isEmpty() || age != null;

        float gridH = DotMatrixFont.ROWS * s;
        int lines = w == null ? 1 : 1 + (hasPlace ? 1 : 0) + (hasAlert ? 1 : 0);
        float blockH = gridH + gap + lines * lineH;
        float y = Lattice.snap(Math.max(0f, (hPx - blockH) / 2f), dens);

        float gx = Lattice.snap(right - cols * s, dens);
        Paint dots = fill(DotStyle.L65);
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] == 1) dot(c, gx, y, s, col, row, r, dots); // lit cells only
            }
        }
        if (w != null) {
            float icon = 32f * dens;
            float ix = Lattice.snap(gx - 8f * dens - icon, dens);
            if (ix >= 0f) drawGlyph(c, WeatherCodes.glyph(w.code), ix, Lattice.snap(y + (gridH - icon) / 2f, dens), icon, dens);
        }
        y += gridH + gap;

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
     * buttons. dens is the effective density, so every size is true dp.
     */
    static Bitmap music(Context ctx, int wPx, int hPx, NowPlaying np, float dens) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        backdrop(ctx, c, bmp.getWidth(), bmp.getHeight(), dens, true, true);
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

    /** Pref "widget.backdrop" (default on): whether widgets draw their own solid black panel. */
    static boolean backdropOn(Context ctx) {
        return !"false".equals(new PrefsStore(ctx).get("widget.backdrop", "true"));
    }

    /**
     * Opaque GROUND rounded panel filling the bitmap, 24 dp corners. Only the flagged sides are rounded; an
     * unflagged side runs square so adjacent widget halves read as one panel.
     */
    private static void backdrop(Context ctx, Canvas c, int wPx, int hPx, float dens, boolean left, boolean right) {
        if (!backdropOn(ctx)) return;
        c.drawPath(panel(0, 0, wPx, hPx, PANEL_RADIUS_DP * dens, left, right), fill(DotStyle.GROUND));
    }

    private static Path panel(float l, float t, float r, float b, float rad, boolean roundLeft, boolean roundRight) {
        float lr = roundLeft ? rad : 0f, rr = roundRight ? rad : 0f;
        Path path = new Path();
        path.addRoundRect(new RectF(l, t, r, b), new float[] {lr, lr, rr, rr, rr, rr, lr, lr}, Path.Direction.CW);
        return path;
    }

    /** Makes the corners of an opaque bitmap transparent (24 dp radius) when backdrops are on; else untouched. */
    static void roundCorners(Context ctx, Bitmap bmp, float dens) {
        if (!backdropOn(ctx)) return;
        Path outside = panel(0, 0, bmp.getWidth(), bmp.getHeight(), PANEL_RADIUS_DP * dens, true, true);
        outside.setFillType(Path.FillType.INVERSE_WINDING);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        new Canvas(bmp).drawPath(outside, p);
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
