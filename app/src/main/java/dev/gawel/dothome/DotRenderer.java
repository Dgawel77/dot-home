package dev.gawel.dothome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;
import java.util.WeakHashMap;

import dev.gawel.dothome.core.GameWorld;
import dev.gawel.dothome.core.Rng;
import dev.gawel.dothome.core.ScoreBook;

/** Draws the game as dots on a Canvas; a port of the mockup's _draw (Phase 1 parts only). */
final class DotRenderer {
    private static final float TAU = (float) (Math.PI * 2);
    private static final float[] SHIP = {15, 0, -10, -9.5f, -5, 0, -10, 9.5f};
    private static final int TRAIL_MAX = 48;

    /** Cached dots of one rock: x, y, alpha triples (alpha 1 marks an outline dot), valid for one spacing. */
    private static final class RockDots {
        float spacing;
        long seed;
        float[] pts = new float[0];
        int n;
    }

    private final Paint dotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap sprite;
    private final Rect spriteSrc = new Rect(0, 0, 32, 32);
    private final RectF dst = new RectF();
    private final RectF zoneRect = new RectF();
    private final WeakHashMap<GameWorld.Rock, RockDots> rockCache = new WeakHashMap<>();
    private long rockSeed = 1;
    private float[] shipPts = new float[0];
    private float shipSpacing = -1;

    private final float[] trailX = new float[TRAIL_MAX];
    private final float[] trailY = new float[TRAIL_MAX];
    private final float[] trailLife = new float[TRAIL_MAX];
    private int trailHead;
    private float trailT;

    private float parTx, parTy, parX, parY;

    private int lastToday = -1, lastBest = -1;
    private String scoreText = "";

    /** Y below which rock and particle dots are skipped (renderStill); negative means draw everything. */
    private float clearTopY = -1;

    DotRenderer(Context context) {
        sprite = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(sprite).drawCircle(16, 16, 15, p);
        textPaint.setTypeface(Fonts.doto(context));
        textPaint.setColor(0xFFA6A6A6);
        textPaint.setTextAlign(Paint.Align.RIGHT);
    }

    /** Target parallax tilt in [-1, 1]; the drawn offset eases toward it. */
    void setParallax(float tx, float ty) {
        parTx = clamp(tx == tx ? tx : 0f, -1f, 1f);
        parTy = clamp(ty == ty ? ty : 0f, -1f, 1f);
    }

    /** Per-frame bookkeeping that is not part of the simulation (the ship's exhaust trail). Call after step(). */
    void advance(GameWorld w, float dt) {
        if (w.width() <= 0) return;
        float k = scaleK(w);
        float a = 1f - (float) Math.exp(-dt / 0.15f);
        parX += (parTx - parX) * a;
        parY += (parTy - parY) * a;
        for (int i = 0; i < TRAIL_MAX; i++) if (trailLife[i] > 0) trailLife[i] -= dt;
        trailT += dt;
        GameWorld.Ship s = w.ship();
        float sp = (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy);
        if (trailT > 0.04f && sp > 10f * k) {
            trailT = 0;
            trailX[trailHead] = s.x - (float) Math.cos(s.angle) * 9f * k;
            trailY[trailHead] = s.y - (float) Math.sin(s.angle) * 9f * k;
            trailLife[trailHead] = 0.7f;
            trailHead = (trailHead + 1) % TRAIL_MAX;
        }
    }

