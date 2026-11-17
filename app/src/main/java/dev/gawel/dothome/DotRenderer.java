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
import dev.gawel.dothome.core.MoonPhase;
import dev.gawel.dothome.core.LatticeRaster;
import dev.gawel.dothome.core.ChargeStream;
import dev.gawel.dothome.core.Precip;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.ScoreBook;
import dev.gawel.dothome.core.ShipShape;
import dev.gawel.dothome.core.Zones;

/**
 * Draws the game on the 4 dp lattice anchored at the surface origin. Rocks are rasterised every frame into filled
 * lattice silhouettes (regular weight on the edge blending to light in the body, ink by sqrt(coverage)), or, with
 * the outline style, drawn as free outline dots with a body stipple. Hit rings are lattice dot rows. The ship
 * (Ship v2 "forked-5", heavy weight), its two main-engine plumes, its two small nose-retro plumes (L65 fading to
 * L46), its RCS puffs (heavier through a barrage's spin-up and spin-down), debris and stars are free soft dots in
 * the greys. The four gun barrels are L65 dots (the under-nose twin under the hull, the wingtip pair over it on a
 * black knockout); each slides back on its shot and springs forward, and every shot kicks the drawn hull back a
 * little (drawing only: the simulation never moves).
 * Laser bolts, the muzzle flash and hit sparks are the one colour, DotStyle.LASER.
 * Dots draw across the whole screen.
 * Only the ship, its opening dot and the plumes reach L100; on a lock still every grey stays at L65 or below and
 * the red is dimmed to 65%. Nothing is allocated per frame: plume, puff and spark dots live in fixed pools.
 *
 * <p>Sizes: positions and lengths are world px (mockup px x k); dot diameters and stroke widths are mockup px on
 * the lattice (4 mockup px = one 4 dp step, so 3.4 is heavy weight).
 */
final class DotRenderer {
    private static final float TAU = (float) (Math.PI * 2);
    /** The forked-5 hull and its nozzle bars: x, y, diameter, level quads in mockup px (ShipShape). */
    private static final float[] HULL = ShipShape.hullDots(), MARKS = ShipShape.nozzleMarks();
    private static final int MARKS_PER_NOZZLE = ShipShape.marksPerNozzle();
    /** A nozzle bar glows from L46 (as alpha on L100) at idle up to L100 at full thrust. */
    private static final float NOZZLE_IDLE = 0.53f;
    /** Plume dots per second per nozzle at full thrust (130 for the pair, as the prototype). */
    private static final float PLUME_RATE = 65f;
    /** Retro plume dots per second per retro at full level, and their level at birth (L65) and at the end (L46). */
    private static final float RETRO_RATE = 40f, RETRO_L65 = ShipShape.LEVEL_L65, RETRO_L46 = NOZZLE_IDLE;
    /** RCS firings weaker than this (fine trim) show no puff. */
    private static final float PUFF_MIN = 0.12f;
    /** A barrage's spin-up and spin-down puff this many times as often, a little longer, with two more dots each. */
    private static final float BARRAGE_PUFFS = 2f, BARRAGE_PUFF_LIFE = 1.3f;
    /** Thruster dots (plumes and puffs) and red sparks alive at once, at most. */
    private static final int FX_MAX = 384, SPARK_MAX = 64;
    /** LASER_GLOW's alpha, stacked from a 3x pass and a 2x pass so the glow reads about 35% beside the core. */
    private static final float GLOW_ALPHA = (DotStyle.LASER_GLOW >>> 24) / 255f, GLOW_OUTER = 0.5f, GLOW_INNER = 0.6f;
    /** A bolt is a pill: a 15 mockup px core line with round caps (about 18 long), 3 thick. */
    private static final float BOLT_LEN = 15f, BOLT_THICK = 3f, SPARK_THICK = 1.6f;
    /** Lock stills dim the laser red to this. */
    private static final float STILL_RED = 0.65f;
    /** A bright star is this much bigger; a glint's cross dots sit one lattice step out. */
    private static final float BRIGHT_SCALE = 1.5f;
    /** The shooting star: trailing dots and the gap between them in mockup px. */
    private static final int METEOR_TAIL = 10;
    private static final float METEOR_GAP = 9f;
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
    /** Precipitation's alpha: it reads as weather, not as stars. */
    private static final float PRECIP_ALPHA = 0.9f;
    private final SoftDot dot65 = new SoftDot(DotStyle.L65);
    private final SoftDot dot46 = new SoftDot(DotStyle.L46);
    private final SoftDot dot10 = new SoftDot(DotStyle.L10);
    private final Paint lattice = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.content.res.Resources res;
    private float density = -1, step, rLight, rReg, rHeavy;
    /** Device px per mockup px of dot or stroke size: a quarter of the lattice step. */
    private float mock;

