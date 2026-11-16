package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.TextFit;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherCodes;

/**
 * Draws the clock and weather halves of the home-screen widget as transparent bitmaps, in the mockup's
 * proportions (6 px dots on an 11 px pitch, Inter text lines, Doto temperature). Everything is scaled by k,
 * derived from the bitmap size against a reference box (the mockup's widget slot).
 */
final class WidgetArt {
    private static final float CLOCK_REF_W = 340f, WEATHER_REF_W = 240f, REF_H = 150f;
    private static final long STALE_MS = 90L * 60 * 1000;
    static final int UNLIT = 0xFF1A1A1A, DATE = 0xFFA6A6A6, LINE = 0xFFD6D6D6, PLACE = 0xFF949494;

    private WidgetArt() {}

    static Bitmap clock(Context ctx, int wPx, int hPx, long nowMs) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float k = Math.min(wPx / CLOCK_REF_W, hPx / REF_H);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int hour = cal.get(Calendar.HOUR);
        if (hour == 0) hour = 12;
        DotMatrixFont.Grid g = DotMatrixFont.render(hour + ":" + (cal.get(Calendar.MINUTE) < 10 ? "0" : "")
            + cal.get(Calendar.MINUTE));

        float padL = 4f * k;
        float pitch = Math.min(11f * k, (wPx - padL - 2f * k) / g.cols);
        float radius = pitch * 3f / 11f; // the mockup's 6 px dot on an 11 px pitch
        float gridH = g.rows * pitch;

        Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        date.setTypeface(Fonts.inter(ctx));
        date.setTextSize(15f * k);
        date.setColor(DATE);
        float dateH = 15f * k;
        float gap = 12f * k;
        float top = Math.max(0f, (hPx - (gridH + gap + dateH)) / 2f);

        Paint unlit = new Paint(Paint.ANTI_ALIAS_FLAG);
        unlit.setColor(UNLIT);
        Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        lit.setColor(Color.WHITE);
        lit.setShadowLayer(5f * k, 0, 0, 0x66FFFFFF);
        for (int r = 0; r < g.rows; r++) {
            for (int col = 0; col < g.cols; col++) {
                if (!g.present(col, r)) continue;
                c.drawCircle(padL + (col + 0.5f) * pitch, top + (r + 0.5f) * pitch, radius,
                    g.lit(col, r) ? lit : unlit);
            }
        }

        String text = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date(nowMs));
        text = TextFit.fit(text, wPx - padL - 8f * k, date::measureText);
        c.drawText(text, padL + 4f * k, top + gridH + gap + dateH * 0.8f, date);
        return bmp;
    }

    static Bitmap weather(Context ctx, int wPx, int hPx, Weather w, long nowMs) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float k = Math.min(wPx / WEATHER_REF_W, hPx / REF_H);
        float right = wPx - 4f * k, maxW = wPx - 8f * k;

        Paint doto = new Paint(Paint.ANTI_ALIAS_FLAG);
        doto.setTypeface(Fonts.doto(ctx));
        doto.setTextSize(50f * k);
        doto.setColor(Color.WHITE);
        doto.setTextAlign(Paint.Align.RIGHT);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setTypeface(Fonts.inter(ctx));
        line.setTextAlign(Paint.Align.RIGHT);

        String temp = w == null ? "—°" : Math.round(w.tempF) + "°";
        String cond, place, age = null;
        String alert = w == null ? null : w.alert;
        if (w == null) {
            cond = "No weather yet";
            place = "";
        } else {
            cond = w.condition + " · H " + Math.round(w.highF) + "° L " + Math.round(w.lowF) + "°";
            place = w.place == null ? "" : w.place;
            age = ageText(nowMs - w.fetchedAtMs);
        }
        boolean hasAlert = alert != null && !alert.isEmpty();

        // block: temperature row (50k), gap 7k, condition 14k, gap 7k, place 13k, [gap 7k, alert 13k]
        float tempH = 50f * k, gap = 7f * k;
        float blockH = tempH + gap + 14f * k + (place.isEmpty() && age == null ? 0 : gap + 13f * k) + (hasAlert ? gap + 13f * k : 0);
        float y = Math.max(0f, (hPx - blockH) / 2f);

        float tempW = doto.measureText(temp);
        c.drawText(temp, right, y + tempH * 0.8f, doto);
        if (w != null) {
            float icon = 30f * k;
            drawGlyph(c, WeatherCodes.glyph(w.code), right - tempW - 10f * k - icon, y + (tempH - icon) / 2f, icon, k);
        }
        y += tempH + gap;

        line.setTextSize(14f * k);
        line.setColor(LINE);
        c.drawText(TextFit.fit(cond, maxW, line::measureText), right, y + 14f * k * 0.8f, line);
        y += 14f * k;
        if (!place.isEmpty() || age != null) {
            y += gap;
            line.setTextSize(13f * k);
            line.setColor(PLACE);
            String suffix = age == null ? "" : (place.isEmpty() ? age : " · " + age);
            c.drawText(TextFit.fitWithSuffix(place, suffix, maxW, line::measureText), right, y + 13f * k * 0.8f, line);
            y += 13f * k;
        }
        if (hasAlert) {
            y += gap;
            line.setTextSize(13f * k);
            line.setColor(Color.WHITE);
            c.drawText(TextFit.fit(alert, maxW, line::measureText), right, y + 13f * k * 0.8f, line);
        }
        return bmp;
    }

    /** " 2h ago"-style text once the data is older than 90 minutes; null while fresh. Hours, then days. */
    static String ageText(long ageMs) {
        if (ageMs <= STALE_MS) return null;
        long hours = (ageMs + 30L * 60 * 1000) / (60L * 60 * 1000);
        if (hours >= 48) return (hours / 24) + "d ago";
        return hours + "h ago";
    }

    /** Line-art weather icon in a size x size box at (x, y), drawn on the mockup's 24-unit grid. */
    private static void drawGlyph(Canvas c, WeatherCodes.Glyph glyph, float x, float y, float size, float k) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.WHITE);
        float u = size / 24f;
        c.save();
        c.translate(x, y);
        c.scale(u, u);
        p.setStrokeWidth(1.6f);
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
