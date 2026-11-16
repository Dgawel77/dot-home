package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class GameWorldTest {
    private static final class Count implements GameWorld.Listener {
        int destroyed, hits;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() { hits++; }
    }

    private static GameWorld world(Count c) {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        g.setListener(c);
        g.rocks().clear();
        return g;
    }

    private static int count(GameWorld g, int size) {
        int n = 0;
        for (GameWorld.Rock r : g.rocks()) if (r.size == size) n++;
        return n;
    }

    private static void fireUntilKill(GameWorld g, Count c, float x, float y) {
        g.fire(x, y);
        for (int i = 0; i < 125 && c.destroyed < 1; i++) g.step(0.016f);
    }

    public static void testShotDestroysBigRockAndSplits() {
        Count c = new Count();
        GameWorld g = world(c);
        g.addRock(3, 310, 200, 0, 0);
        fireUntilKill(g, c, 310, 200);
        T.eq(1, c.destroyed, "destroyed");
        T.eq(2, count(g, 2), "size-2 rocks");
    }

    public static void testSmallRockLeavesNoFragments() {
        Count c = new Count();
        GameWorld g = world(c);
        g.addRock(1, 310, 200, 0, 0);
        fireUntilKill(g, c, 310, 200);
        T.eq(1, c.destroyed, "destroyed");
        T.eq(0, count(g, 1) + count(g, 2), "no fragments");
        T.check(g.particles().size() > 0, "burst particles");
    }

    public static void testShipHitKnocksBackNoGameOver() {
        Count c = new Count();
        GameWorld g = world(c);
        for (int i = 0; i < 82; i++) g.step(0.016f);
        GameWorld.Ship s = g.ship();
        GameWorld.Rock r = g.addRock(3, s.x - 10, s.y, 0, 0);
        g.step(0.016f);
        T.eq(1, g.rings().size(), "rings");
        T.eq(1, c.hits, "hits");
        T.check(s.vx * (s.x - r.x) + s.vy * (s.y - r.y) > 0, "knocked away from rock");
        float t = g.time();
        for (int i = 0; i < 312; i++) g.step(0.016f);
        T.check(g.time() > t + 4.9f, "world keeps running");
    }

    public static void testHitCooldownOneRing() {
        Count c = new Count();
        GameWorld g = world(c);
        for (int i = 0; i < 82; i++) g.step(0.016f);
        GameWorld.Ship s = g.ship();
        for (int i = 0; i < 3; i++) {
            g.addRock(3, s.x - 10, s.y, 0, 0);
            g.step(0.016f);
            g.rocks().clear();
        }
        T.eq(1, g.rings().size(), "rings");
        T.eq(1, c.hits, "hits");
    }

    public static void testShipStaysInBounds() {
        GameWorld g = world(new Count());
        GameConfig cfg = GameConfig.forSize(620, 722);
        for (int i = 0; i < 625; i++) { g.rocks().clear(); g.step(0.016f); }
        T.check(g.ship().x >= cfg.edgeMargin - 0.01 && g.ship().y >= cfg.edgeMargin - 0.01, "low bound");
        T.check(g.ship().x <= 620 - cfg.edgeMargin + 0.01, "x bound " + g.ship().x);
        T.check(g.ship().y <= 722 - cfg.edgeMargin + 0.01, "y bound " + g.ship().y);
    }

    public static void testFireCooldown() {
        GameWorld g = world(new Count());
        T.eq(true, g.fire(100, 100), "first");
        T.eq(false, g.fire(100, 100), "second");
    }

    public static void testParticlesCapped() {
        Count c = new Count();
        GameWorld g = world(c);
        for (int i = 0; i < 1995; i++) {
            GameWorld.Particle p = new GameWorld.Particle();
            p.life = p.life0 = 100f;
            g.particles().add(p);
        }
        g.addRock(1, 310, 200, 0, 0);
        fireUntilKill(g, c, 310, 200);
        T.eq(1, c.destroyed, "kill still happened");
        T.check(g.particles().size() <= 2000, "cap " + g.particles().size());
        T.check(g.particles().size() > 1995, "burst added some particles " + g.particles().size());
    }

    public static void testZeroResizeIgnored() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        g.resize(0, 722);
        g.resize(330, 810);
        GameConfig cfg = GameConfig.forSize(330, 810);
        GameWorld.Ship s = g.ship();
        T.check(!Float.isNaN(s.x) && !Float.isInfinite(s.x) && !Float.isNaN(s.y) && !Float.isInfinite(s.y), "ship finite");
        T.check(s.x >= cfg.edgeMargin && s.x <= 330 - cfg.edgeMargin, "ship x " + s.x);
        T.check(s.y >= cfg.edgeMargin && s.y <= 810 - cfg.edgeMargin, "ship y " + s.y);
        for (GameWorld.Rock r : g.rocks()) {
            T.check(!Float.isNaN(r.x) && !Float.isInfinite(r.x) && !Float.isNaN(r.y) && !Float.isInfinite(r.y), "rock finite");
        }
    }

    public static void testSpawnRestoresMass() {
        GameWorld g = world(new Count());
        for (int i = 0; i < 2500; i++) g.step(0.016f);
        float mass = 0;
        for (GameWorld.Rock r : g.rocks()) mass += r.size == 3 ? 1f : r.size == 2 ? 0.45f : 0.2f;
        T.check(mass >= 0.85 * GameConfig.forSize(620, 722).rocksTarget - 1, "mass " + mass);
    }

    public static void testIntroKeepsShipStill() {
        GameWorld g = world(new Count());
        for (int i = 0; i < 62; i++) g.step(0.016f);
        T.near(310, g.ship().x, 0.01, "ship x");
        T.near(722 * 0.54, g.ship().y, 0.01, "ship y");
    }

    public static void testDtClamped() {
        GameWorld a = world(new Count()), b = world(new Count());
        a.step(1.0f);
        b.step(0.05f);
        T.eq(b.ship().x, a.ship().x, "x");
        T.eq(b.ship().y, a.ship().y, "y");
        T.eq(b.time(), a.time(), "time");
    }

    public static void testResizeRefitsWorld() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        int n = g.rocks().size();
        g.ship().x = 600;
        g.ship().y = 400;
        g.resize(330, 810);
        GameConfig cfg = GameConfig.forSize(330, 810);
        T.check(g.ship().x <= 330 - cfg.edgeMargin, "ship x " + g.ship().x);
        T.eq(n, g.rocks().size(), "rock count");
        for (GameWorld.Rock r : g.rocks()) {
            T.check(r.x >= -2 * r.r && r.x <= 330 + 2 * r.r, "rock x " + r.x);
        }
    }

    public static void testAutoFireHitsNearby() {
        Count c = new Count();
        GameWorld g = world(c);
        g.setAutoFire(true);
        g.addRock(1, g.ship().x + 100, g.ship().y, 0, 0);
        for (int i = 0; i < 188; i++) g.step(0.016f);
        T.check(c.destroyed >= 1, "destroyed " + c.destroyed);
    }

    private static GameWorld sized(float w, float h) {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(w, h);
        return g;
    }

    private static void within10(int expected, int actual, String msg) {
        T.check(Math.abs(actual - expected) <= expected * 0.10, msg + ": expected ~" + expected + " but was " + actual);
    }

    public static void testStarCountIsAtMockupDensityOnFold6Surfaces() {
        within10(Math.round(620f * 722f / 2300f), sized(1856, 2160).stars().size(), "main stars");
        within10(Math.round(330f * 810f / 2300f), sized(968, 2376).stars().size(), "cover stars");
    }

    public static void testRockTargetMatchesMockupAspect() {
        T.eq(GameConfig.forSize(330, 810).rocksTarget, GameConfig.forSize(968, 2376).rocksTarget, "cover rocks");
        T.check(GameConfig.forSize(968, 2376).rocksTarget <= 5, "cover rocks not 8");
        T.check(GameConfig.forSize(1856, 2160).rocksTarget >= 6, "main rocks near mockup density");
        T.eq(GameConfig.forSize(968, 2376).rocksTarget, sized(968, 2376).rocks().size(), "spawned rocks");
    }

    public static void testResizeRetargetsStars() {
        GameWorld g = sized(968, 2376);
        int cover = g.stars().size();
        g.resize(1856, 2160);
        T.eq(GameConfig.starCount(1856, 2160), g.stars().size(), "grown to main target");
        T.check(g.stars().size() > cover, "more stars on main");
        g.resize(968, 2376);
        T.eq(cover, g.stars().size(), "trimmed back");
    }

    private static void stepClear(GameWorld g, float secs) {
        for (int i = 0, n = Math.round(secs / 0.016f); i < n; i++) { g.rocks().clear(); g.step(0.016f); }
    }

    public static void testAutopilotKeepsMoving() {
        GameWorld g = world(new Count());
        GameConfig cfg = GameConfig.forSize(620, 722);
        stepClear(g, 1.3f);
        float sum = 0;
        int n = 1250;
        for (int i = 0; i < n; i++) {
            g.rocks().clear();
            g.step(0.016f);
            GameWorld.Ship s = g.ship();
            sum += (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy);
            T.check(s.x >= cfg.edgeMargin - 0.01 && s.x <= 620 - cfg.edgeMargin + 0.01, "x in margin " + s.x);
            T.check(s.y >= cfg.edgeMargin - 0.01 && s.y <= 722 - cfg.edgeMargin + 0.01, "y in margin " + s.y);
        }
        T.check(sum / n > 0.1f * cfg.maxSpeed, "mean speed " + sum / n);
    }

    public static void testAutopilotDodgesIncomingRock() {
        int clean = 0;
        for (int seed = 1; seed <= 5; seed++) {
            Count c = new Count();
            GameWorld g = new GameWorld(new Rng(seed));
            g.resize(620, 722);
            g.setListener(c);
            g.rocks().clear();
            stepClear(g, 1.3f);
            GameWorld.Ship s = g.ship();
            GameWorld.Rock keep = g.addRock(3, s.x, s.y - 200, 0, 40);
            for (int i = 0; i < 313; i++) {
                for (int j = g.rocks().size() - 1; j >= 0; j--) if (g.rocks().get(j) != keep) g.rocks().remove(j);
                g.step(0.016f);
            }
            if (c.hits == 0) clean++;
        }
        T.check(clean >= 4, "clean dodges " + clean + " of 5");
    }

    public static void testAutopilotNotStuckInCorner() {
        GameWorld g = world(new Count());
        g.ship().x = 30;
        g.ship().y = 30;
        stepClear(g, 10f);
        float dx = g.ship().x - 30, dy = g.ship().y - 30;
        T.check(Math.sqrt(dx * dx + dy * dy) > 150, "distance from corner " + Math.sqrt(dx * dx + dy * dy));
    }

    public static void testTiltDoesNotMoveShip() {
        GameWorld g = world(new Count());
        g.setAutopilot(false);
        g.setTilt(1, 1);
        float x0 = g.ship().x, y0 = g.ship().y;
        stepClear(g, 3f);
        T.near(x0, g.ship().x, 0.01, "x");
        T.near(y0, g.ship().y, 0.01, "y");
    }

    private static float meanVyAfterKill(float ty) {
        Count c = new Count();
        GameWorld g = world(c);
        g.setAutopilot(false);
        g.setTilt(0, ty);
        g.addRock(1, 310, 200, 0, 0);
        fireUntilKill(g, c, 310, 200);
        T.eq(1, c.destroyed, "destroyed");
        for (int i = 0; i < 31; i++) g.step(0.016f);
        float sum = 0;
        for (GameWorld.Particle p : g.particles()) sum += p.vy;
        T.check(g.particles().size() > 0, "particles exist");
        return sum / g.particles().size();
    }

    public static void testGravityPullsDebris() {
        float with = meanVyAfterKill(1f), without = meanVyAfterKill(0f);
        T.check(with > without, "vy with gravity " + with + " vs " + without);
    }

    public static void testShockwaveClearsRocksAndCools() {
        Count c = new Count();
        GameWorld g = world(c);
        g.setAutopilot(false);
        for (int i = 0; i < 5; i++) g.addRock(3, 100 + i * 100, 150 + i * 90, 0, 0);
        T.eq(true, g.shockwave(), "first shockwave");
        T.check(g.wave() != null, "wave active");
        for (int i = 0; i < 75; i++) g.step(0.016f);
        for (GameWorld.Rock r : g.rocks()) {
            boolean onScreen = r.x >= 0 && r.x <= 620 && r.y >= 0 && r.y <= 722;
            T.check(!(r.size == 3 && onScreen), "size-3 rock left at " + r.x + "," + r.y);
        }
        T.check(c.destroyed >= 5, "destroyed " + c.destroyed);
        T.check(g.wave() == null, "wave finished");
        T.eq(false, g.shockwave(), "cooldown");
        float t0 = 0f; // the wave started at game time 0
        while (g.time() < t0 + 59.5f) g.step(0.05f);
        T.eq(false, g.shockwave(), "still cooling");
        while (g.time() < t0 + 62f) g.step(0.05f);
        T.eq(true, g.shockwave(), "ready again");
    }

    public static void testShockwaveReachesCornersAfterResize() {
        Count c = new Count();
        GameWorld g = world(c);
        g.setAutopilot(false);
        for (int i = 0; i < 5; i++) g.addRock(3, 100 + i * 100, 150 + i * 90, 0, 0);
        T.eq(true, g.shockwave(), "shockwave");
        g.resize(330, 810);
        for (int i = 0; i < 75; i++) g.step(0.016f);
        for (GameWorld.Rock r : g.rocks()) {
            boolean onScreen = r.x >= 0 && r.x <= 330 && r.y >= 0 && r.y <= 810;
            T.check(!(r.size == 3 && onScreen), "size-3 rock left at " + r.x + "," + r.y);
        }
    }

    public static void testStarDepthRange() {
        GameWorld g = sized(1856, 2160);
        T.check(g.stars().size() > 0, "stars");
        for (GameWorld.Star s : g.stars()) T.check(s.depth >= 0.2f && s.depth <= 1.0f, "depth " + s.depth);
    }
}
