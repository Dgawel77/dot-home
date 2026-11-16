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
        g.setTilt(1, 1);
        for (int i = 0; i < 625; i++) g.step(0.016f);
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

    public static void testIntroIgnoresTilt() {
        GameWorld g = world(new Count());
        g.setTilt(1, 0);
        for (int i = 0; i < 62; i++) g.step(0.016f);
        T.near(310, g.ship().x, 0.01, "ship x");
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
}
