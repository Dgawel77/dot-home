package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** Weather in the game: the precipitation pool, wind on rocks, and keeping the game's Rng untouched. */
public final class PrecipTest {
    private PrecipTest() {}

    private static final long T0 = 10_000_000_000L;
    private static final float W = 1856f, H = 2160f, DT = 1f / 60f;

    private static WeatherFx fx(int code, double precip, double windMph, double dirDeg) {
        return WeatherFx.from(new Weather(40, 50, 30, windMph, dirDeg, code, "x", null, 0, 0, T0, null, precip,
                Double.NaN), T0);
    }

    private static WeatherFx snow(double intensityPrecipMm) { return fx(75, intensityPrecipMm, 0, 0); }

    private static void run(Precip p, WeatherFx f, float gx, float gy, float secs, GameConfig cfg, Rng rng) {
        for (int i = 0, n = Math.round(secs / DT); i < n; i++) p.step(DT, f, gx, gy, W, H, cfg, rng);
    }

    public static void testNoneSpawnsNothing() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip p = new Precip();
        run(p, WeatherFx.NONE, 0, 1, 10, cfg, new Rng(7));
        T.eq(0, p.n, "n");
    }

    public static void testPrecipCap() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip p = new Precip();
        run(p, snow(0), 0, 1, 20, cfg, new Rng(7));
        T.check(p.n <= cfg.precipMax && p.n <= Precip.CAP, "cap " + p.n);
        T.check(p.n >= 0.8 * cfg.precipMax, "fills " + p.n + "/" + cfg.precipMax);
        Precip q = new Precip();
        WeatherFx light = fx(71, 0, 0, 0); // 0.3
        run(q, light, 0, 1, 20, cfg, new Rng(7));
        T.check(q.n <= Math.round(0.3 * cfg.precipMax) + 1, "light " + q.n);
        T.check(q.n > 0, "light spawns");
    }

    private static float meanVx(Precip p) { float s = 0; for (int i = 0; i < p.n; i++) s += p.vx[i]; return s / p.n; }

    private static float meanVy(Precip p) { float s = 0; for (int i = 0; i < p.n; i++) s += p.vy[i]; return s / p.n; }

    public static void testSnowFallsTowardGravity() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip a = new Precip();
        run(a, snow(0), 0, 1, 8, cfg, new Rng(7));
        T.check(a.snow, "snow flag");
        T.check(meanVy(a) > 0, "down " + meanVy(a));
        Precip b = new Precip();
        run(b, snow(0), 1, 0, 8, cfg, new Rng(7));
        T.check(meanVx(b) > 0, "right " + meanVx(b));
        Precip c = new Precip();
        run(c, snow(0), 0.05f, -0.05f, 8, cfg, new Rng(7));
        T.check(meanVy(c) > 0, "weak tilt falls down " + meanVy(c));
    }

    public static void testRainSlantsWithWind() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip p = new Precip();
        WeatherFx east = fx(65, 0, 25, 270); // blows toward +x
        T.check(east.windX > 0.9f, "wind east " + east.windX);
        run(p, east, 0, 1, 3, cfg, new Rng(7));
        T.check(p.n > 0, "rain falls");
        T.check(!p.snow, "rain");
        for (int i = 0; i < p.n; i++) T.check(p.vx[i] > 0, "slant " + p.vx[i]);
    }

    public static void testStopsSpawningAfterClear() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip p = new Precip();
        Rng rng = new Rng(7);
        run(p, snow(0), 0, 1, 5, cfg, rng);
        T.check(p.n > 0, "snowing");
        run(p, WeatherFx.NONE, 0, 1, 0.1f, cfg, rng);
        T.check(p.n > 0, "no instant clear");
        run(p, WeatherFx.NONE, 0, 1, 30, cfg, rng);
        T.eq(0, p.n, "cleared");
    }

    public static void testResizeScalesPrecip() {
        GameConfig cfg = GameConfig.forSize(W, H);
        Precip p = new Precip();
        run(p, snow(0), 0, 1, 3, cfg, new Rng(7));
        float[] before = new float[p.n];
        System.arraycopy(p.x, 0, before, 0, p.n);
        int n = p.n;
        p.scale(0.5f, 1f);
        for (int i = 0; i < n; i++) T.near(before[i] * 0.5f, p.x[i], 1e-4, "x " + i);
    }

    private static GameWorld world(long seed, int w, int h) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(w, h);
        g.skipIntro();
        return g;
    }

    public static void testWeatherDoesNotTouchGameRng() {
        GameWorld a = world(3, 1856, 2160), b = world(3, 1856, 2160);
        b.setWeather(snow(0));
        for (int i = 0; i < 600; i++) { a.step(DT); b.step(DT); }
        T.check(b.precip().n > 0, "snow fell");
        T.eq(a.rocks().size(), b.rocks().size(), "rock count");
        for (int i = 0; i < a.rocks().size(); i++) {
            T.near(a.rocks().get(i).x, b.rocks().get(i).x, 0, "x " + i);
            T.near(a.rocks().get(i).y, b.rocks().get(i).y, 0, "y " + i);
        }
    }

    public static void testWindLeansRocks() {
        GameWorld a = world(5, 4000, 4000), b = world(5, 4000, 4000);
        b.setWeather(fx(0, 0, 25, 270)); // east wind, windX = 1, clear sky
        T.near(1.0, b.weather().windX, 1e-4, "windX");
        int n0 = a.rocks().size();
        for (int i = 0; i < 600; i++) { a.step(DT); b.step(DT); }
        float expect = b.config().windRockBias * 10f;
        float sum = 0;
        int used = 0;
        for (int i = 0; i < n0; i++) {
            float d = b.rocks().get(i).x - a.rocks().get(i).x;
            if (Math.abs(d - expect) < 0.5f * expect) { sum += d; used++; } // skip a rock that wrapped or was knocked
        }
        T.check(used >= n0 / 2, "rocks compared " + used + "/" + n0);
        T.near(expect, sum / used, 0.15 * expect, "mean lean");
    }
}