    private boolean outlineRocks;
    private final WeakHashMap<GameWorld.Rock, RockDots> rockCache = new WeakHashMap<>();
    private long rockSeed = 1;

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

    // thruster dots in world px: both plumes and the RCS puffs (puff marks the faster-damped RCS dots)
    private final float[] fxX = new float[FX_MAX], fxY = new float[FX_MAX], fxVx = new float[FX_MAX];
    private final float[] fxVy = new float[FX_MAX], fxLife = new float[FX_MAX], fxLife0 = new float[FX_MAX];
    /** A dot's level fades from fxLevel at birth to fxLevelEnd at the end of its life (0 for the plumes and puffs). */
    private final float[] fxDia = new float[FX_MAX], fxLevel = new float[FX_MAX], fxLevelEnd = new float[FX_MAX];
    private final boolean[] fxPuff = new boolean[FX_MAX];
    private int fxN;
    // red hit sparks in world px
    private final float[] spX = new float[SPARK_MAX], spY = new float[SPARK_MAX], spVx = new float[SPARK_MAX];
    private final float[] spVy = new float[SPARK_MAX], spLife = new float[SPARK_MAX], spLife0 = new float[SPARK_MAX];
    private int spN;
    private final float[] plumeAcc = new float[ShipShape.NOZZLES], retroAcc = new float[ShipShape.RETROS];
    private float puffAcc = 0.9f;
    // the ship's pose while emitting (set once per advance)
    private float emX, emY, emVx, emVy, emCs, emSn, emK, emDt;
    /** Visual noise for plumes, puffs and sparks; the simulation keeps its own seeded Rng. */
    private final Rng fxRng = new Rng(0x5EEDL);
    private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The black ring that sets the wingtip barrels off the hull, this many mockup px wider than the dot. */
    private final Paint knock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float KNOCK = 0.25f;
    /** One barrel's dots on screen (no barrel has more than this many). */
    private final float[] barrelX = new float[8], barrelY = new float[8];
    private float lastIntroT;

    private float parTx, parTy, parX, parY;

    private int lastToday = -1, lastBest = -1;
    private String scoreText = "";

    // the current draw: screen = (vx, vy) + vs * world; identity live, a centred cover fit for stills
    private float vs = 1f, vsx = 1f, vsy = 1f, vx, vy;
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
        red.setStrokeCap(Paint.Cap.ROUND);
        knock.setColor(DotStyle.GROUND);
        metrics();
        dot65.prewarm(rReg);
        dot10.prewarm(rLight);
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
        mock = step / 4f;
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

    /**
     * Per-frame bookkeeping that is not part of the simulation: the plume and RCS dots from the step's thruster
     * events and the red sparks from its laser hits. Call once after each step().
     */
    void advance(GameWorld w, float dt) {
        if (w.width() <= 0) return;
        float a = 1f - (float) Math.exp(-dt / 0.15f);
        parX += (parTx - parX) * a;
        parY += (parTy - parY) * a;
        float it = w.introTime();
        if (it < lastIntroT) { // the intro restarted: the scene starts from one dot
            fxN = spN = 0;
            java.util.Arrays.fill(plumeAcc, 0f);
            java.util.Arrays.fill(retroAcc, 0f);
            puffAcc = 0.9f;
        }
        lastIntroT = it;
        float fdt = clamp(dt, 0f, 0.05f);
        emitThrusters(w, fdt);
        emitSparks(w);
        moveFx(fdt);
    }

