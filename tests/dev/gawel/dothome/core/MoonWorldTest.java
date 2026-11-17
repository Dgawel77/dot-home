package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** The moon's day fade, its horizon fade and the crater glint's cadence. */
public final class MoonWorldTest {
    private MoonWorldTest() {}

    private static final float DT = 0.016f;

    public static void testDayFactorFallsBackToSunAltitude() {
        T.near(0, MoonSky.dayFactor(Float.NaN, -10), 1e-6, "sun well down: night");
        T.near(1, MoonSky.dayFactor(Float.NaN, 10), 1e-6, "sun well up: day");
        T.near(0.3, MoonSky.dayFactor(0.3f, 50), 1e-6, "known dayness wins");
    }

    public static void testAlphaFadesOverLast2Degrees() {
        T.near(0, MoonSky.alpha(-0.1, 0), 1e-6, "below the horizon");
        T.near(0.5, MoonSky.alpha(1, 0), 1e-6, "1 degree up");
        T.near(1, MoonSky.alpha(30, 0), 1e-6, "high at night");
        T.near(0.5, MoonSky.alpha(30, 1), 1e-6, "high by day");
    }

    private static GameWorld live(long seed) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        g.skipIntro();
        return g;
    }

    public static void testCraterGlintCadence() {
        GameWorld g = live(5);
        float t = 0f, lastStart = 0f;
        int prev = -1, starts = 0;
        for (int i = 0, n = Math.round(120f / DT); i < n; i++) {
            g.step(DT);
            t += DT;
            int c = g.moonGlintCrater();
            T.check(g.moonGlint() >= 0f && g.moonGlint() <= GameWorld.GLINT_SECONDS + 1e-6f, "glint " + g.moonGlint());
            T.check((c >= 0) == (g.moonGlint() > 0f), "crater set only while glinting at " + t);
            if (c >= 0 && prev < 0) {
                T.check(c <= 2, "crater " + c);
                float gap = t - lastStart;
                T.check(gap >= 8f - 2 * DT && gap <= 15f + 2 * DT, "glint gap " + gap + " at " + t);
                lastStart = t;
                starts++;
            }
            prev = c;
        }
        T.check(starts >= 7 && starts <= 15, "glints in 120 s: " + starts);

        GameWorld a = live(9), b = live(9);
        for (int i = 0, n = Math.round(120f / DT); i < n; i++) {
            a.step(DT);
            b.step(DT);
        }
        T.check(a.ship().x == b.ship().x && a.ship().y == b.ship().y, "same ship");
        T.eq(a.rocks().size(), b.rocks().size(), "same rock count");
        for (int i = 0; i < a.rocks().size(); i++) {
            T.check(a.rocks().get(i).x == b.rocks().get(i).x && a.rocks().get(i).y == b.rocks().get(i).y, "rock " + i);
        }
    }
}
