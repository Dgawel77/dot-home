package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** Star periods and brightness, glints and the shooting star. */
public final class StarsTest {
    private StarsTest() {}

    private static final float DT = 0.016f;

    private static GameWorld world(long seed) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        return g;
    }

    public static void testStarPeriodsAreTwoOrFourSecondsAndAlphaInRange() {
        for (long seed = 1; seed <= 4; seed++) {
            GameWorld g = world(seed);
            int two = 0, four = 0, bright = 0;
            for (GameWorld.Star s : g.stars()) {
                T.check(s.period == 2f || s.period == 4f, "period " + s.period);
                if (s.period == 2f) two++; else four++;
                if (s.bright) bright++;
                T.check(s.alpha >= 0.12f && s.alpha <= 0.55f, "alpha " + s.alpha);
            }
            T.check(two > 0 && four > 0, "both periods occur: " + two + "/" + four);
            T.check(bright < g.stars().size() / 2, "a minority bright: " + bright + "/" + g.stars().size());
        }
    }

    public static void testGlintFrequencyOverAMinute() {
        GameWorld g = world(3);
        g.skipIntro();
        for (int i = 0, n = Math.round(60f / DT); i < n; i++) g.step(DT);
        int n = g.glintCount();
        T.check(n >= 9 && n <= 21, "glints in 60 s: " + n);
    }

    public static void testGlintFlaresOneStarForPointThreeSeconds() {
        GameWorld g = world(3);
        g.skipIntro();
        int seen = 0;
        for (int i = 0; i < Math.round(30f / DT); i++) {
            g.step(DT);
            int active = 0;
            for (GameWorld.Star s : g.stars()) {
                if (s.glint > 0) active++;
                T.check(s.glint <= GameWorld.GLINT_SECONDS + 1e-6f, "glint " + s.glint);
            }
            T.check(active <= 1, "one glint at a time");
            seen += active;
        }
        T.check(seen > 0, "glints happen");
    }

    public static void testShootingStarGapsStayWithin20And60SecondsOneAtATime() {
        for (long seed = 1; seed <= 3; seed++) {
            GameWorld g = world(seed);
            g.skipIntro();
            float lastStart = 0f;
            int starts = 0;
            boolean was = false;
            float steps = 0;
            for (int i = 0, n = Math.round(1200f / DT); i < n; i++) {
                g.step(DT);
                steps = g.time();
                boolean now = g.meteor() != null;
                if (now && !was) {
                    starts++;
                    float gap = steps - lastStart;
                    T.check(gap >= GameWorld.METEOR_MIN_GAP - 0.1f && gap <= GameWorld.METEOR_MAX_GAP + 0.1f,
                        "seed " + seed + ": gap " + gap);
                    lastStart = steps;
                }
                if (now) T.check(g.meteor().t < GameWorld.METEOR_SECONDS, "within its 0.7 s");
                was = now;
            }
            T.eq(starts, g.meteorCount(), "one at a time: every launch seen as its own");
            T.check(starts >= 1200 / 60 - 1 && starts <= 1200 / 20, "seed " + seed + ": " + starts + " in 20 min");
        }
    }

    public static void testNoShootingStarDuringTheIntroOrWhileItIsHeld() {
        GameWorld g = world(2);
        g.holdIntro();
        for (int i = 0; i < Math.round(200f / DT); i++) {
            g.step(DT);
            T.check(g.meteor() == null, "none while the intro is held");
        }
        T.eq(0, g.meteorCount(), "none launched");
        GameWorld h = world(2);
        h.restartIntro();
        for (int i = 0; i < Math.round(GameConfig.INTRO_SECONDS / DT) - 2; i++) {
            h.step(DT);
            T.check(h.meteor() == null, "none during the intro");
        }
    }

    public static void testShootingStarCrossesAboutHalfTheWidthDiagonally() {
        GameWorld g = world(1);
        g.skipIntro();
        GameWorld.Meteor m = null;
        float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
        for (int i = 0; i < Math.round(200f / DT) && x1 == 0; i++) {
            g.step(DT);
            m = g.meteor();
            if (m != null) {
                if (x0 == 0 && m.t < 0.05f) { x0 = m.x; y0 = m.y; }
                x1 = m.x; y1 = m.y;
                if (m.t > 0.66f) break;
                x1 = 0;
            }
        }
        T.check(m != null, "launched");
        float run = Math.abs(x1 - x0), fall = y1 - y0;
        T.check(run > 0.35f * 620 && run < 0.62f * 620, "crosses 40-60% of the width: " + run);
        float deg = (float) Math.toDegrees(Math.atan2(fall, run));
        T.check(deg > 18 && deg < 42, "20-40 degrees below horizontal: " + deg);
        T.check(y0 < 0.6f * 722, "starts in the upper 60%");
    }

    public static void testSameSeedSameSkyEvents() {
        GameWorld a = world(7), b = world(7);
        a.skipIntro();
        b.skipIntro();
        for (int i = 0; i < Math.round(300f / DT); i++) {
            a.step(DT);
            b.step(DT);
            T.eq(a.glintCount(), b.glintCount(), "glints " + i);
            GameWorld.Meteor p = a.meteor(), q = b.meteor();
            T.check((p == null) == (q == null), "meteor presence " + i);
            if (p != null) T.check(p.x == q.x && p.y == q.y, "meteor position " + i);
        }
        T.check(a.meteorCount() > 0, "something flew");
    }
}
