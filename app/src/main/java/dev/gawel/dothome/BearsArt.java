package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.TimeZone;

import dev.gawel.dothome.core.BearsGame;
import dev.gawel.dothome.core.BearsView;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.TextFit;

/**
 * The Dot Bears bitmap (over the card, which WidgetArt draws), left-aligned inside a 16 dp pad and centred
 * vertically (a 1-row widget leaves less than 16 dp above and below). LIVE/FINAL: away team then home team, each a 13 dp mono label and its score in matrix dots (lit cells,
 * L65) at step 8 (4 when 8 does not fit), then the quarter and clock or FINAL in 11 dp mono. PRE: the kickoff line
 * in 13 dp mono. Greyscale only. tools/WidgetPreview.java mirrors this layout for the picker preview.
 */
final class BearsArt {
    static final float PAD_DP = 16f, LABEL_DP = 13f, STATUS_DP = 11f;
    /** Label to its score, and one team to the other. */
    static final float LABEL_GAP_DP = 8f, TEAM_GAP_DP = 24f;
    /** Score row to status row, and the status row's line height. */
    static final float ROW_GAP_DP = 8f, STATUS_LINE_DP = 16f;

    private BearsArt() {}

    /** The content bitmap, wPx x hPx, transparent around the text and dots. g is not null; mode is PRE, LIVE or FINAL. */
    static Bitmap render(Context ctx, int wPx, int hPx, float dens, BearsGame g, BearsView.Mode mode, TimeZone tz) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Typeface mono = Fonts.mono(ctx);
        float pad = PAD_DP * dens;
        if (mode == BearsView.Mode.PRE) {
            Paint p = mono(mono, LABEL_DP, dens);
            String t = TextFit.fit(BearsView.preText(g, tz), Math.max(1f, wPx - 2 * pad), p::measureText);
            c.drawText(t, pad, baseline(p, 0f, hPx), p);
            return bmp;
        }
        String awayName = g.home ? g.opp : "CHI", homeName = g.home ? "CHI" : g.opp;
        String awayScore = score(g.home ? g.oppScore : g.chi), homeScore = score(g.home ? g.chi : g.oppScore);
        String status = mode == BearsView.Mode.LIVE ? BearsView.liveLabel(g) : "FINAL";
        Paint label = mono(mono, LABEL_DP, dens), small = mono(mono, STATUS_DP, dens);

        float labels = label.measureText(awayName) + label.measureText(homeName);
        int cols = DotMatrixFont.render(awayScore).cols + DotMatrixFont.render(homeScore).cols;
        float fixed = labels + (2 * LABEL_GAP_DP + TEAM_GAP_DP) * dens;
        int stepDp = 8;
        if (fixed + cols * 8f * dens > wPx - 2 * pad || blockH(8, dens) > hPx - 2 * pad) stepDp = 4;
        float s = stepDp * dens, r = s * Lattice.Weight.REGULAR.ratio / 2f, rowH = DotMatrixFont.ROWS * s;
        float top = Lattice.snap(Math.max(0f, (hPx - blockH(stepDp, dens)) / 2f), dens); // >= 16 dp once the widget is 2 rows

        Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
        dots.setColor(DotStyle.L65);
        float x = pad;
        x = team(c, x, top, rowH, awayName, awayScore, label, dots, s, r, dens);
        x += TEAM_GAP_DP * dens;
        team(c, x, top, rowH, homeName, homeScore, label, dots, s, r, dens);
        c.drawText(TextFit.fit(status, Math.max(1f, wPx - 2 * pad), small::measureText), pad,
            baseline(small, top + rowH + ROW_GAP_DP * dens, STATUS_LINE_DP * dens), small);
        return bmp;
    }

    /** The score block's height in px at the given step: the dot row, the gap and the status line. */
    private static float blockH(int stepDp, float dens) {
        return (DotMatrixFont.ROWS * stepDp + ROW_GAP_DP + STATUS_LINE_DP) * dens;
    }

    /** One team's label (centred on the dot row) and score dots from x; returns the x after the dots. */
    private static float team(Canvas c, float x, float top, float rowH, String name, String score, Paint label,
                              Paint dots, float s, float r, float dens) {
        c.drawText(name, x, baseline(label, top, rowH), label);
        x += label.measureText(name) + LABEL_GAP_DP * dens;
        byte[] cells = DotMatrixFont.cells(score);
        int cols = cells.length / DotMatrixFont.ROWS;
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] == 1) c.drawCircle(x + (col + 0.5f) * s, top + (row + 0.5f) * s, r, dots);
            }
        }
        return x + cols * s;
    }

    /** An unknown score (-1, before ESPN fills it in) shows as 0. */
    private static String score(int v) {
        return String.valueOf(Math.max(0, v));
    }

    /** Mono caps at sizeDp, L65, +1 dp letter-spacing (as WidgetArt's labels). */
    private static Paint mono(Typeface tf, float sizeDp, float dens) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(DotStyle.L65);
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
