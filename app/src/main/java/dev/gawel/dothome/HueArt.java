package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.HueLayout;
import dev.gawel.dothome.core.HueRows;
import dev.gawel.dothome.core.TextFit;

/**
 * The Dot Hue strip bitmap (over the card, which WidgetArt draws), in HueLayout's geometry so the tap cells line up.
 * <ul>
 *   <li>Top line: ‹ and › in 13 dp mono (L46, only with two or more rooms) around the room name in 13 dp mono (L65
 *       on, L46 off), and ALL OFF in 11 dp mono (L65) at the right.</li>
 *   <li>Below: 20 dots across the width, one per 5% of brightness: lit L100, unlit L30 (L10 would vanish on the
 *       glass card).</li>
 *   <li>Unreachable: the name L30, every dot L30, and NO BRIDGE (L46) where ALL OFF was.</li>
 *   <li>UNPAIRED and NO_ROOMS: one 11 dp line (L65), centred vertically.</li>
 * </ul>
 * Greyscale only.
 */
final class HueArt {
    static final String PREV = "‹", NEXT = "›", ALL_OFF = "ALL OFF";
    /** Room between the end of the name and ›. */
    static final float NAME_GAP_DP = 6f;

    private HueArt() {}

    /** The name as drawn at widget width wDp: fitted to HueLayout.maxNameW. */
    static String fittedName(Context ctx, String name, float wDp) {
        Paint p = mono(Fonts.mono(ctx), HueLayout.NAME_DP, 1f, 0);
        return TextFit.fit(name, Math.max(1f, HueLayout.maxNameW(wDp) - NAME_GAP_DP), p::measureText);
    }

    /** The name cell's width in dp at widget width wDp: the fitted name plus NAME_GAP_DP (HueWidget uses it too). */
    static float nameCellDp(Context ctx, String name, float wDp) {
        Paint p = mono(Fonts.mono(ctx), HueLayout.NAME_DP, 1f, 0);
        return p.measureText(fittedName(ctx, name, wDp)) + NAME_GAP_DP;
    }

    /** The content bitmap, wPx x hPx at dens px per dp, showing room row (an index into s.rows). */
    static Bitmap render(Context ctx, int wPx, int hPx, float dens, HueRepo.Shown s, int row) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Typeface mono = Fonts.mono(ctx);
        float wDp = wPx / dens, hDp = hPx / dens, pad = HueLayout.PAD_DP * dens;
        if (s.mode != HueRepo.Mode.ROWS || s.rows.isEmpty()) {
            Paint p = mono(mono, HueLayout.LABEL_DP, dens, DotStyle.L65);
            String msg = s.mode == HueRepo.Mode.UNPAIRED ? HueRows.UNPAIRED : HueRows.NO_ROOMS;
            c.drawText(TextFit.fit(msg, Math.max(1f, wPx - 2 * pad), p::measureText), pad, baseline(p, 0f, hPx), p);
            return bmp;
        }
        int n = s.rows.size();
        HueRows.Row r = s.rows.get(HueRows.index(row, n));
        boolean on = r.state.on;
        int nameColour = !s.reachable ? DotStyle.L30 : on ? DotStyle.L65 : DotStyle.L46;

        Paint name = mono(mono, HueLayout.NAME_DP, dens, nameColour);
        String shown = fittedName(ctx, r.name, wDp);
        float nameW = nameCellDp(ctx, r.name, wDp);
        float[] nc = HueLayout.nameCell(wDp, hDp, nameW);
        c.drawText(shown, nc[0] * dens, baseline(name, nc[1] * dens, nc[3] * dens), name);

        if (n > 1) {
            Paint arrow = mono(mono, HueLayout.NAME_DP, dens, DotStyle.L46);
            centred(c, PREV, HueLayout.prevCell(wDp, hDp), arrow, dens);
            centred(c, NEXT, HueLayout.nextCell(wDp, hDp, nameW), arrow, dens);
        }

        Paint label = mono(mono, HueLayout.LABEL_DP, dens, s.reachable ? DotStyle.L65 : DotStyle.L46);
        String right = s.reachable ? ALL_OFF : HueRows.NO_BRIDGE;
        float[] oc = HueLayout.offCell(wDp, hDp);
        float lw = label.measureText(right);
        c.drawText(right, (oc[0] + oc[2]) * dens - lw, baseline(label, oc[1] * dens, oc[3] * dens), label);

        int lit = s.reachable ? HueRows.litDots(r.state) : 0;
        Paint litP = dot(DotStyle.L100), unlit = dot(DotStyle.L30);
        float rad = HueLayout.DOT_R_DP * dens;
        for (int i = 0; i < HueRows.STEPS; i++) {
            float[] p = HueLayout.dotCentre(wDp, hDp, i);
            c.drawCircle(p[0] * dens, p[1] * dens, rad, i < lit ? litP : unlit);
        }
        return bmp;
    }

    private static void centred(Canvas c, String t, float[] cell, Paint p, float dens) {
        float w = p.measureText(t);
        c.drawText(t, (cell[0] + cell[2] / 2f) * dens - w / 2f, baseline(p, cell[1] * dens, cell[3] * dens), p);
    }

    private static Paint dot(int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        return p;
    }

    /** Mono caps at sizeDp x dens px, +1 dp letter-spacing (as WidgetArt's labels). */
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
