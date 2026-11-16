package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.List;
import java.util.WeakHashMap;

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.GameConfig;
import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.Lattice;
import dev.gawel.dothome.core.LatticeRaster;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.ScoreBook;
import dev.gawel.dothome.core.Zones;

/**
 * Draws the game on the 4 dp lattice anchored at the surface origin. Rocks are rasterised every frame into filled
 * lattice silhouettes (regular weight on the edge blending to light in the body, ink by sqrt(coverage)), or, with
 * the outline style, drawn as free outline dots with a body stipple. The shockwave and hit rings are lattice dot
 * rows. The ship, shots, debris, exhaust trail and stars are free soft dots.
 * Dots draw across the whole screen.
 * Only the ship, its opening dot and shots reach L100; everything on a lock still stays at L65 or below. Nothing is
 * allocated per frame.
 */
final class DotRenderer {
    private static final float TAU = (float) (Math.PI * 2);
    private static final float[] SHIP = {15, 0, -10, -9.5f, -5, 0, -10, 9.5f};
    private static final int TRAIL_MAX = 48;
    private static final float TRAIL_LIFE = 0.7f;
    /** Breath tempo: the star twinkle period in seconds. */
    private static final float TWINKLE_PERIOD = 4f;
    /** Size shows depth: stars at or above NEAR_DEPTH are NEAR_SCALE times bigger. */
    private static final float NEAR_DEPTH = 0.6f, NEAR_SCALE = 1.5f;
    /** Intro timeline as shares of the intro: the scene gathers from SCENE_FROM, the opening dot hands over to the ship from HANDOFF_FROM. */
    private static final float SCENE_FROM = 1f / 3f, HANDOFF_FROM = 2f / 3f;
    /** Rock cells under this coverage are skipped: sqrt(0.005) x the regular radius is about 0.2 px. */
    private static final float MIN_COVERAGE = 0.005f;
    /** A rock whose bounding box needs more lattice cells than this is skipped (a guard; a big rock needs about 500). */
    private static final int MAX_ROCK_CELLS = 1 << 18;
    /** Layout in dp: the score label (JetBrains Mono caps, 1 dp tracking) and its margins. */
    private static final float LABEL_DP = 11f, LABEL_TRACKING_EM = 1f / 11f;
    private static final float MARGIN_MAIN_DP = 24f, MARGIN_COVER_DP = 16f, LABEL_BASE_DP = 16f;

    /** Cached dots of one rock (outline style): x, y, alpha triples (alpha 1 marks an outline dot), valid for one spacing. */
    private static final class RockDots {
        float spacing;
        float radius; // r.r the cache was built for; world.resize rescales rocks in place
        long seed;
        float[] pts = new float[0];
        int n;
    }

    private final SoftDot dot100 = new SoftDot(DotStyle.L100);
    private final SoftDot dot65 = new SoftDot(DotStyle.L65);
    private final SoftDot dot46 = new SoftDot(DotStyle.L46);
    private final Paint lattice = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.content.res.Resources res;
    private float density = -1, step, rLight, rReg, rHeavy;

    private boolean outlineRocks;
    private final WeakHashMap<GameWorld.Rock, RockDots> rockCache = new WeakHashMap<>();
    private long rockSeed = 1;
    private float[] shipPts = new float[0];
    private float shipSpacing = -1, shipK = -1;

    // rasterising scratch: one rock's polygon on screen and the coverage of its bounding-box cells (grown, never per frame)
    private float[] polyX = new float[16], polyY = new float[16];
    private float[] grid = new float[0];
    private int gridCol0, gridRow0, gridCols, gridRows;
    private final LatticeRaster.CoverageSink gridSink = new LatticeRaster.CoverageSink() {
        @Override
        public void cell(int col, int row, float cov) {
            grid[(row - gridRow0) * gridCols + (col - gridCol0)] = cov;
        }
    };

