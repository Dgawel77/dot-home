import dev.gawel.dothome.core.Arrival;
import dev.gawel.dothome.core.BearsGame;
import dev.gawel.dothome.core.BearsView;
import dev.gawel.dothome.core.BlueLineStations;
import dev.gawel.dothome.core.CtaBoard;
import dev.gawel.dothome.core.CtaParser;
import dev.gawel.dothome.core.DotMatrixFont;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.PlatformBoard;
import dev.gawel.dothome.core.TextFit;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Picker preview images for the feed widgets, drawn on the JVM (no device needed).
 * Usage: java -cp build/test WidgetPreview kind out.png, with kind bears or blueline.
 * Build: ./test.sh && javac -cp build/test -d build/test tools/WidgetPreview.java (run from the repo root).
 * Renders at density 2.625 on a 24 dp-corner card (the glass approximated as a flat L10 fill) with JetBrains Mono
 * from the app's assets, mirroring the widget's own art (BearsArt, BoardArt) in a plausible state. The Blue Line
 * preview is the tests/fixtures/cta-arrivals.json board at Clark/Lake.
 */
public final class WidgetPreview {
    static final float DENS = 2.625f;
    static final float CARD_RADIUS_DP = 24f;
    static final String MONO = "app/src/main/assets/fonts/JetBrainsMono-Regular.ttf";

    // BearsArt's layout (dp)
    static final float PAD_DP = 16f, LABEL_DP = 13f, STATUS_DP = 11f;
    static final float LABEL_GAP_DP = 8f, TEAM_GAP_DP = 24f, ROW_GAP_DP = 8f, STATUS_LINE_DP = 16f;
    static final int BEARS_W_DP = 300, BEARS_H_DP = 120;

    // BoardArt's layout (dp)
    static final float SMALL_DP = 11f, LABEL_LINE_DP = 20f, HEAD_LINE_DP = 16f, GAP_DP = 4f;
    static final float COL_GAP_DP = 16f, DEST_GAP_DP = 8f;
    static final int BOARD_STEP_DP = 4;
    static final int BLUELINE_W_DP = 300, BLUELINE_H_DP = 180;
    static final String CTA_FIXTURE = "tests/fixtures/cta-arrivals.json";
    static final int CTA_FIXTURE_MAPID = 40380;

    private WidgetPreview() {}

    public static void main(String[] args) throws IOException, FontFormatException {
        if (args.length != 2) {
            System.err.println("usage: WidgetPreview <bears|blueline> <out.png>");
            System.exit(2);
        }
        Font mono = Font.createFont(Font.TRUETYPE_FONT, new File(MONO));
        BufferedImage img;
        switch (args[0]) {
            case "bears":
                img = bears(mono);
                break;
            case "blueline":
                img = blueline(mono);
                break;
            default:
                System.err.println("unknown kind: " + args[0]);
                System.exit(2);
                return;
        }
        File out = new File(args[1]);
        if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer");
        System.out.println("wrote " + out + " " + img.getWidth() + "x" + img.getHeight());
    }

