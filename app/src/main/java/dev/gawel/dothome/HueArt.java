package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.List;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.HueLayout;
import dev.gawel.dothome.core.HueRows;
import dev.gawel.dothome.core.TextFit;

/**
 * The Dot Hue bitmap (over the card, which WidgetArt draws), in HueLayout's geometry so the tap cells line up.
 * <ul>
 *   <li>Each row: the room name in 13 dp mono (L65 on, L46 off), left in its name cell.</li>
 *   <li>Five dots of radius DOT_R_DP centred in their cells: lit L100, unlit L30, L30 reading on the glass card
 *       where L10 would vanish.</li>
 *   <li>Unreachable: names L30, every dot L30, and NO BRIDGE bottom right in 9 dp mono (L46).</li>
 *   <li>UNPAIRED and NO_ROOMS: one 11 dp line (L65), vertically centred.</li>
 * </ul>
 * Greyscale only.
 */
final class HueArt {
    static final float MSG_DP = 11f, NOTE_DP = 9f, NAME_GAP_DP = 8f;

    private HueArt() {}

    /** The content bitmap, wPx x hPx at dens px per dp, transparent around the text and dots. */
    static Bitmap render(Context ctx, int wPx, int hPx, float dens, HueRepo.Shown s) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Typeface mono = Fonts.mono(ctx);
        float pad = HueLayout.PAD_DP * dens, innerW = Math.max(1f, wPx - 2 * pad);
        if (s.mode != HueRepo.Mode.ROWS) {
            Paint p = mono(mono, MSG_DP, dens, DotStyle.L65);
            String msg = s.mode == HueRepo.Mode.UNPAIRED ? HueRows.UNPAIRED : HueRows.NO_ROOMS;
            c.drawText(TextFit.fit(msg, innerW, p::measureText), pad, baseline(p, 0f, hPx), p);
            return bmp;
        }
        float wDp = wPx / dens, hDp = hPx / dens;
        List<HueRows.Row> rows = s.rows;
        int n = HueLayout.rowsFit(hDp, rows.size());
        Paint lit = dot(DotStyle.L100), unlit = dot(DotStyle.L30);
        float r = HueLayout.DOT_R_DP * dens;
        for (int i = 0; i < n; i++) {
            HueRows.Row row = rows.get(i);
            float[] rect = HueLayout.row(wDp, hDp, n, i), name = HueLayout.nameCell(rect);
            int colour = !s.reachable ? DotStyle.L30 : row.state.on ? DotStyle.L65 : DotStyle.L46;
            Paint np = mono(mono, HueLayout.NAME_DP, dens, colour);
            float nameW = Math.max(1f, (name[2] - NAME_GAP_DP) * dens);
            c.drawText(TextFit.fit(row.name, nameW, np::measureText), name[0] * dens,
                baseline(np, name[1] * dens, name[3] * dens), np);
            int on = s.reachable ? HueRows.litDots(row.state) : 0;
            for (int d = 0; d < HueRows.DOTS; d++) {
                float[] p = HueLayout.dotCentre(rect, d);
                c.drawCircle(p[0] * dens, p[1] * dens, r, d < on ? lit : unlit);
            }
        }
        if (!s.reachable) {
            Paint note = mono(mono, NOTE_DP, dens, DotStyle.L46);
            float w = note.measureText(HueRows.NO_BRIDGE);
            c.drawText(HueRows.NO_BRIDGE, wPx - pad - w, baseline(note, hPx - pad, pad), note);
        }
        return bmp;
    }

    private static Paint dot(int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        return p;
    }

    /** Mono caps at sizeDp, +1 dp letter-spacing (as WidgetArt's labels). */
    private static Paint mono(Typeface tf, float sizeDp, float dens, int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
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