    /**
     * Main-engine plume dots from both nozzles (density follows thrust), the smaller forward plumes of the two nose
     * retros (density follows the retro level) and RCS puffs out of the firing ports.
     */
    private void emitThrusters(GameWorld w, float dt) {
        GameWorld.Ship s = w.ship();
        emX = s.x;
        emY = s.y;
        emVx = s.vx;
        emVy = s.vy;
        emCs = (float) Math.cos(s.angle);
        emSn = (float) Math.sin(s.angle);
        emK = scaleK(w);
        emDt = dt;
        float k = emK, kick = hullKick(w); // plumes and puffs leave the kicked hull, mockup px back
        for (int i = 0; i < ShipShape.NOZZLES; i++) {
            float thr = w.nozzleThrust(i);
            plumeAcc[i] = Math.min(2f, plumeAcc[i] + thr * PLUME_RATE * dt);
            while (plumeAcc[i] >= 1f) {
                plumeAcc[i] -= 1f;
                float sp = rnd(120f, 200f) * k, spread = rnd(-0.2f, 0.2f);
                float ly = ShipShape.nozzleY(i) + rnd(-1f, 1f) * ShipShape.NOZZLE_SPREAD;
                addFx(ShipShape.NOZZLE_X - kick, ly, -(float) Math.cos(spread) * sp, (float) Math.sin(spread) * sp,
                    rnd(0.24f, 0.34f), 1.8f + thr, 1f, 0f, false);
            }
        }
        float rl = w.retroThrust();
        for (int i = 0; i < ShipShape.RETROS; i++) {
            retroAcc[i] = Math.min(2f, retroAcc[i] + rl * RETRO_RATE * dt);
            while (retroAcc[i] >= 1f) {
                retroAcc[i] -= 1f;
                float sp = rnd(70f, 120f) * k, side = i == 0 ? -1f : 1f;
                float spread = side * (ShipShape.RETRO_SPLAY + rnd(-0.15f, 0.15f));
                addFx(ShipShape.RETRO_X - kick, ShipShape.retroY(i), (float) Math.cos(spread) * sp,
                    (float) Math.sin(spread) * sp, rnd(0.14f, 0.2f), 1.2f + 0.5f * rl, RETRO_L65, RETRO_L46, false);
            }
        }
        int n = w.rcsFirings();
        float ra = 0f;
        for (int j = 0; j < n; j++) ra = Math.max(ra, w.rcsStrength(j));
        if (ra < PUFF_MIN) {
            puffAcc = 0.9f; // the next firing puffs at once
            return;
        }
        boolean heavy = w.isBarrage();
        float rate = (2f + ra * 24f) * (heavy ? BARRAGE_PUFFS : 1f), life = heavy ? BARRAGE_PUFF_LIFE : 1f;
        puffAcc = Math.min(3f, puffAcc + rate * dt);
        while (puffAcc >= 1f) {
            puffAcc -= 1f;
            for (int j = 0; j < n; j++) {
                int port = w.rcsPort(j), side = w.rcsSide(j);
                float px = ShipShape.rcsX(port) - kick, py = ShipShape.rcsY(port, side);
                for (int m = 2 + fxRng.nextInt(3) + (heavy ? 2 : 0); m > 0; m--) {
                    addFx(px + rnd(-1f, 1f), py + side * 1.5f, rnd(-13f, 13f) * k, side * rnd(60f, 120f) * k,
                        rnd(0.15f, 0.23f) * life, 2.2f, 0.9f, 0f, true);
                }
            }
        }
    }

