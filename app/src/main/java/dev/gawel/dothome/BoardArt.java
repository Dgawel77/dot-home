package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.List;

import dev.gawel.dothome.core.Arrival;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.PlatformBoard;
import dev.gawel.dothome.core.TextFit;

/**
 * The Dot Blue Line platform board bitmap (over the card, which WidgetArt draws), inside a 16 dp pad. The station
 * label in 13 dp mono (L65); below it the O'HARE and FOREST PK columns, headed in 11 dp mono (L46) and separated by a
 * vertical dot-row divider on the 4 dp lattice (L46). Each column has up to PlatformBoard.ROWS rows (as many as the
 * height fits): the destination in 11 dp mono (L65) on the left, the minutes on the right in matrix dots at step 4
 * (lit cells, L65), or DUE in 11 dp mono (L100). With no key: one 11 dp line, ADD CTA KEY IN DOT HOME (L65).
 * Greyscale only. tools/WidgetPreview.java mirrors this layout for the picker preview.
 */
final class BoardArt {
    static final float PAD_DP = 16f, LABEL_DP = 13f, SMALL_DP = 11f;
    /** Line boxes: the station label and the column headers. */
    static final float LABEL_LINE_DP = 20f, HEAD_LINE_DP = 16f;
    /** Label to headers, headers to the first row, and between rows. */
    static final float GAP_DP = 4f;
    /** Matrix digit step; a row is DotMatrixFont.ROWS steps tall. */
    static final int STEP_DP = 4;
    /** Between the columns (the divider sits in its middle), and the least gap from a destination to its minutes. */
    static final float COL_GAP_DP = 16f, DEST_GAP_DP = 8f;
    static final String NO_KEY_TEXT = "ADD CTA KEY IN DOT HOME";
    static final String OHARE = "O'HARE", FOREST_PK = "FOREST PK";

    private BoardArt() {}

    /** How many rows fit under the headers in a widget hDp tall, 0 to PlatformBoard.ROWS. */
    static int rowsFit(float hDp) {
        float rowsTop = PAD_DP + LABEL_LINE_DP + GAP_DP + HEAD_LINE_DP + GAP_DP;
        float rowH = DotMatrixFont.ROWS * STEP_DP;
        int n = (int) Math.floor((hDp - PAD_DP - rowsTop + GAP_DP) / (rowH + GAP_DP));
        return Math.max(0, Math.min(PlatformBoard.ROWS, n));
    }

    /** The content bitmap, wPx x hPx, transparent around the text and dots. s.mode is NO_KEY or BOARD. */
    static Bitmap render(Context ctx, int wPx, int hPx, float dens, CtaRepo.Shown s) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Typeface mono = Fonts.mono(ctx);
        float pad = PAD_DP * dens, innerW = Math.max(1f, wPx - 2 * pad);
        if (s.mode == CtaRepo.Mode.NO_KEY) {
            Paint p = mono(mono, SMALL_DP, dens, DotStyle.L65);
            c.drawText(TextFit.fit(NO_KEY_TEXT, innerW, p::measureText), pad, baseline(p, 0f, hPx), p);
            return bmp;
        }
        Paint label = mono(mono, LABEL_DP, dens, DotStyle.L65);
        Paint head = mono(mono, SMALL_DP, dens, DotStyle.L46);
        Paint dest = mono(mono, SMALL_DP, dens, DotStyle.L65);
        Paint due = mono(mono, SMALL_DP, dens, DotStyle.L100);
        Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        lit.setColor(DotStyle.L65);
        Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        divider.setColor(DotStyle.L46);

        float top = pad;
        c.drawText(TextFit.fit(s.label, innerW, label::measureText), pad, baseline(label, top, LABEL_LINE_DP * dens),
            label);
        float headTop = top + (LABEL_LINE_DP + GAP_DP) * dens;
        float rowsTop = headTop + (HEAD_LINE_DP + GAP_DP) * dens;
        float colW = Math.max(1f, Lattice.snap((innerW - COL_GAP_DP * dens) / 2f, dens));
        float x0 = pad, x1 = pad + colW + COL_GAP_DP * dens;
        int rows = rowsFit(hPx / dens);
        float step = STEP_DP * dens, rowH = DotMatrixFont.ROWS * step;

        c.drawText(TextFit.fit(OHARE, colW, head::measureText), x0, baseline(head, headTop, HEAD_LINE_DP * dens), head);
        c.drawText(TextFit.fit(FOREST_PK, colW, head::measureText), x1, baseline(head, headTop, HEAD_LINE_DP * dens),
            head);
        column(c, s.ohare, x0, colW, rowsTop, rows, step, rowH, dens, dest, due, lit);
        column(c, s.forestPark, x1, colW, rowsTop, rows, step, rowH, dens, dest, due, lit);

        // the divider: one dot every 4 dp down the middle of the column gap, from the headers to the last row
        float dx = pad + colW + COL_GAP_DP * dens / 2f;
        float bottom = rows > 0 ? rowsTop + rows * rowH + (rows - 1) * GAP_DP * dens : headTop + HEAD_LINE_DP * dens;
        float r = step * Lattice.Weight.LIGHT.ratio / 2f;
        for (float y = headTop + step / 2f; y < bottom; y += step) c.drawCircle(dx, y, r, divider);
        return bmp;
    }

    private static void column(Canvas c, List<Arrival> trains, float x, float colW, float top, int rows, float step,
                               float rowH, float dens, Paint dest, Paint due, Paint lit) {
        float r = step * Lattice.Weight.REGULAR.ratio / 2f, right = x + colW;
        for (int i = 0; i < Math.min(rows, trains.size()); i++) {
            Arrival a = trains.get(i);
            float y = top + i * (rowH + GAP_DP * dens);
            String t = PlatformBoard.text(a);
            float timeW;
            if (a.due) {
                timeW = due.measureText(t);
                c.drawText(t, right - timeW, baseline(due, y, rowH), due);
            } else {
                byte[] cells = DotMatrixFont.cells(t);
                int cols = cells.length / DotMatrixFont.ROWS;
                timeW = cols * step;
                float left = right - timeW;
                for (int row = 0; row < DotMatrixFont.ROWS; row++) {
                    for (int col = 0; col < cols; col++) {
                        if (cells[row * cols + col] == 1) {
                            c.drawCircle(left + (col + 0.5f) * step, y + (row + 0.5f) * step, r, lit);
                        }
                    }
                }
            }
            float destW = Math.max(1f, colW - timeW - DEST_GAP_DP * dens);
            c.drawText(TextFit.fit(a.dest, destW, dest::measureText), x, baseline(dest, y, rowH), dest);
        }
    }

    /** Mono caps at sizeDp, +1 dp letter-spacing (as WidgetArt's labels). */
    private static Paint mono(Typeface tf, float sizeDp, float dens, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTypeface(tf);
        p.setTextSize(sizeDp * dens);
        p.setLetterSpacing(1f / sizeDp);
        return p;
    }

    /** Baseline that centres the text vertically in a line box of lineH starting at top. */
    private static float baseline(Paint p, float top, float lineH) {
        Paint.FontMetrics m = p.getFontMetrics();
        return top + (lineH - (m.descent - m.ascent)) / 2f - m.ascent;
    }
}
