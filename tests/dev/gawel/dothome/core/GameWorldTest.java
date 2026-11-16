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
        g.skipIntro(); // firing waits for the intro
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
        g.skipIntro();
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
            // pin the wander target on the far side of the rock so only avoidance keeps the ship clear
            g.setWanderTargetForTest(s.x, s.y - 280);
            for (int i = 0; i < 313; i++) {
                for (int j = g.rocks().size() - 1; j >= 0; j--) if (g.rocks().get(j) != keep) g.rocks().remove(j);
                g.setWanderTargetForTest(310, 100);
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

    private static GameWorld pastIntro(Count c) {
        GameWorld g = world(c);
        g.setAutopilot(false);
        for (int i = 0; i < 80; i++) g.step(0.016f);
        return g;
    }

    public static void testShockwaveClearsRocksAndCools() {
        Count c = new Count();
        GameWorld g = pastIntro(c);
        for (int i = 0; i < 5; i++) g.addRock(3, 100 + i * 100, 150 + i * 90, 0, 0);
        T.eq(true, g.shockwave(), "first shockwave");
        T.check(g.wave() != null, "wave active");
        for (int i = 0; i < 75; i++) g.step(0.016f);
        for (GameWorld.Rock r : g.rocks()) {
            boolean onScreen = r.x >= 0 && r.x <= 620 && r.y >= 0 && r.y <= 722;
            T.check(!(r.size == 3 && onScreen), "size-3 rock left at " + r.x + "," + r.y);
        }
        T.check(c.destroyed >= 35, "destroyed " + c.destroyed); // 5 big lineages x (1 + 2 + 4)
        T.check(g.wave() == null, "wave finished");
        T.eq(false, g.shockwave(), "cooldown");
        float t0 = g.time() - 1.2f; // the wave started about 1.2 s ago
        while (g.time() < t0 + 59.5f) g.step(0.05f);
        T.eq(false, g.shockwave(), "still cooling");
        while (g.time() < t0 + 62f) g.step(0.05f);
        T.eq(true, g.shockwave(), "ready again");
    }

    public static void testShockCooldownCountsHiddenTime() {
        GameWorld g = pastIntro(new Count());
        T.eq(true, g.shockwave(), "first");
        g.step(0.016f);
        g.elapseHidden(30f);
        T.eq(false, g.shockwave(), "30 s hidden is not enough");
        g.elapseHidden(31f);
        T.eq(true, g.shockwave(), "ready after 61 s hidden");
    }

    public static void testNoShockwaveDuringIntro() {
        GameWorld g = world(new Count());
        g.setAutopilot(false);
        T.eq(false, g.shockwave(), "t=0");
        for (int i = 0; i < 40; i++) g.step(0.016f);
        T.eq(false, g.shockwave(), "still intro");
        T.check(g.wave() == null, "no wave");
        for (int i = 0; i < 40; i++) g.step(0.016f);
        T.eq(true, g.shockwave(), "after intro");
    }

    public static void testShockwaveKillsRockWhenRingTouchesItsEdge() {
        Count c = new Count();
        GameWorld g = pastIntro(c);
        float d = g.ship().y - 90;
        g.addRock(3, g.ship().x, 90, 0, 0);
        T.eq(true, g.shockwave(), "shockwave");
        // radius = 1190 * t (0.8 s to the far corner): the edge (d - 33.5) is reached at about 0.224 s, the centre (d) at about 0.252 s
        for (int i = 0; i < 15; i++) g.step(0.016f);
        T.check(g.wave().radius < d, "ring has not reached the centre " + g.wave().radius + " < " + d);
        T.check(c.destroyed >= 1, "burst when the ring touches the edge, destroyed " + c.destroyed);
    }

    public static void testWanderTargetsStayInFreeBand() {
        float[][] cases = {{620, 722, 0.266f, 0.693f}, {330, 810, 0.267f, 0.716f}};
        for (float[] cs : cases) {
            GameWorld g = new GameWorld(new Rng(3));
            g.resize(cs[0], cs[1]);
            float lo = cs[2] * cs[1], hi = cs[3] * cs[1];
            float min = 1e9f, max = -1e9f;
            for (int i = 0; i < 3750; i++) {
                g.rocks().clear();
                g.step(0.016f);
                if (g.time() < 1.3f) continue;
                float y = g.wanderTargetY();
                T.check(y >= lo && y <= hi, cs[0] + "x" + cs[1] + " wander y " + y + " outside [" + lo + "," + hi + "]");
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
            T.check(max - min > 0.3f * (hi - lo), "targets vary: " + min + ".." + max);
        }
    }

    public static void testWanderBandFollowsResize() {
        GameWorld g = new GameWorld(new Rng(3));
        g.resize(620, 722);
        g.resize(330, 810);
        float[] b = g.wanderBand();
        T.check(b[0] >= 0.267f * 810 && b[1] <= 0.716f * 810 && b[0] < b[1], "cover band " + b[0] + ".." + b[1]);
    }

    public static void testShockwaveReachesCornersAfterResize() {
        Count c = new Count();
        GameWorld g = pastIntro(c);
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

    public static void testShockwaveIsEventTempo() {
        GameConfig cfg = GameConfig.forSize(620, 722);
        T.near(0.8, cfg.shockDuration, 1e-6, "shockwave duration");
        T.check(cfg.ringLife >= 0.6f && cfg.ringLife <= 0.8f, "hit ring at event tempo " + cfg.ringLife);
        GameWorld g = pastIntro(new Count());
        T.eq(true, g.shockwave(), "shockwave");
        int n = 0;
        while (g.wave() != null && n < 200) { g.step(0.016f); n++; }
        T.near(0.8, n * 0.016, 0.02, "the wave lasts 0.8 s");
    }

    public static void testIntroIsAtMostOnePointTwoSeconds() {
        T.check(GameConfig.forSize(620, 722).introSeconds <= 1.2f, "intro cap");
        T.near(GameConfig.INTRO_SECONDS, GameConfig.forSize(968, 2376).introSeconds, 1e-6, "one intro length");
    }

    public static void testSkipIntroGoesLiveAtOnce() {
        GameWorld g = world(new Count());
        g.skipIntro();
        T.check(g.introTime() >= g.config().introSeconds, "intro over " + g.introTime());
        T.eq(true, g.shockwave(), "shockwave allowed at once");
    }

    public static void testSkipIntroLetsTheShipSteerAtOnce() {
        GameWorld g = world(new Count());
        g.skipIntro();
        float x0 = g.ship().x, y0 = g.ship().y;
        stepClear(g, 0.5f);
        float dx = g.ship().x - x0, dy = g.ship().y - y0;
        T.check(Math.sqrt(dx * dx + dy * dy) > 1, "the autopilot steers during what was the intro");
    }

    public static void testSkipIntroBeforeFirstResize() {
        GameWorld g = new GameWorld(new Rng(1));
        g.skipIntro();
        g.resize(620, 722);
        T.eq(true, g.shockwave(), "no intro after the first resize");
    }

    public static void testSkipIntroKeepsTimeAndIsIdempotent() {
        GameWorld g = pastIntro(new Count());
        float it = g.introTime(), t = g.time();
        g.skipIntro();
        T.near(it, g.introTime(), 1e-6, "an ended intro is left alone");
        T.near(t, g.time(), 1e-6, "world time untouched");
    }

    public static void testRestartIntroReplaysIt() {
        Count c = new Count();
        GameWorld g = world(c);
        g.setAutoFire(true);
        stepClear(g, 3f); // live: the autopilot has the ship moving
        GameWorld.Ship s = g.ship();
        T.check(Math.sqrt(s.vx * s.vx + s.vy * s.vy) > 1, "moving before the replay");
        g.addRock(1, s.x + 120, s.y, 0, 0); // an auto-fire target, out of reach of the ship
        g.shots().add(new GameWorld.Shot());
        g.particles().add(new GameWorld.Particle());
        g.rings().add(new GameWorld.Ring());
        float t = g.time();
        g.restartIntro();
        T.near(0, g.introTime(), 1e-6, "intro clock reset");
        T.near(0, s.vx, 1e-6, "ship vx stopped");
        T.near(0, s.vy, 1e-6, "ship vy stopped");
        T.eq(0, g.shots().size(), "shots cleared");
        T.eq(0, g.particles().size(), "debris cleared");
        T.eq(0, g.rings().size(), "hit rings cleared");
        T.check(g.wave() == null, "no shockwave");
        T.eq(false, g.shockwave(), "no shockwave during the replayed intro");
        T.eq(false, g.fire(s.x, s.y - 100), "no tap fire during the intro");
        float x0 = s.x, y0 = s.y;
        for (int i = 0; i < 62; i++) { // 0.99 s, autopilot and auto-fire on
            g.step(0.016f);
            T.near(x0, s.x, 0.01, "the ship holds still (x) at step " + i);
            T.near(y0, s.y, 0.01, "the ship holds still (y) at step " + i);
            T.eq(0, g.shots().size(), "nothing fires during the intro, step " + i);
        }
        T.eq(0, c.destroyed, "nothing destroyed during the intro");
        T.check(g.time() > t + 0.9f, "world time keeps running");
        for (int i = 0; i < 19; i++) g.step(0.016f);
        T.check(g.introTime() >= g.config().introSeconds, "intro over");
        T.eq(true, g.shockwave(), "live again after 1.2 s");
    }

    public static void testNoFireDuringFirstIntro() {
        GameWorld g = world(new Count());
        T.eq(false, g.fire(100, 100), "tap during the intro");
        g.setAutoFire(true);
        g.addRock(1, g.ship().x + 100, g.ship().y, 0, 0);
        for (int i = 0; i < 70; i++) {
            g.step(0.016f);
            T.eq(0, g.shots().size(), "auto-fire waits for the intro, step " + i);
        }
        for (int i = 0; i < 30; i++) g.step(0.016f);
        T.check(g.shots().size() > 0 || g.particles().size() > 0, "auto-fire after the intro");
    }

    public static void testHoldIntroStaysOnFirstDot() {
        GameWorld g = pastIntro(new Count());
        g.ship().vx = 50;
        g.holdIntro();
        T.near(0, g.ship().vx, 1e-6, "held intro stops the ship too");
        float x0 = g.ship().x;
        g.setAutopilot(true);
        stepClear(g, 3f);
        T.near(0, g.introTime(), 1e-6, "the intro stays at its first frame");
        T.near(x0, g.ship().x, 0.01, "ship still while held");
        T.eq(false, g.shockwave(), "no shockwave while held");
        g.restartIntro();
        stepClear(g, 0.5f);
        T.near(0.5, g.introTime(), 0.02, "playing after the unlock");
        g.holdIntro();
        g.skipIntro();
        stepClear(g, 0.1f);
        T.check(g.introTime() > g.config().introSeconds, "a skip also releases the hold");
    }
}