    /**
     * One thruster dot from ship-local (lx, ly) mockup px, ejected at local (ex, ey) px/s on top of the ship's
     * velocity, born at a random moment inside the frame so a steady plume does not band. Its level fades from
     * level to levelEnd over its life.
     */
    private void addFx(float lx, float ly, float ex, float ey, float life, float dia, float level, float levelEnd,
            boolean puff) {
        if (fxN >= FX_MAX) return;
        float wx = ex * emCs - ey * emSn, wy = ex * emSn + ey * emCs, u = (float) fxRng.next() * emDt;
        int i = fxN++;
        fxX[i] = emX + (lx * emCs - ly * emSn) * emK + wx * u;
        fxY[i] = emY + (lx * emSn + ly * emCs) * emK + wy * u;
        fxVx[i] = emVx + wx;
        fxVy[i] = emVy + wy;
        fxLife[i] = life - u;
        fxLife0[i] = life;
        fxDia[i] = dia;
        fxLevel[i] = level;
        fxLevelEnd[i] = levelEnd;
        fxPuff[i] = puff;
    }

    /** 5-7 red sparks thrown back from each laser hit, about 0.15 s. */
    private void emitSparks(GameWorld w) {
        float k = scaleK(w);
        for (int j = 0; j < w.laserHits(); j++) {
            float a = w.laserHitAngle(j) + (float) Math.PI;
            for (int m = 5 + fxRng.nextInt(3); m > 0 && spN < SPARK_MAX; m--) {
                float ang = a + rnd(-1.2f, 1.2f), sp = rnd(140f, 360f) * k;
                int i = spN++;
                spX[i] = w.laserHitX(j);
                spY[i] = w.laserHitY(j);
                spVx[i] = (float) Math.cos(ang) * sp;
                spVy[i] = (float) Math.sin(ang) * sp;
                spLife[i] = spLife0[i] = rnd(0.1f, 0.17f);
            }
        }
    }

    /** Moves, damps and ages the pooled dots, compacting the live ones to the front. */
    private void moveFx(float dt) {
        float plume = (float) Math.exp(-1.5f * dt), puff = (float) Math.exp(-5f * dt);
        int n = 0;
        for (int i = 0; i < fxN; i++) {
            float life = fxLife[i] - dt;
            if (life <= 0) continue;
            float e = fxPuff[i] ? puff : plume;
            fxX[n] = fxX[i] + fxVx[i] * dt;
            fxY[n] = fxY[i] + fxVy[i] * dt;
            fxVx[n] = fxVx[i] * e;
            fxVy[n] = fxVy[i] * e;
            fxLife[n] = life;
            fxLife0[n] = fxLife0[i];
            fxDia[n] = fxDia[i];
            fxLevel[n] = fxLevel[i];
            fxLevelEnd[n] = fxLevelEnd[i];
            fxPuff[n] = fxPuff[i];
            n++;
        }
        fxN = n;
        float se = (float) Math.exp(-4f * dt);
        n = 0;
        for (int i = 0; i < spN; i++) {
            float life = spLife[i] - dt;
            if (life <= 0) continue;
            spX[n] = spX[i] + spVx[i] * dt;
            spY[n] = spY[i] + spVy[i] * dt;
            spVx[n] = spVx[i] * se;
            spVy[n] = spVy[i] * se;
            spLife[n] = life;
            spLife0[n] = spLife0[i];
            n++;
        }
        spN = n;
    }