    private final float[] trailX = new float[TRAIL_MAX];
    private final float[] trailY = new float[TRAIL_MAX];
    private final float[] trailLife = new float[TRAIL_MAX];
    private int trailHead;
    private float trailT;
    private float lastIntroT;

    private float parTx, parTy, parX, parY;

    private int lastToday = -1, lastBest = -1;
    private String scoreText = "";

    // the current draw: screen = (vx, vy) + vs * world; identity live, a centred cover fit for stills
    private float vs = 1f, vx, vy;
    private float canvasW, canvasH;
    private boolean still;
    /** Screen y above which (stills only) rock, particle, shot, ship and ring dots are skipped; negative draws everything. */
    private float clearTopY = -1;

    DotRenderer(Context context) {
        res = context.getResources();
        lattice.setStyle(Paint.Style.FILL);
        lattice.setColor(DotStyle.L65);
        textPaint.setTypeface(Fonts.mono(context));
        textPaint.setLetterSpacing(LABEL_TRACKING_EM);
        textPaint.setColor(DotStyle.L65);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        metrics();
        dot65.prewarm(rReg);
        dot100.prewarm(rHeavy);
    }

    /** Re-reads the density (it can change with display size: the fold fix) and the lattice sizes that follow from it. */
    private void metrics() {
        float d = res.getDisplayMetrics().density;
        if (d == density) return;
        density = d;
        step = Lattice.stepPx(Lattice.Step.S4, d);
        rLight = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.LIGHT, d);
        rReg = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.REGULAR, d);
        rHeavy = Lattice.radiusPx(Lattice.Step.S4, Lattice.Weight.HEAVY, d);
        textPaint.setTextSize(LABEL_DP * d);
    }

    /** Outline rocks (free outline dots and stipple) instead of lattice silhouettes; the Setup "Rock style" pick. */
    void setOutlineRocks(boolean on) { outlineRocks = on; }

    /** Target parallax tilt in [-1, 1]; the drawn offset eases toward it. */
    void setParallax(float tx, float ty) {
        parTx = clamp(tx == tx ? tx : 0f, -1f, 1f);
        parTy = clamp(ty == ty ? ty : 0f, -1f, 1f);
    }

    /** Zeroes parallax at once (target and drawn offset) so stars do not slide when the wallpaper wakes. */
    void resetParallax() {
        parTx = parTy = parX = parY = 0f;
    }

    /** Per-frame bookkeeping that is not part of the simulation (the ship's exhaust trail). Call after step(). */
    void advance(GameWorld w, float dt) {
        if (w.width() <= 0) return;
        float k = scaleK(w);
        float a = 1f - (float) Math.exp(-dt / 0.15f);
        parX += (parTx - parX) * a;
        parY += (parTy - parY) * a;
        float it = w.introTime();
        if (it < lastIntroT) java.util.Arrays.fill(trailLife, 0f); // the intro restarted: the scene starts from one dot
        lastIntroT = it;
        for (int i = 0; i < TRAIL_MAX; i++) if (trailLife[i] > 0) trailLife[i] -= dt;
        trailT += dt;
        GameWorld.Ship s = w.ship();
        float sp = (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy);
        if (trailT > 0.04f && sp > 10f * k) {
            trailT = 0;
            trailX[trailHead] = s.x - (float) Math.cos(s.angle) * 9f * k;
            trailY[trailHead] = s.y - (float) Math.sin(s.angle) * 9f * k;
            trailLife[trailHead] = TRAIL_LIFE;
            trailHead = (trailHead + 1) % TRAIL_MAX;
        }
    }

    void draw(Canvas c, GameWorld w, ScoreBook sb, float topInset) {
        c.drawColor(DotStyle.GROUND);
        if (w.width() <= 0) return;
        metrics();
        if (sb != null) {
            int today = sb.today(), best = sb.best();
            if (today != lastToday || best != lastBest) {
                lastToday = today;
                lastBest = best;
                scoreText = "TODAY " + today + " · BEST " + best;
            }
        }
        still = false;
        vs = 1f;
        vx = vy = 0f;
        clearTopY = -1f;
        canvasW = c.getWidth();
        canvasH = c.getHeight();
        drawScene(c, w);
        if (sb != null) drawScore(c, w, topInset);
    }

    /** The score line: right-aligned on the 24 / 16 dp margin, below the status bar, on the lattice. */
    private void drawScore(Canvas c, GameWorld w, float topInset) {
        float margin = (Zones.isCover(w.width(), w.height()) ? MARGIN_COVER_DP : MARGIN_MAIN_DP) * density;
        float right = Lattice.snap(w.width() - margin, density);
        float base = Lattice.snap(Math.max(0f, topInset) + LABEL_BASE_DP * density, density);
        c.drawText(scoreText, right, base, textPaint);
    }

    /**
     * Renders the current world into a new bitmap of the given size. rock, particle, shot, ship and ring dots (and the shockwave) above clearTopFraction of the height are skipped
     * so that band stays clear. The world is scaled uniformly to cover the target and centred (excess is cropped);
     * the lattice and dot sizes are the target screen's, and every level is capped at L65.
     */
    Bitmap renderStill(GameWorld w, int width, int height, float clearTopFraction) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(DotStyle.GROUND);
        if (w.width() > 0 && w.height() > 0) {
            metrics();
            float s = Math.max(width / w.width(), height / w.height());
            still = true;
            vs = s;
            vx = (width - s * w.width()) / 2f;
            vy = (height - s * w.height()) / 2f;
            canvasW = width;
            canvasH = height;
            clearTopY = clearTopFraction * height;
            try {
                drawScene(c, w);
            } finally {
                still = false;
                vs = 1f;
                vx = vy = 0f;
                clearTopY = -1f;
            }
        }
        return bmp;
    }

    private void drawScene(Canvas c, GameWorld w) {
        GameConfig cfg = w.config();
        if (cfg == null) return;
        float k = scaleK(w);
        float t = w.time();
        // the intro (the opening dot) runs on its own clock, replayed after unlock; stills show the finished scene
        float intro = cfg.introSeconds;
        float it = still ? intro : Math.min(w.introTime(), intro);
        float fade = easeOut(clamp((it - SCENE_FROM * intro) / ((1f - SCENE_FROM) * intro), 0, 1));
        float hand = clamp((it - HANDOFF_FROM * intro) / ((1f - HANDOFF_FROM) * intro), 0, 1);
        SoftDot user = still ? dot65 : dot100; // the user's own dots; lock stills cap at L65

        List<GameWorld.Star> stars = w.stars();
        for (int i = 0; i < stars.size(); i++) {
            GameWorld.Star st = stars.get(i);
            float x = st.x, y = st.y;
            if (!still) {
                x = wrap(x + parX * st.depth * 28f * k, w.width());
                y = wrap(y + parY * st.depth * 28f * k, w.height());
            }
            float qx = sx(x), qy = sy(y);
            float twinkle = 0.65f + 0.35f * (float) Math.sin(TAU * t / TWINKLE_PERIOD + st.phase);
            float r = st.depth >= NEAR_DEPTH ? rLight * NEAR_SCALE : rLight;
            dot65.draw(c, qx, qy, r, st.alpha * twinkle * fade);
        }

        List<GameWorld.Rock> rocks = w.rocks();
        for (int i = 0; i < rocks.size(); i++) {
            if (outlineRocks) outlineRock(c, rocks.get(i), fade);
            else latticeRock(c, rocks.get(i), fade);
        }

        List<GameWorld.Particle> parts = w.particles();
        for (int i = 0; i < parts.size(); i++) {
            GameWorld.Particle p = parts.get(i);
            float qx = sx(p.x), qy = sy(p.y);
            if (qy < clearTopY) continue;
            float lk = p.life / p.life0;
            dot65.draw(c, qx, qy, rReg * (0.45f + 0.55f * lk), p.alpha * lk);
        }

        if (!still) {
            for (int i = 0; i < TRAIL_MAX; i++) {
                if (trailLife[i] <= 0) continue;
                float lk = trailLife[i] / TRAIL_LIFE;
                dot46.draw(c, trailX[i], trailY[i], rReg * (0.3f + 0.6f * lk), lk * fade);
            }
        }

        List<GameWorld.Shot> shots = w.shots();
        for (int i = 0; i < shots.size(); i++) {
            GameWorld.Shot sh = shots.get(i);
            float qx = sx(sh.x), qy = sy(sh.y);
            if (qy < clearTopY) continue;
            user.draw(c, qx, qy, rReg, 1f);
            dot46.draw(c, sx(sh.x - sh.vx * 0.012f), sy(sh.y - sh.vy * 0.012f), rReg, 1f);
            dot46.draw(c, sx(sh.x - sh.vx * 0.024f), sy(sh.y - sh.vy * 0.024f), rLight, 1f);
        }

        GameWorld.Ship s = w.ship();
        boolean shipClear = clearTopY < 0 || sy(s.y - 12f * k) >= clearTopY;
        if (shipClear && it < intro) {
            // the opening dot: one pulsing hero dot that hands over to the ship
            float pr = rHeavy * (1f + Math.abs((float) Math.sin(it * 3.2f)));
            user.draw(c, sx(s.x), sy(s.y), pr, 1f - easeIn(hand));
        }
        float sa = easeOut(hand);
        if (shipClear && sa > 0) {
            if (step != shipSpacing || k != shipK) buildShipDots(step, k);
            float cs = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
            float blink = s.hitCooldown > 0 ? 1f - 0.45f * (float) Math.sin(Math.PI * (0.4f - s.hitCooldown) / 0.4f) : 1f;
            for (int j = 0; j < shipPts.length; j += 2) {
                float px = shipPts[j], py = shipPts[j + 1];
                user.draw(c, sx(s.x + px * cs - py * sn), sy(s.y + px * sn + py * cs), rHeavy, sa * blink);
            }
        }

        GameWorld.Shockwave sw = w.wave();
        if (sw != null && !still) {
            float al = 1f - sw.t / cfg.shockDuration;
            if (al > 0 && sw.radius > 1f) latticeRing(c, sw.x, sw.y, sw.radius, al);
        }

        List<GameWorld.Ring> rings = w.rings();
        for (int i = 0; i < rings.size(); i++) {
            GameWorld.Ring g = rings.get(i);
            float rk = g.t / cfg.ringLife;
            if (rk < 1f) latticeRing(c, g.x, g.y, (8f + rk * 62f) * k, 1f - rk);
        }
    }

    /**
     * One rock as a filled lattice silhouette: its outline on screen is rasterised onto the S4 lattice (origin 0,0).
     * Each cell's dot comes from LatticeRaster.dotRadius: regular weight on the edge, light in the body, blended by
     * the weakest of the cell and its four neighbours, with ink following sqrt(coverage). It is continuous, so edge
     * dots grow and shrink smoothly as the rock moves and never pop. All in L65, centred on the cell.
     */
    private void latticeRock(Canvas c, GameWorld.Rock r, float fade) {
        float[] v = r.outline;
        int nv = v.length / 2;
        if (fade <= 0f || nv < 3) return;
        if (polyX.length < nv) {
            polyX = new float[nv];
            polyY = new float[nv];
        }
        float cs = (float) Math.cos(r.rot), sn = (float) Math.sin(r.rot);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int j = 0; j < nv; j++) {
            float px = v[2 * j], py = v[2 * j + 1];
            float x = sx(r.x + px * cs - py * sn), y = sy(r.y + px * sn + py * cs);
            polyX[j] = x;
            polyY[j] = y;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        // a non-finite or runaway outline is skipped, never drawn from a stale grid
        if (!LatticeRaster.accepts(polyX, polyY, nv, step, 0f, 0f)) return;
        if (maxX < 0 || maxY < 0 || minX > canvasW || minY > canvasH || maxY < clearTopY) return; // off screen
        gridCol0 = LatticeRaster.firstCell(minX, step, 0f);
        gridRow0 = LatticeRaster.firstCell(minY, step, 0f);
        gridCols = LatticeRaster.cellCount(minX, maxX, step, 0f);
        gridRows = LatticeRaster.cellCount(minY, maxY, step, 0f);
        if ((long) gridCols * gridRows > MAX_ROCK_CELLS) return;
        int need = gridCols * gridRows;
        if (grid.length < need) grid = new float[need + need / 2]; // only when a bigger rock than any before appears
        if (!LatticeRaster.coverage(polyX, polyY, nv, step, 0f, 0f, gridSink)) return;
        lattice.setAlpha(Math.round(fade * 255));
        for (int row = 0; row < gridRows; row++) {
            float cy = (gridRow0 + row + 0.5f) * step;
            if (cy < 0 || cy > canvasH || cy < clearTopY) continue;
            for (int col = 0; col < gridCols; col++) {
                float cov = grid[row * gridCols + col];
                if (cov < MIN_COVERAGE) continue;
                float cx = (gridCol0 + col + 0.5f) * step;
                if (cx < 0 || cx > canvasW) continue;
                float side = Math.min(Math.min(gridAt(col - 1, row), gridAt(col + 1, row)),
                    Math.min(gridAt(col, row - 1), gridAt(col, row + 1)));
                c.drawCircle(cx, cy, LatticeRaster.dotRadius(cov, side, rLight, rReg), lattice);
            }
        }
    }

    private float gridAt(int col, int row) {
        if (col < 0 || row < 0 || col >= gridCols || row >= gridRows) return 0f;
        return grid[row * gridCols + col];
    }

    /**
     * One rock in the outline style: free outline dots (regular) and a seeded body stipple (light), in L65.
     */
    private void outlineRock(Canvas c, GameWorld.Rock r, float fade) {
        if (clearTopY >= 0 && sy(r.y + 1.12f * r.r) + rReg < clearTopY) return;
        RockDots d = rockCache.get(r);
        if (d == null) {
            d = new RockDots();
            d.seed = rockSeed++;
            rockCache.put(r, d);
        }
        if (d.spacing != step || d.radius != r.r) buildRockDots(r, d, step);
        float cs = (float) Math.cos(r.rot), sn = (float) Math.sin(r.rot);
        for (int j = 0; j < d.n; j++) {
            float px = d.pts[3 * j], py = d.pts[3 * j + 1];
            float x = sx(r.x + px * cs - py * sn), y = sy(r.y + px * sn + py * cs);
            if (y < clearTopY) continue;
            dot65.draw(c, x, y, d.pts[3 * j + 2] == 1f ? rReg : rLight, fade);
        }
    }

    /**
     * A circle as one lattice dot row: every S4 cell it passes through, once, in L65 at regular weight. The point
     * walks the circle about one step at a time by a fixed rotation, so there is no trig per dot.
     */
    private void latticeRing(Canvas c, float wx, float wy, float wr, float alpha) {
        float cx = sx(wx), cy = sy(wy), rad = wr * vs;
        int n = Math.max(12, (int) Math.ceil(TAU * rad / step));
        double da = 2 * Math.PI / n, cd = Math.cos(da), sd = Math.sin(da);
        double ux = 1, uy = 0;
        lattice.setAlpha(Math.round(clamp(alpha, 0f, 1f) * 255));
        int firstCol = 0, firstRow = 0, prevCol = 0, prevRow = 0;
        for (int j = 0; j < n; j++) {
            int col = (int) Math.floor((cx + ux * rad) / step), row = (int) Math.floor((cy + uy * rad) / step);
            double nx = ux * cd - uy * sd;
            uy = ux * sd + uy * cd;
            ux = nx;
            if (j == 0) {
                firstCol = col;
                firstRow = row;
            } else if ((col == prevCol && row == prevRow) || (col == firstCol && row == firstRow)) {
                continue;
            }
            prevCol = col;
            prevRow = row;
            float dx = (col + 0.5f) * step, dy = (row + 0.5f) * step;
            if (dx < 0 || dy < 0 || dx > canvasW || dy > canvasH || dy < clearTopY) continue;
            c.drawCircle(dx, dy, rReg, lattice);
        }
    }

    /** Outline dots every `spacing` px plus a stable interior stipple (seeded per rock, so it never flickers). */
    private void buildRockDots(GameWorld.Rock rock, RockDots d, float spacing) {
        float[] v = rock.outline;
        int nv = v.length / 2;
        int stipple = Math.max(0, Math.round(rock.r * rock.r * 2.2f / (spacing * spacing * 3.2f)));
        int cap = stipple;
        for (int i = 0; i < nv; i++) {
            int u = (i + 1) % nv;
            cap += Math.max(1, Math.round(hypot(v[2 * u] - v[2 * i], v[2 * u + 1] - v[2 * i + 1]) / spacing));
        }
        float[] pts = new float[cap * 3];
        int n = 0;
        for (int i = 0; i < nv; i++) {
            int u = (i + 1) % nv;
            float ax = v[2 * i], ay = v[2 * i + 1], bx = v[2 * u], by = v[2 * u + 1];
            int steps = Math.max(1, Math.round(hypot(bx - ax, by - ay) / spacing));
            for (int j = 0; j < steps; j++) {
                float tt = (float) j / steps;
                pts[3 * n] = ax + (bx - ax) * tt;
                pts[3 * n + 1] = ay + (by - ay) * tt;
                pts[3 * n + 2] = 1f;
                n++;
            }
        }
        Rng rng = new Rng(d.seed * 7919L + 13);
        for (int j = 0; j < stipple; j++) {
            float ang = (float) rng.range(0, TAU);
            int idx = (int) (ang / TAU * nv) % nv;
            float vr = hypot(v[2 * idx], v[2 * idx + 1]);
            float rr = (float) Math.sqrt(rng.next()) * vr * 0.8f;
            pts[3 * n] = (float) Math.cos(ang) * rr;
            pts[3 * n + 1] = (float) Math.sin(ang) * rr;
            pts[3 * n + 2] = (float) rng.range(0.18, 0.38);
            n++;
        }
        d.pts = pts;
        d.n = n;
        d.spacing = spacing;
        d.radius = rock.r;
    }

    private void buildShipDots(float sp, float k) {
        int nv = SHIP.length / 2;
        float[] tmp = new float[256];
        int n = 0;
        for (int i = 0; i < nv; i++) {
            int u = (i + 1) % nv;
            float ax = SHIP[2 * i] * k, ay = SHIP[2 * i + 1] * k, bx = SHIP[2 * u] * k, by = SHIP[2 * u + 1] * k;
            int steps = Math.max(1, Math.round(hypot(bx - ax, by - ay) / sp));
            for (int j = 0; j < steps && 2 * n + 2 <= tmp.length; j++) {
                float tt = (float) j / steps;
                tmp[2 * n] = ax + (bx - ax) * tt;
                tmp[2 * n + 1] = ay + (by - ay) * tt;
                n++;
            }
        }
        shipPts = java.util.Arrays.copyOf(tmp, 2 * n);
        shipSpacing = sp;
        shipK = k;
    }

    private float sx(float x) { return vx + vs * x; }

    private float sy(float y) { return vy + vs * y; }

    private static float scaleK(GameWorld w) { return (float) Math.sqrt((double) w.width() * w.height()) / 669f; }

    private static float wrap(float v, float size) {
        float m = v % size;
        return m < 0 ? m + size : m;
    }

    private static float easeOut(float f) {
        float u = 1f - f;
        return 1f - u * u * u;
    }

    private static float easeIn(float f) { return f * f * f; }

    private static float hypot(float a, float b) { return (float) Math.sqrt(a * a + b * b); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
