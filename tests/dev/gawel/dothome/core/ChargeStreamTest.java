package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** The charging stream: dots from the screen edges homing into the ship, and the low-battery dim. */
public final class ChargeStreamTest {
    private ChargeStreamTest() {}

    private static final float W = 1856f, H = 2160f, DT = 1f / 60f;

    private static void run(ChargeStream s, boolean on, float secs, GameConfig cfg, Rng rng) {
        for (int i = 0, n = Math.round(secs / DT); i < n; i++) s.step(DT, on, W / 2, H / 2, W, H, cfg, rng);
    }

    public static void testRateAbout15PerSecond() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        run(s, true, 10f, cfg, new Rng(3));
        T.check(s.spawned >= 140 && s.spawned <= 160, "spawned " + s.spawned);
    }

    public static void testAllAbsorbedAfterOff() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        Rng rng = new Rng(4);
        run(s, true, 5f, cfg, rng);
        run(s, false, 6f, cfg, rng);
        T.eq(0, s.n, "n");
        T.check(s.absorbed == s.spawned, "absorbed " + s.absorbed + " spawned " + s.spawned);
    }

    public static void testOffSpawnsNothing() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        run(s, false, 10f, cfg, new Rng(5));
        T.check(s.spawned == 0, "spawned " + s.spawned);
    }

    public static void testStreamCap() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        Rng rng = new Rng(6);
        float sx = 100f, dir = 1f, v = cfg.maxSpeed * GameConfig.SPEED_CAP;
        for (int i = 0, n = Math.round(30f / DT); i < n; i++) {
            sx += dir * v * DT;
            if (sx > W - 100f) dir = -1f; else if (sx < 100f) dir = 1f;
            s.step(DT, true, sx, H / 2, W, H, cfg, rng);
            T.check(s.n <= ChargeStream.CAP, "n " + s.n);
        }
    }

    public static void testDotsSpawnAtEdges() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        Rng rng = new Rng(7);
        int seen = 0;
        for (int i = 0, n = Math.round(10f / DT); i < n; i++) {
            long sp = s.spawned, ab = s.absorbed;
            s.step(DT, true, W / 2, H / 2, W, H, cfg, rng);
            if (s.spawned > sp && s.absorbed == ab) {
                float x = s.x[s.n - 1], y = s.y[s.n - 1];
                boolean edge = x <= 1f || x >= W - 1f || y <= 1f || y >= H - 1f;
                T.check(edge, "dot at " + x + "," + y);
                seen++;
            }
        }
        T.check(seen > 100, "checked " + seen);
    }

    public static void testGlowRises() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        Rng rng = new Rng(8);
        // spawn exactly one dot, then let it fly in
        int guard = 0;
        while (s.spawned == 0 && guard++ < 600) s.step(DT, true, W / 2, H / 2, W, H, cfg, rng);
        T.eq(1, s.n, "one dot");
        float first = s.glow[0], prev = first;
        T.check(first < 0.05f, "starts dark " + first);
        float last = first;
        for (int i = 0; i < 20 && s.n == 1; i++) {
            s.step(DT, false, W / 2, H / 2, W, H, cfg, rng);
            if (s.n == 1) { T.check(s.glow[0] >= prev - 1e-4f, "glow fell"); prev = s.glow[0]; last = prev; }
        }
        for (int i = 0; i < 600 && s.n == 1; i++) {
            s.step(DT, false, W / 2, H / 2, W, H, cfg, rng);
            if (s.n == 1) { T.check(s.glow[0] >= prev - 1e-4f, "glow fell"); prev = s.glow[0]; last = prev; }
        }
        T.check(last > 0.8f, "ends bright " + last);
    }

    public static void testResizeScalesStream() {
        GameConfig cfg = GameConfig.forSize(W, H);
        ChargeStream s = new ChargeStream();
        Rng rng = new Rng(9);
        run(s, true, 1f, cfg, rng);
        T.check(s.n > 0, "has dots");
        float[] x = new float[s.n], y = new float[s.n];
        for (int i = 0; i < s.n; i++) { x[i] = s.x[i]; y[i] = s.y[i]; }
        int n = s.n;
        s.scale(2f, 1f);
        for (int i = 0; i < n; i++) {
            T.near(x[i] * 2f, s.x[i], 1e-3, "x");
            T.near(y[i], s.y[i], 1e-3, "y");
        }
    }

    public static void testShipDim() {
        GameWorld w = new GameWorld(new Rng(1));
        w.resize(W, H);
        w.setPower(false, 14);
        T.check(w.shipDim(), "14% discharging dims");
        w.setPower(false, 15);
        T.check(!w.shipDim(), "15% does not");
        w.setPower(true, 5);
        T.check(!w.shipDim(), "charging does not");
        w.setPower(false, -1);
        T.check(!w.shipDim(), "unknown does not");
    }
}