    void draw(Canvas c, GameWorld w, List<float[]> zonesPx, ScoreBook sb) {
        c.drawColor(Color.BLACK);
        if (w.width() <= 0) return;
        drawScene(c, w, true);
        fillPaint.setColor(0x99000000);
        float k = scaleK(w);
        float topZoneBottom = -1, topZoneRight = 0;
        if (zonesPx != null) {
            for (int i = 0; i < zonesPx.size(); i++) {
                float[] z = zonesPx.get(i);
                zoneRect.set(z[0], z[1], z[0] + z[2], z[1] + z[3]);
                c.drawRoundRect(zoneRect, 26f * k, 26f * k, fillPaint);
                if (i == 0) {
                    topZoneBottom = z[1] + z[3];
                    topZoneRight = z[0] + z[2];
                }
            }
        }
        if (sb != null) {
            int today = sb.today(), best = sb.best();
            if (today != lastToday || best != lastBest) {
                lastToday = today;
                lastBest = best;
                scoreText = "TODAY " + today + " · BEST " + best;
            }
            textPaint.setTextSize(16f * k);
            float right = topZoneBottom >= 0 ? topZoneRight : w.width() - 16f * k;
            float top = topZoneBottom >= 0 ? topZoneBottom : 0;
            c.drawText(scoreText, right, top + 8f * k + 16f * k, textPaint);
        }
    }