    /** CHI 21 at GB 17, Q3 8:42: CHI is away, so it sits on the left. */
    static BufferedImage bears(Font mono) {
        BearsGame g = new BearsGame(BearsGame.State.IN, 0L, "GB", false, 21, 17, 3, "8:42", "3rd Quarter");
        int w = Math.round(BEARS_W_DP * DENS), h = Math.round(BEARS_H_DP * DENS);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D c = canvas(img);
        card(c, w, h);

        float pad = PAD_DP * DENS;
        Font label = mono(mono, LABEL_DP), small = mono(mono, STATUS_DP);
        String away = g.home ? g.opp : "CHI", home = g.home ? "CHI" : g.opp;
        String awayScore = String.valueOf(Math.max(0, g.home ? g.oppScore : g.chi));
        String homeScore = String.valueOf(Math.max(0, g.home ? g.chi : g.oppScore));
        FontMetrics lm = c.getFontMetrics(label);
        float labels = lm.stringWidth(away) + lm.stringWidth(home);
        int cols = DotMatrixFont.render(awayScore).cols + DotMatrixFont.render(homeScore).cols;
        float fixed = labels + (2 * LABEL_GAP_DP + TEAM_GAP_DP) * DENS;
        int stepDp = 8;
        if (fixed + cols * 8f * DENS > w - 2 * pad || blockH(8) > h - 2 * pad) stepDp = 4;
        float s = stepDp * DENS, r = s * Lattice.Weight.REGULAR.ratio / 2f, rowH = DotMatrixFont.ROWS * s;
        float top = Lattice.snap(Math.max(0f, (h - blockH(stepDp)) / 2f), DENS);

        float x = pad;
        x = team(c, x, top, rowH, away, awayScore, label, s, r);
        x += TEAM_GAP_DP * DENS;
        team(c, x, top, rowH, home, homeScore, label, s, r);
        text(c, small, BearsView.liveLabel(g), pad, top + rowH + ROW_GAP_DP * DENS, STATUS_LINE_DP * DENS);
        c.dispose();
        return img;
    }

    /** The fixture board at Clark/Lake, as BoardArt draws it at 300 x 180 dp (three rows). */
    static BufferedImage blueline(Font mono) throws IOException {
        CtaBoard b = CtaParser.parse(new String(Files.readAllBytes(Paths.get(CTA_FIXTURE)), StandardCharsets.UTF_8));
        if (b.errCd != 0) throw new IOException("fixture board errCd " + b.errCd);
        int w = Math.round(BLUELINE_W_DP * DENS), h = Math.round(BLUELINE_H_DP * DENS);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D c = canvas(img);
        card(c, w, h);

        float pad = PAD_DP * DENS, innerW = w - 2 * pad;
        Font label = mono(mono, LABEL_DP), small = mono(mono, SMALL_DP);
        FontMetrics lm = c.getFontMetrics(label), sm = c.getFontMetrics(small);
        text(c, label, TextFit.fit(BlueLineStations.label(CTA_FIXTURE_MAPID), innerW, lm::stringWidth), pad, pad,
            LABEL_LINE_DP * DENS, DotStyle.L65);
        float headTop = pad + (LABEL_LINE_DP + GAP_DP) * DENS;
        float rowsTop = headTop + (HEAD_LINE_DP + GAP_DP) * DENS;
        float colW = Lattice.snap((innerW - COL_GAP_DP * DENS) / 2f, DENS);
        float x0 = pad, x1 = pad + colW + COL_GAP_DP * DENS;
        float rowsTopDp = PAD_DP + LABEL_LINE_DP + GAP_DP + HEAD_LINE_DP + GAP_DP;
        int rows = Math.max(0, Math.min(PlatformBoard.ROWS, (int) Math.floor(
            (BLUELINE_H_DP - PAD_DP - rowsTopDp + GAP_DP) / (DotMatrixFont.ROWS * BOARD_STEP_DP + GAP_DP))));
        float step = BOARD_STEP_DP * DENS, rowH = DotMatrixFont.ROWS * step;

        text(c, small, "O'HARE", x0, headTop, HEAD_LINE_DP * DENS, DotStyle.L46);
        text(c, small, "FOREST PK", x1, headTop, HEAD_LINE_DP * DENS, DotStyle.L46);
        column(c, b.ohare, x0, colW, rowsTop, rows, step, rowH, small, sm);
        column(c, b.forestPark, x1, colW, rowsTop, rows, step, rowH, small, sm);

        float dx = pad + colW + COL_GAP_DP * DENS / 2f;
        float bottom = rows > 0 ? rowsTop + rows * rowH + (rows - 1) * GAP_DP * DENS : headTop + HEAD_LINE_DP * DENS;
        float r = step * Lattice.Weight.LIGHT.ratio / 2f;
        c.setColor(new Color(DotStyle.L46, true));
        for (float y = headTop + step / 2f; y < bottom; y += step) {
            c.fill(new Ellipse2D.Float(dx - r, y - r, 2 * r, 2 * r));
        }
        c.dispose();
        return img;
    }

