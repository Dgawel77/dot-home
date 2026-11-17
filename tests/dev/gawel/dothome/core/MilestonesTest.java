package dev.gawel.dothome.core;

import dev.gawel.dothome.test.MapStore;
import dev.gawel.dothome.test.T;
import java.util.Arrays;
import java.util.List;

/** Milestone easter eggs: once-per-day firing, the shapes, and the debris gathering into them. */
public final class MilestonesTest {
    private MilestonesTest() {}

    private static final float W = 620f, H = 722f, DT = 0.016f;

    public static void testMilestonesOncePerDay() {
        Milestones m = new Milestones(new MapStore());
        T.eq(100, m.reached(100, "2026-11-17"), "first 100");
        T.eq(0, m.reached(100, "2026-11-17"), "again");
        T.eq(0, m.reached(99, "2026-11-17"), "99");
        T.eq(250, m.reached(250, "2026-11-17"), "250");
        T.eq(100, m.reached(100, "2026-11-18"), "next day");
    }

    public static void testChicagoStars() {
        float[] p = MilestoneShapes.points(500);
        int n = p.length / 2;
        T.check(n >= 80 && n <= 240, "count " + n);
        float[] xs = new float[n];
        for (int i = 0; i < n; i++) xs[i] = p[2 * i];
        Arrays.sort(xs);
        float spacing = Float.MAX_VALUE;
        for (int i = 1; i < n; i++) if (xs[i] - xs[i - 1] > 1e-4f) spacing = Math.min(spacing, xs[i] - xs[i - 1]);
        int gaps = 0;
        for (int i = 1; i < n; i++) if (xs[i] - xs[i - 1] > 2 * spacing + 1e-4f) gaps++;
        T.eq(3, gaps, "gaps");
        for (int i = 0; i < n; i++) {
            boolean found = false;
            for (int j = 0; j < n && !found; j++) {
                found = Math.abs(p[2 * j] + p[2 * i]) < 1e-4f && Math.abs(p[2 * j + 1] - p[2 * i + 1]) < 1e-4f;
            }
            T.check(found, "mirror of point " + i);
        }
        T.check(xs[n - 1] - xs[0] <= 2f + 1e-4f, "width");
    }

    public static void testDigitsShape() {
        for (int m : Milestones.COUNTS) {
            if (m == 500) continue;
            DotMatrixFont.Grid g = DotMatrixFont.render(String.valueOf(m));
            int lit = 0;
            for (int r = 0; r < g.rows; r++) for (int c = 0; c < g.cols; c++) if (g.lit(c, r)) lit++;
            T.eq(lit, MilestoneShapes.points(m).length / 2, "lit cells of " + m);
        }
    }

    private static GameWorld world() {
        GameWorld g = new GameWorld(new Rng(5));
        g.resize(W, H);
        g.skipIntro();
        g.rocks().clear();
        g.addRock(3, 310, 200, 0, 0);
        return g;
    }

    private static void run(GameWorld g, float secs) {
        for (int i = 0, n = Math.round(secs / DT); i < n; i++) g.step(DT);
    }

    public static void testShapeForms() {
        GameWorld g = world();
        g.killForTest(0);
        float[] pts = MilestoneShapes.points(250);
        g.formShape(pts);
        List<GameWorld.Particle> ps = g.particles();
        int forming = 0;
        for (GameWorld.Particle p : ps) if (p.formT >= 0) forming++;
        T.eq(pts.length / 2, forming, "forming");
        run(g, 0.7f);
        for (GameWorld.Particle p : ps) {
            if (p.formT < 0) continue;
            T.check(Math.abs(p.x - p.tx) <= 1f && Math.abs(p.y - p.ty) <= 1f, "at target");
        }
        run(g, 2.2f); // 2.9 s
        for (GameWorld.Particle p : ps) {
            if (p.formT < 0) continue;
            T.check(Math.abs(p.x - p.tx) <= 1f && Math.abs(p.y - p.ty) <= 1f, "still held");
        }
        run(g, 0.2f); // 3.1 s
        int lit = 0;
        for (GameWorld.Particle p : ps) {
            if (!p.lit) continue;
            lit++;
            T.check(p.formT < 0, "released");
            T.check(p.vx != 0 || p.vy != 0, "speed");
        }
        T.eq(pts.length / 2, lit, "lit count");
    }

    public static void testShapeRespectsParticleCap() {
        GameWorld g = world();
        GameConfig cfg = GameConfig.forSize(W, H);
        for (int i = 0; i < cfg.particleCap - 10; i++) {
            GameWorld.Particle p = new GameWorld.Particle();
            p.life = p.life0 = 1f;
            g.particles().add(p);
        }
        g.formShape(MilestoneShapes.points(500));
        T.check(g.particles().size() <= cfg.particleCap, "size " + g.particles().size());
    }

    public static void testResizeScalesShapeTargets() {
        GameWorld g = world();
        g.killForTest(0);
        g.formShape(MilestoneShapes.points(100));
        GameWorld.Particle p = null;
        for (GameWorld.Particle q : g.particles()) if (q.formT >= 0) { p = q; break; }
        T.check(p != null, "a forming particle");
        float tx = p.tx;
        g.resize(W / 2, H);
        T.near(tx / 2, p.tx, 1e-3, "tx halved");
    }

    public static void testShapeDoesNotTouchGameRng() {
        GameWorld a = world(), b = world();
        a.killForTest(0);
        b.killForTest(0);
        b.formShape(MilestoneShapes.points(250));
        run(a, 4.8f);
        run(b, 4.8f);
        T.eq(a.rocks().size(), b.rocks().size(), "rock count");
        for (int i = 0; i < a.rocks().size(); i++) {
            T.near(a.rocks().get(i).x, b.rocks().get(i).x, 1e-4, "rock x");
            T.near(a.rocks().get(i).y, b.rocks().get(i).y, 1e-4, "rock y");
        }
    }
}