    /**
     * Renders the current world into a new bitmap of the given size. dim (0..1) darkens the picture with black;
     * rock and particle dots above clearTopFraction of the height are skipped so that band stays clear.
     * The world is scaled uniformly to cover the target and centred (excess is cropped), so dots never stretch.
     */
    Bitmap renderStill(GameWorld w, int width, int height, float dim, float clearTopFraction) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        if (w.width() > 0 && w.height() > 0) {
            // uniform "cover" scale, centred: dots stay round when the target aspect differs from the world's
            float s = Math.max(width / w.width(), height / w.height());
            c.save();
            c.translate(width / 2f, height / 2f);
            c.scale(s, s);
            c.translate(-w.width() / 2f, -w.height() / 2f);
            // world y that lands at clearTopFraction of the target height
            clearTopY = (clearTopFraction * height - height / 2f) / s + w.height() / 2f;
            try {
                drawScene(c, w, false);
            } finally {
                clearTopY = -1;
            }
            c.restore();
        }
        if (dim > 0) c.drawColor(Color.argb(Math.round(Math.min(1f, dim) * 255), 0, 0, 0));
        return bmp;
    }

    private void drawScene(Canvas c, GameWorld w, boolean withTrail) {
        float k = scaleK(w);
        float ds = 1.2f * k, spacing = 4f * k;
        float t = w.time();
        float f = clamp((t - 0.5f) / 1.1f, 0, 1);
        float fade = f * f * (3 - 2 * f);

        List<GameWorld.Star> stars = w.stars();
        for (int i = 0; i < stars.size(); i++) {
            GameWorld.Star st = stars.get(i);
            float sx = st.x, sy = st.y;
            if (withTrail) {
                sx = wrap(sx + parX * st.depth * 28f * k, w.width());
                sy = wrap(sy + parY * st.depth * 28f * k, w.height());
            }
            dot(c, sx, sy, ds * 0.55f, st.alpha * (0.65f + 0.35f * (float) Math.sin(t * st.speed * 2 + st.phase)) * fade);
        }

        List<GameWorld.Rock> rocks = w.rocks();
        for (int i = 0; i < rocks.size(); i++) {
            GameWorld.Rock r = rocks.get(i);
            if (clearTopY >= 0 && r.y + 1.12f * r.r + ds < clearTopY) continue;
            RockDots d = rockCache.get(r);
            if (d == null) {
                d = new RockDots();
                d.seed = rockSeed++;
                rockCache.put(r, d);
            }
            if (d.spacing != spacing) buildRockDots(r, d, spacing);
            float cs = (float) Math.cos(r.rot), sn = (float) Math.sin(r.rot);
            for (int j = 0; j < d.n; j++) {
                float px = d.pts[3 * j], py = d.pts[3 * j + 1], a = d.pts[3 * j + 2];
                float x = r.x + px * cs - py * sn, y = r.y + px * sn + py * cs;
                if (clearTopY >= 0 && y < clearTopY) continue;
                if (a == 1f) dot(c, x, y, ds, 0.9f * fade);
                else dot(c, x, y, ds * 0.75f, a * fade);
            }
        }

        List<GameWorld.Particle> parts = w.particles();
        for (int i = 0; i < parts.size(); i++) {
            GameWorld.Particle p = parts.get(i);
            if (clearTopY >= 0 && p.y < clearTopY) continue;
            float lk = p.life / p.life0;
            dot(c, p.x, p.y, ds * (0.45f + 0.55f * lk), p.alpha * lk);
        }

        if (withTrail) {
            for (int i = 0; i < TRAIL_MAX; i++) {
                if (trailLife[i] <= 0) continue;
                float lk = trailLife[i] / 0.7f;
                dot(c, trailX[i], trailY[i], ds * (0.3f + 0.6f * lk), 0.55f * lk * fade);
            }
        }

        List<GameWorld.Shot> shots = w.shots();
        for (int i = 0; i < shots.size(); i++) {
            GameWorld.Shot sh = shots.get(i);
            dot(c, sh.x, sh.y, ds * 1.4f, 1f);
            dot(c, sh.x - sh.vx * 0.012f, sh.y - sh.vy * 0.012f, ds, 0.45f);
            dot(c, sh.x - sh.vx * 0.024f, sh.y - sh.vy * 0.024f, ds * 0.8f, 0.2f);
        }

        GameWorld.Ship s = w.ship();
        if (t < 1.4f) {
            float pr = ds * (1.3f + 1.4f * Math.abs((float) Math.sin(t * 3.2f)));
            dot(c, s.x, s.y, pr, 1f - clamp((t - 1.0f) / 0.4f, 0, 1));
        }
        float sa = clamp((t - 0.9f) / 0.5f, 0, 1);
        if (sa > 0) {
            float sp = spacing * 0.55f;
            if (sp != shipSpacing) buildShipDots(sp, k);
            float cs = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
            float blink = s.hitCooldown > 0 ? 0.55f + 0.45f * (float) Math.cos(s.hitCooldown * 40) : 1f;
            for (int j = 0; j < shipPts.length; j += 2) {
                float px = shipPts[j], py = shipPts[j + 1];
                dot(c, s.x + px * cs - py * sn, s.y + px * sn + py * cs, ds * 1.05f, sa * blink);
            }
        }

        GameWorld.Shockwave sw = w.wave();
        if (sw != null && w.config() != null) {
            float dur = w.config().shockDuration;
            float al = 1f - sw.t / dur;
            float rad = sw.radius;
            if (al > 0 && rad > 1f) {
                int n = Math.max(12, Math.round(TAU * rad / (6f * k)));
                for (int j = 0; j < n; j++) {
                    float ang = j * TAU / n;
                    float wx = sw.x + (float) Math.cos(ang) * rad, wy = sw.y + (float) Math.sin(ang) * rad;
                    if (wx < -ds * 2 || wy < -ds * 2 || wx > w.width() + ds * 2 || wy > w.height() + ds * 2) continue;
                    dot(c, wx, wy, ds * 1.2f, al);
                }
            }
        }

        List<GameWorld.Ring> rings = w.rings();
        for (int i = 0; i < rings.size(); i++) {
            GameWorld.Ring g = rings.get(i);
            float rk = g.t / 0.8f, rad = (8f + rk * 62f) * k;
            for (int j = 0; j < 30; j++) {
                float a = j / 30f * TAU;
                dot(c, g.x + (float) Math.cos(a) * rad, g.y + (float) Math.sin(a) * rad, ds * (1.25f - 0.6f * rk), (1f - rk) * 0.95f);
            }
        }
    }

    private void dot(Canvas c, float x, float y, float r, float a) {
        if (a <= 0.01f || r <= 0.05f) return;
        dotPaint.setAlpha(Math.round((a > 1f ? 1f : a) * 255));
        dst.set(x - r, y - r, x + r, y + r);
        c.drawBitmap(sprite, spriteSrc, dst, dotPaint);
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
    }

    private static float scaleK(GameWorld w) { return (float) Math.sqrt((double) w.width() * w.height()) / 669f; }

    private static float wrap(float v, float size) {
        float m = v % size;
        return m < 0 ? m + size : m;
    }

    private static float hypot(float a, float b) { return (float) Math.sqrt(a * a + b * b); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