    private float rnd(float lo, float hi) { return lo + (hi - lo) * (float) fxRng.next(); }

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
        vsx = w.burstScaleX();
        vsy = w.burstScaleY();
        vx = (1f - vsx) * c.getWidth() / 2f;
        vy = (1f - vsy) * c.getHeight() / 2f;
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
     * Renders the current world into a new bitmap of the given size. rock, particle, shot, ship and ring dots above clearTopFraction of the height are skipped
     * so that band stays clear. The world is scaled uniformly to cover the target and centred (excess is cropped);
     * the lattice and dot sizes are the target screen's, every grey is capped at L65 (the ship and its plumes too)
     * and the laser red is dimmed to 65%. Plumes and sparks come from this renderer's pools.
     */
    Bitmap renderStill(GameWorld w, int width, int height, float clearTopFraction) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(DotStyle.GROUND);
        if (w.width() > 0 && w.height() > 0) {
            metrics();
            float s = Math.max(width / w.width(), height / w.height());
            still = true;
            vs = vsx = vsy = s;
            vx = (width - s * w.width()) / 2f;
            vy = (height - s * w.height()) / 2f;
            canvasW = width;
            canvasH = height;
            clearTopY = clearTopFraction * height;
            try {
                drawScene(c, w);
            } finally {
                still = false;
                vs = vsx = vsy = 1f;
                vx = vy = 0f;
                clearTopY = -1f;
            }
        }
        return bmp;
    }

    /** The charging stream: light dots, L46 blending to L65 as they close on the ship. */
    private void drawCharge(Canvas c, GameWorld w, float fade) {
        ChargeStream cs = w.chargeStream();
        for (int i = 0; i < cs.n; i++) {
            float qx = sx(cs.x[i]), qy = sy(cs.y[i]);
            if (qy < clearTopY) continue;
            float g = cs.glow[i];
            dot46.draw(c, qx, qy, rLight, (1f - g) * fade);
            dot65.draw(c, qx, qy, rLight, g * fade);
        }
    }

    /** Snow as soft dots (L65 or L46), rain as a three-dot streak of L46 trailing the drop; never above L65. */
    private void drawPrecip(Canvas c, GameWorld w, float fade) {
        Precip p = w.precip();
        for (int i = 0; i < p.n; i++) {
            float qx = sx(p.x[i]), qy = sy(p.y[i]);
            if (p.snow) {
                if (qy < clearTopY) continue;
                (p.light[i] ? dot65 : dot46).draw(c, qx, qy, rLight * p.size[i], PRECIP_ALPHA * fade);
            } else {
                float len = (float) Math.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i]);
                if (len < 1e-3f) continue;
                float ux = -p.vx[i] / len * step * vs, uy = -p.vy[i] / len * step * vs;
                for (int q = 0; q < 3; q++) {
                    float dx = qx + ux * q, dy = qy + uy * q;
                    if (dy < clearTopY) continue;
                    dot46.draw(c, dx, dy, rLight, PRECIP_ALPHA * fade);
                }
            }
        }
    }

    /**
     * The night moon: light dots on the 4 dp lattice within the moon's radius, lit cells L65 and unlit L10 (both
     * within the still cap). It fades out as the day comes and is not drawn when the sun times are unknown.
     */
    private void drawMoon(Canvas c, GameWorld w, GameConfig cfg, float fade) {
        float d = w.dayness();
        if (d != d) return;
        float alpha = clamp(1f - 2f * d, 0f, 1f) * fade;
        if (alpha <= 0f) return;
        float rad = cfg.moonRadiusDp * density;
        float cx = GameConfig.moonCentreX(w.width(), w.height()), cy = GameConfig.moonCentreY(w.height());
        float phase = MoonPhase.phase(System.currentTimeMillis());
        int n = (int) Math.ceil(rad / step) + 1;
        float ox = Lattice.snap(cx, density), oy = Lattice.snap(cy, density);
        for (int j = -n; j <= n; j++) {
            for (int i = -n; i <= n; i++) {
                float px = ox + i * step, py = oy + j * step;
                float ux = (px - cx) / rad, uy = (py - cy) / rad;
                int cell = MoonPhase.cell(ux, uy, phase);
                if (cell == 0) continue;
                float qx = sx(px), qy = sy(py);
                if (qy < clearTopY) continue;
                (cell == 2 ? dot65 : dot10).draw(c, qx, qy, rLight, alpha);
            }
        }
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

        float fog = w.weather().fog ? cfg.fogStarDim : 1f;
        List<GameWorld.Star> stars = w.stars();
        // by day fewer, dimmer stars (the sun's real times); unknown counts as night
        float day = w.dayness();
        if (day != day) day = 0f;
        int starsShown = Math.round(stars.size() * (1f + (cfg.dayStarCount - 1f) * day));
        float dayDim = 1f + (cfg.dayStarBright - 1f) * day;
        for (int i = 0; i < starsShown && i < stars.size(); i++) {
            GameWorld.Star st = stars.get(i);
            float x = st.x, y = st.y;
            if (!still) {
                x = wrap(x + parX * st.depth * 28f * k, w.width());
                y = wrap(y + parY * st.depth * 28f * k, w.height());
            }
            float qx = sx(x), qy = sy(y);
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(TAU * t / st.period + st.phase));
            float r = st.depth >= NEAR_DEPTH ? rLight * NEAR_SCALE : rLight;
            if (st.bright) r = rLight * BRIGHT_SCALE;
            float a = st.alpha * twinkle;
            SoftDot sd = st.bright ? user : dot65;
            if (!still && st.glint > 0f) {
                // a glint flares up to full alpha at L100 and fades, with four small dots a step out
                float g = st.glint / GameWorld.GLINT_SECONDS, e = (float) Math.sin(Math.PI * (1f - g));
                a = a + (1f - a) * e;
                sd = user;
                float cr = rLight * 0.7f, cs = step;
                float[] cx = {cs, -cs, 0, 0}, cy = {0, 0, cs, -cs};
                for (int q = 0; q < 4; q++) dot65.draw(c, qx + cx[q], qy + cy[q], cr, e * g * fade);
            }
            sd.draw(c, qx, qy, r, a * fog * dayDim * fade);
        }
        drawMoon(c, w, cfg, fade);
        drawPrecip(c, w, fade);

        GameWorld.Meteor m = still ? null : w.meteor();
        if (m != null) {
            // head at L100, regular weight; the tail trails behind and fades in alpha and size to nothing
            user.draw(c, sx(m.x), sy(m.y), rReg, m.alpha);
            for (int q = 1; q <= METEOR_TAIL; q++) {
                float f = 1f - (float) q / (METEOR_TAIL + 1);
                float d = q * METEOR_GAP * k;
                user.draw(c, sx(m.x - m.dx * d), sy(m.y - m.dy * d), rReg * f, m.alpha * f);
            }
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
            if (p.lit) user.draw(c, qx, qy, rReg, p.formT >= 0f ? p.alpha : p.alpha * lk); // released: fades like debris
            else dot65.draw(c, qx, qy, rReg * (0.45f + 0.55f * lk), p.alpha * lk);
        }

        // the plumes, retro plumes and RCS puffs: free dots that shrink and fade over their short life
        for (int i = 0; i < fxN; i++) {
            float qx = sx(fxX[i]), qy = sy(fxY[i]);
            if (qy < clearTopY) continue;
            float q = fxLife[i] / fxLife0[i];
            float lv = fxLevelEnd[i] + (fxLevel[i] - fxLevelEnd[i]) * q;
            user.draw(c, qx, qy, (0.7f + (fxDia[i] - 0.7f) * q) * mock / 2f, lv);
        }

        drawCharge(c, w, fade);
        GameWorld.Ship s = w.ship();
        boolean shipClear = clearTopY < 0 || sy(s.y - 12f * k) >= clearTopY;
        if (shipClear && it < intro) {
            // the opening dot: one pulsing hero dot that hands over to the ship
            float pr = rHeavy * (1f + Math.abs((float) Math.sin(it * 3.2f)));
            user.draw(c, sx(s.x), sy(s.y), pr, 1f - easeIn(hand));
        }
        float sa = easeOut(hand);
        if (shipClear && sa > 0) {
            float cs = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
            float blink = s.hitCooldown > 0 ? 1f - 0.45f * (float) Math.sin(Math.PI * (0.4f - s.hitCooldown) / 0.4f) : 1f;
            // the hull kick: the drawn ship sits back along its heading (lock stills show it at rest)
            float kick = still ? 0f : hullKick(w) * k, hx = s.x - kick * cs, hy = s.y - kick * sn;
            drawBarrels(c, w, hx, hy, cs, sn, k, sa * blink, true);
            boolean dim = w.shipDim(); // under 15% and not charging: the hull and bars draw at L46
            for (int j = 0; j < HULL.length; j += 4) {
                float px = HULL[j] * k, py = HULL[j + 1] * k;
                SoftDot d = dim ? dot46 : HULL[j + 3] >= ShipShape.LEVEL_L100 ? user : dot65;
                d.draw(c, sx(hx + px * cs - py * sn), sy(hy + px * sn + py * cs), HULL[j + 2] * mock / 2f, sa * blink);
            }
            // the nozzle bars grow and brighten with their engine's thrust
            for (int j = 0; j < MARKS.length; j += 4) {
                float thr = w.nozzleThrust(j / 4 / MARKS_PER_NOZZLE);
                float px = MARKS[j] * k, py = MARKS[j + 1] * k;
                (dim ? dot46 : user).draw(c, sx(hx + px * cs - py * sn), sy(hy + px * sn + py * cs),
                    (MARKS[j + 2] + 1.4f * thr) * MARKS[j + 3] * mock / 2f, (NOZZLE_IDLE + (1f - NOZZLE_IDLE) * thr) * sa);
            }
            drawBarrels(c, w, hx, hy, cs, sn, k, sa * blink, false);
        }

        List<GameWorld.Ring> rings = w.rings();
        for (int i = 0; i < rings.size(); i++) {
            GameWorld.Ring g = rings.get(i);
            float rk = g.t / cfg.ringLife;
            if (rk < 1f) latticeRing(c, g.x, g.y, (8f + rk * 62f) * k, 1f - rk);
        }

        drawLasers(c, w, k);
    }

    /** The hull kick of the guns' recent shots, ship units back (ShipShape.kick summed, capped at KICK_MAX). */
    private static float hullKick(GameWorld w) {
        float sum = 0f;
        for (int g = 0; g < ShipShape.GUNS; g++) sum += ShipShape.kick(w.gunAge(g));
        return Math.min(ShipShape.KICK_MAX, sum);
    }

    /**
     * The gun barrels under the hull (the nose twin) or over it (the wingtips, on a black knockout ring so they read
     * apart from the L100 hull), as L65 dots along each barrel's axis, slid back by its recoil (at rest on stills).
     * (hx, hy) is the ship's drawn origin, kicked.
     */
    private void drawBarrels(Canvas c, GameWorld w, float hx, float hy, float cs, float sn, float k, float alpha,
            boolean under) {
        knock.setAlpha(Math.round(clamp(alpha, 0f, 1f) * 255));
        for (int g = 0; g < ShipShape.GUNS; g++) {
            if (ShipShape.gunUnder(g) != under) continue;
            float off = still ? 0f : ShipShape.recoil(w.gunAge(g)) * ShipShape.recoilStroke(g);
            float ga = ShipShape.gunAngle(g), gc = (float) Math.cos(ga), gs = (float) Math.sin(ga);
            float bx = ShipShape.breechX(g), by = ShipShape.breechY(g), gap = ShipShape.gunGap(g);
            float r = ShipShape.gunDot(g) * mock / 2f;
            int n = ShipShape.gunDots(g);
            for (int i = 0; i < n; i++) {
                float a = i * gap - off, lx = (bx + gc * a) * k, ly = (by + gs * a) * k;
                barrelX[i] = sx(hx + lx * cs - ly * sn);
                barrelY[i] = sy(hy + lx * sn + ly * cs);
            }
            if (!under) {
                for (int i = 0; i < n; i++) {
                    if (barrelY[i] >= clearTopY) c.drawCircle(barrelX[i], barrelY[i], r + KNOCK * mock, knock);
                }
            }
            for (int i = 0; i < n; i++) if (barrelY[i] >= clearTopY) dot65.draw(c, barrelX[i], barrelY[i], r, alpha);
        }
    }

    /**
     * The one colour, on top of everything: each bolt a short glowing pill stretched along its path (a 3x and a 2x
     * glow pass in LASER_GLOW, then the LASER core), the muzzle flash at the firing point, and the hit sparks.
     * A bolt stretches 35% just after launch and squashes into a flare where it hits.
     */
    private void drawLasers(Canvas c, GameWorld w, float k) {
        List<GameWorld.Shot> shots = w.shots();
        GameWorld.Flash fl = w.flash();
        if (shots.isEmpty() && spN == 0 && !(fl.t > 0)) return;
        GameConfig cfg = w.config();
        float dim = still ? STILL_RED : 1f;
        red.setStyle(Paint.Style.STROKE);
        for (int pass = 0; pass < 3; pass++) {
            float wide = pass == 0 ? 3f : pass == 1 ? 2f : 1f;
            float share = pass == 0 ? GLOW_ALPHA * GLOW_OUTER : pass == 1 ? GLOW_ALPHA * GLOW_INNER : 1f;
            red.setColor(pass < 2 ? DotStyle.LASER_GLOW : DotStyle.LASER);
            for (int i = 0; i < shots.size(); i++) {
                GameWorld.Shot b = shots.get(i);
                float x1 = sx(b.x), y1 = sy(b.y);
                if (y1 < clearTopY) continue;
                float len, th, al = 1f;
                if (b.hit > 0) {
                    float q = b.hit / cfg.shotHitFlare;
                    len = BOLT_LEN * (0.15f + 0.35f * q) * k;
                    th = BOLT_THICK * (1.6f - 0.6f * q);
                    al = 0.4f + 0.6f * q;
                } else {
                    float st = 1f + 0.35f * Math.max(0f, 1f - b.t / 0.1f);
                    len = Math.min(BOLT_LEN * st * k, b.trav);
                    th = BOLT_THICK / (float) Math.sqrt(st);
                }
                float ux = (float) Math.cos(b.angle) * len * vsx, uy = (float) Math.sin(b.angle) * len * vsy;
                red.setAlpha(Math.round(255f * clamp(share * al * dim, 0f, 1f)));
                red.setStrokeWidth(th * wide * mock);
                c.drawLine(x1 - ux, y1 - uy, x1, y1, red);
            }
        }
        if (fl.t > 0) {
            GameWorld.Ship s = w.ship();
            float q = fl.t / cfg.muzzleFlash, cs = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
            float fa = s.angle + fl.angle;
            float x = sx(s.x + fl.x * cs - fl.y * sn + (float) Math.cos(fa) * 2f * k);
            float y = sy(s.y + fl.x * sn + fl.y * cs + (float) Math.sin(fa) * 2f * k);
            if (y >= clearTopY) {
                float r = (1.2f + 1.6f * q) * mock;
                red.setStyle(Paint.Style.FILL);
                red.setColor(DotStyle.LASER_GLOW);
                red.setAlpha(Math.round(255f * GLOW_ALPHA * q * dim));
                c.drawCircle(x, y, r * 2.5f, red);
                red.setColor(DotStyle.LASER);
                red.setAlpha(Math.round(255f * q * dim));
                c.drawCircle(x, y, r, red);
                red.setStyle(Paint.Style.STROKE);
            }
        }
        red.setColor(DotStyle.LASER);
        red.setStrokeWidth(SPARK_THICK * mock);
        for (int i = 0; i < spN; i++) {
            float x = sx(spX[i]), y = sy(spY[i]);
            if (y < clearTopY) continue;
            red.setAlpha(Math.round(255f * clamp(spLife[i] / spLife0[i] * dim, 0f, 1f)));
            c.drawLine(sx(spX[i] - spVx[i] * 0.014f), sy(spY[i] - spVy[i] * 0.014f), x, y, red);
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

    private float sx(float x) { return vx + vsx * x; }

    private float sy(float y) { return vy + vsy * y; }

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