    static void column(Graphics2D c, List<Arrival> trains, float x, float colW, float top, int rows, float step,
                       float rowH, Font small, FontMetrics sm) {
        float r = step * Lattice.Weight.REGULAR.ratio / 2f, right = x + colW;
        for (int i = 0; i < Math.min(rows, trains.size()); i++) {
            Arrival a = trains.get(i);
            float y = top + i * (rowH + GAP_DP * DENS);
            String t = PlatformBoard.text(a);
            float timeW;
            if (a.due) {
                timeW = sm.stringWidth(t);
                text(c, small, t, right - timeW, y, rowH, DotStyle.L100);
            } else {
                byte[] cells = DotMatrixFont.cells(t);
                int cols = cells.length / DotMatrixFont.ROWS;
                timeW = cols * step;
                float left = right - timeW;
                c.setColor(new Color(DotStyle.L65, true));
                for (int row = 0; row < DotMatrixFont.ROWS; row++) {
                    for (int col = 0; col < cols; col++) {
                        if (cells[row * cols + col] != 1) continue;
                        float cx = left + (col + 0.5f) * step, cy = y + (row + 0.5f) * step;
                        c.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
                    }
                }
            }
            float destW = Math.max(1f, colW - timeW - DEST_GAP_DP * DENS);
            text(c, small, TextFit.fit(a.dest, destW, sm::stringWidth), x, y, rowH, DotStyle.L65);
        }
    }

    static float blockH(int stepDp) {
        return (DotMatrixFont.ROWS * stepDp + ROW_GAP_DP + STATUS_LINE_DP) * DENS;
    }

    static float team(Graphics2D c, float x, float top, float rowH, String name, String score, Font label,
                      float s, float r) {
        text(c, label, name, x, top, rowH);
        x += c.getFontMetrics(label).stringWidth(name) + LABEL_GAP_DP * DENS;
        byte[] cells = DotMatrixFont.cells(score);
        int cols = cells.length / DotMatrixFont.ROWS;
        c.setColor(new Color(DotStyle.L65, true));
        for (int row = 0; row < DotMatrixFont.ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                if (cells[row * cols + col] != 1) continue;
                float cx = x + (col + 0.5f) * s, cy = top + (row + 0.5f) * s;
                c.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
            }
        }
        return x + cols * s;
    }

    /** L65 text, vertically centred in a line box of lineH starting at top. */
    static void text(Graphics2D c, Font f, String s, float x, float top, float lineH) {
        text(c, f, s, x, top, lineH, DotStyle.L65);
    }

    /** Text in colour, vertically centred in a line box of lineH starting at top. */
    static void text(Graphics2D c, Font f, String s, float x, float top, float lineH, int colour) {
        FontMetrics m = c.getFontMetrics(f);
        c.setFont(f);
        c.setColor(new Color(colour, true));
        c.drawString(s, x, top + (lineH - (m.getAscent() + m.getDescent())) / 2f + m.getAscent());
    }

    /** sizeDp at DENS with +1 dp tracking, as the widget's mono labels. */
    static Font mono(Font base, float sizeDp) {
        Map<TextAttribute, Object> tracking = Collections.singletonMap(TextAttribute.TRACKING, 1f / sizeDp);
        return base.deriveFont(sizeDp * DENS).deriveFont(tracking);
    }

    static Graphics2D canvas(BufferedImage img) {
        Graphics2D c = img.createGraphics();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        c.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        c.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        c.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return c;
    }

    /** The card: transparent corners, a flat L10 fill with 24 dp corners. */
    static void card(Graphics2D c, int w, int h) {
        float rad = CARD_RADIUS_DP * DENS;
        c.setColor(new Color(DotStyle.L10, true));
        c.fill(new RoundRectangle2D.Float(0, 0, w, h, 2 * rad, 2 * rad));
    }
}
