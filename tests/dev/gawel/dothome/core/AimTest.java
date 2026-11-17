package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * Tap-to-fire: a tap fires one bolt at once from the next twin gun along the heading (within 3 degrees of it), and
 * the RCS then turns the ship to face the tap point and holds the facing until it settles (or 1.5 s).
 */
public final class AimTest {
    private AimTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);
    private static final float CONE = 3 * DEG, GUN_CONE = 3 * DEG;

    private static final class Count implements GameWorld.Listener {
        int destroyed, hits;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() { hits++; }
    }

    /** 620x722 (k = 1) past the intro, no rocks, the autopilot station-keeping: the ship rests at (310, 390) heading up. */
    private static GameWorld live(long seed, Count c) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        if (c != null) g.setListener(c);
        g.rocks().clear();
        g.skipIntro();
        g.setWanderTargetForTest(g.ship().x, g.ship().y);
        return g;
    }

    private static float bearing(GameWorld.Ship s, float x, float y) {
        return (float) Math.atan2(y - s.y, x - s.x);
    }

    /** |heading error| to (x, y), radians. */
    private static float err(GameWorld.Ship s, float x, float y) {
        return Math.abs(GameWorld.wrapAngle(bearing(s, x, y) - s.angle));
    }

    /** The bolt fired in the last step, or null: a new bolt has flown exactly one step. */
    private static GameWorld.Shot newBolt(GameWorld g) {
        GameWorld.Shot found = null;
        for (GameWorld.Shot b : g.shots()) {
            if (b.t != DT) continue;
            T.check(found == null, "one bolt a step at most");
            found = b;
        }
        return found;
    }

    /**
     * The bolt fired this step left one of the twin guns (px, py hold where it started) within 3 degrees of the
     * heading at the bolt speed, with the muzzle flash at that gun. Returns the gun's side: -1 port, +1 starboard.
     */
    private static int fromAGun(GameWorld g, GameWorld.Shot b) {
        GameWorld.Ship s = g.ship();
        float k = g.config().k, c = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
        float dx = b.px - s.x, dy = b.py - s.y, lx = dx * c + dy * sn, ly = -dx * sn + dy * c; // ship-local
        T.near(ShipShape.GUN_X * k, lx, 1e-3, "spawned at a gun (x)");
        T.near(ShipShape.GUN_Y * k, Math.abs(ly), 1e-3, "spawned at a gun (y)");
        float off = GameWorld.wrapAngle(b.angle - s.angle);
        T.check(Math.abs(off) <= GUN_CONE + 1e-6f, "within 3 degrees of the heading: " + off / DEG);
        T.near(g.config().shotSpeed, Math.hypot(b.vx, b.vy), 1e-2, "bolt speed");
        T.near(b.angle, Math.atan2(b.vy, b.vx), 1e-5, "flies along its angle");
        GameWorld.Flash f = g.flash();
        T.near(g.config().muzzleFlash, f.t, 1e-6, "muzzle flash");
        T.near(lx, f.x, 1e-3, "flash at that gun (x)");
        T.near(ly, f.y, 1e-3, "flash at that gun (y)");
        T.near(off, f.angle, 1e-5, "flash along the bolt");
        return ly > 0 ? 1 : -1;
    }

    /** The shortest distance from the line through (x, y) along angle a to the point (px, py). */
    private static float miss(float x, float y, float a, float px, float py) {
        return (float) Math.abs(Math.cos(a) * (py - y) - Math.sin(a) * (px - x));
    }

    public static void testTapBehindFiresAtOnceAlongTheHeadingThenTurnsWithRcs() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        float a0 = s.angle, tx = 510, ty = 590; // 135 degrees clockwise of the nose
        T.near(135 * DEG, GameWorld.wrapAngle(bearing(s, tx, ty) - s.angle), 0.01, "behind the ship");
        T.eq(true, g.aim(tx, ty), "accepted");
        g.step(DT);
        T.eq(1, g.shots().size(), "exactly one bolt in the same step");
        GameWorld.Shot b = newBolt(g);
        T.check(b != null, "a fresh bolt");
        fromAGun(g, b);
        T.check(Math.abs(GameWorld.wrapAngle(b.angle - a0)) <= GUN_CONE + 1e-3f, "along the heading it had");
        boolean rcs = false, ok = false;
        for (int i = 0; i < Math.round(g.config().faceTimeout / DT); i++) {
            g.step(DT);
            rcs |= g.rcsFirings() > 0;
            T.check(newBolt(g) == null, "no further bolt");
            if (g.facingActive()) T.eq(0f, s.thrust, "the engines stay off while turning");
            ok |= err(s, tx, ty) < 10 * DEG || !g.facingActive();
        }
        T.check(rcs, "the RCS turned it");
        T.check(ok, "within 1.5 s it faces the tap (or the hold ended)");
        T.check(g.shots().size() <= 1, "no delayed shot");
    }

    public static void testTapAheadToesTheBoltInTowardTheTap() {
        GameWorld g = live(1, null);
        T.eq(true, g.aim(320, 120), "accepted"); // 2 degrees right of the nose
        g.step(DT);
        GameWorld.Shot b = newBolt(g);
        T.check(b != null, "fires on the next step");
        int side = fromAGun(g, b);
        T.check(GameWorld.wrapAngle(b.angle - g.ship().angle) * side < 0, "toed in toward the tap's side");
    }

    public static void testTapsAlternateMuzzles() {
        GameWorld g = live(1, null);
        int prev = 0, n = 0;
        float[][] taps = {{310, 100}, {560, 390}, {100, 200}, {200, 600}, {500, 90}};
        for (float[] t : taps) {
            for (int i = 0; i < 10; i++) g.step(DT); // past the cooldown
            g.aim(t[0], t[1]);
            g.step(DT);
            GameWorld.Shot b = newBolt(g);
            T.check(b != null, "bolt for tap " + n);
            int side = fromAGun(g, b);
            T.check(side != prev, "tap " + n + " from the other gun");
            prev = side;
            n++;
        }
    }

    public static void testTapDuringCooldownSetsTheFacingWithoutFiring() {
        GameWorld g = live(1, null);
        g.aim(310, 100);
        g.step(DT);
        T.check(newBolt(g) != null, "first tap fires");
        g.aim(510, 590);
        g.step(DT);
        T.eq(null, newBolt(g), "no bolt during the cooldown");
        T.eq(true, g.facingActive(), "but it is the facing target now");
        T.near(510, g.aimTargetX(), 1e-6, "x");
        T.near(590, g.aimTargetY(), 1e-6, "y");
        for (int i = 0; i < 40; i++) {
            g.step(DT);
            T.eq(null, newBolt(g), "never a delayed shot");
        }
    }

    public static void testNewerTapRetargets() {
        GameWorld g = live(1, null);
        g.aim(110, 250);
        g.step(DT);
        g.aim(510, 590);
        T.near(510, g.aimTargetX(), 1e-6, "x replaced");
        T.near(590, g.aimTargetY(), 1e-6, "y replaced");
        GameWorld.Ship s = g.ship();
        float best = 99;
        for (int i = 0; i < Math.round(1.2f / DT); i++) {
            g.step(DT);
            best = Math.min(best, err(s, 510, 590));
        }
        T.check(best < 10 * DEG, "it turned to the newer tap: " + best / DEG);
    }

    public static void testHoldEndsAndAutopilotResumes() {
        GameWorld g = live(1, null);
        g.setWanderTargetForTest(310, 120); // the autopilot wants to fly up there at once
        g.aim(510, 590);
        int steps = 0;
        while (g.facingActive() && steps < 200) {
            g.step(DT);
            steps++;
            T.eq(0f, g.engineCommand(), "no burn while facing");
        }
        T.check(!g.facingActive(), "the hold ended");
        T.check(steps * DT <= g.config().faceTimeout + 0.05f, "within 1.5 s: " + steps * DT);
        boolean burned = false;
        for (int i = 0; i < Math.round(3f / DT) && !burned; i++) {
            g.step(DT);
            burned = g.engineCommand() > 0;
        }
        T.check(burned, "the autopilot flies again");
    }

    public static void testNoBoltLeavesOffTheHeading() {
        for (long seed = 1; seed <= 3; seed++) {
            Count c = new Count();
            GameWorld g = new GameWorld(new Rng(seed));
            g.resize(620, 722);
            g.setListener(c);
            g.skipIntro();
            g.setAutoFire(seed == 3);
            Rng taps = new Rng(seed + 100);
            int bolts = 0, prevSide = 0;
            for (int i = 0, n = Math.round(30f / DT); i < n; i++) {
                if (i % 23 == 0) g.aim((float) taps.next() * 620, (float) taps.next() * 722);
                int hits = c.hits;
                g.step(DT);
                GameWorld.Shot b = newBolt(g);
                if (b == null) continue;
                bolts++;
                GameWorld.Ship s = g.ship();
                float off = Math.abs(GameWorld.wrapAngle(b.angle - s.angle));
                T.check(off <= GUN_CONE + 1e-6f, "seed " + seed + ": a bolt " + off / DEG + " degrees off the heading");
                int side = g.flash().y > 0 ? 1 : -1;
                T.check(side != prevSide, "seed " + seed + ": the guns take turns");
                prevSide = side;
                if (c.hits != hits) continue; // a knock in the same step moves the ship after the shot
                T.eq(side, fromAGun(g, b), "seed " + seed + ": from the gun that flashed");
            }
            T.check(bolts > 15, "seed " + seed + " fired " + bolts);
        }
    }

    public static void testGunsSitOnTheHullShouldersEitherSideBehindTheNose() {
        T.eq(2, ShipShape.GUNS, "two guns");
        T.check(ShipShape.gunY(0) < 0 && ShipShape.gunY(1) > 0, "port and starboard of the centreline");
        T.check(ShipShape.GUN_X < ShipShape.NOSE_X && ShipShape.GUN_X > 0, "forward of centre, behind the nose");
        float[] d = ShipShape.hullDots();
        for (int i = 0; i < ShipShape.GUNS; i++) {
            boolean onHull = false;
            for (int j = 0; j < d.length && !onHull; j += 4) {
                onHull = Math.abs(d[j] - ShipShape.GUN_X) < 1e-4f && Math.abs(d[j + 1] - ShipShape.gunY(i)) < 1e-4f;
            }
            T.check(onHull, "gun " + i + " is a hull outline corner");
        }
    }

    /** A rock heads straight at the ship while taps keep it aiming: avoidance wins, then aiming carries on. */
    public static void testAvoidanceStillWinsWhileAiming() {
        // rock start and velocity, and the tapped point; the bolts are cleared each step so only flight saves the ship
        float[][] cases = {
            {310, 190, 0, 40, 60, 390},   // from above, the ship facing left
            {560, 390, -45, 0, 310, 100}, // from the right, the ship facing up
            {150, 230, 30, 30, 560, 600}, // diagonally from the upper left, the ship facing down-right
        };
        for (float[] cs : cases) {
            Count c = new Count();
            GameWorld g = live(1, c);
            GameWorld.Rock keep = g.addRock(3, cs[0], cs[1], cs[2], cs[3]);
            boolean evaded = false, burned = false;
            for (int i = 0; i < Math.round(6f / DT); i++) {
                for (int j = g.rocks().size() - 1; j >= 0; j--) if (g.rocks().get(j) != keep) g.rocks().remove(j);
                g.shots().clear();
                g.aim(cs[4], cs[5]);
                g.step(DT);
                if (g.evading()) {
                    evaded = true;
                    burned |= g.engineCommand() > 0;
                } else {
                    T.eq(0f, g.engineCommand(), "engines off while aiming with nothing about to hit");
                }
            }
            String at = "rock from " + cs[0] + "," + cs[1];
            T.check(g.rocks().contains(keep), at + ": the rock is still there");
            T.eq(0, c.hits, at + ": no collision");
            T.check(evaded, at + ": it dodged");
            T.check(burned, at + ": with the engines");
        }
    }

    public static void testAutoFireTurnsToTheNearestRockAndFiresAlongTheHeading() {
        Count c = new Count();
        GameWorld g = live(1, c);
        GameWorld.Ship s = g.ship();
        GameWorld.Rock r = g.addRock(1, s.x + 100, s.y, 0, 0); // 90 degrees right of the nose
        g.setAutoFire(true);
        boolean rcs = false;
        int bolts = 0;
        for (int i = 0; i < Math.round(3f / DT); i++) {
            g.step(DT);
            rcs |= g.rcsFirings() > 0;
            GameWorld.Shot b = newBolt(g);
            if (b != null) {
                bolts++;
                fromAGun(g, b);
            }
        }
        T.check(rcs, "it turned with the RCS");
        T.check(bolts >= 3, "and kept firing on its cadence: " + bolts);
        T.eq(1, c.destroyed, "and hit the rock once facing it");
    }

    public static void testIntroAndReplayClearTheFacing() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        T.eq(false, g.aim(100, 100), "no taps during the intro");
        T.eq(false, g.facingActive(), "nothing set");
        g.skipIntro();
        g.aim(310, 700);
        g.aim(100, 100);
        g.restartIntro();
        T.eq(false, g.facingActive(), "a replay clears the facing");
    }

    public static void testFacingPointFollowsAResize() {
        GameWorld g = live(1, null);
        g.aim(310, 100);
        g.resize(330, 810);
        T.near(310 * 330f / 620f, g.aimTargetX(), 1e-3, "x scaled");
        T.near(100 * 810f / 722f, g.aimTargetY(), 1e-3, "y scaled");
    }

    public static void testBoltsArePooledAndOffScreenOnesDropped() {
        GameWorld g = live(1, null);
        java.util.Set<GameWorld.Shot> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<GameWorld.Shot, Boolean>());
        int fired = 0;
        for (int n = 0; n < 200; n++) {
            g.aim(310, 40);
            for (int i = 0; i < 9; i++) {
                g.rocks().clear(); // nothing to dodge or hit
                g.step(DT);
                if (newBolt(g) != null) fired++;
                for (GameWorld.Shot b : g.shots()) seen.add(b);
            }
        }
        T.eq(200, fired, "a bolt per tap");
        T.check(seen.size() <= GameWorld.MAX_SHOTS, "bolts come from a fixed pool: " + seen.size());
        for (int i = 0; i < 80; i++) g.step(DT);
        T.eq(0, g.shots().size(), "gone past the edge");
        g.aim(310, 100);
        g.step(DT);
        g.restartIntro();
        T.eq(0, g.shots().size(), "a replay clears the bolts");
        T.check(g.flash().t <= 0, "and the flash");
    }

    public static void testBoltHitsKillScoresAndFlares() {
        Count c = new Count();
        GameWorld g = live(1, c);
        GameWorld.Rock r = g.addRock(2, 310, 150, 0, 0);
        g.aim(310, 150);
        int hitStep = -1;
        GameWorld.Shot b = null;
        for (int i = 0; i < 40 && hitStep < 0; i++) {
            g.step(DT);
            if (b == null) b = newBolt(g);
            if (g.laserHits() > 0) hitStep = i;
        }
        T.check(hitStep >= 0, "hit");
        T.eq(1, c.destroyed, "kill path: the listener scores it");
        T.eq(2, g.rocks().size() - (g.rocks().contains(r) ? 1 : 0), "split into two");
        T.eq(1, g.laserHits(), "one hit event");
        T.near(r.y + r.r * 0.92f, g.laserHitY(0), 0.5, "hit on the rock's edge");
        T.near(b.angle, g.laserHitAngle(0), 1e-6, "hit angle is the bolt's");
        T.check(b.hit > 0, "the bolt lingers as a flare");
        g.step(DT);
        T.eq(0, g.laserHits(), "events last one step");
    }

    public static void testSameSeedSameAim() {
        GameWorld a = new GameWorld(new Rng(5)), b = new GameWorld(new Rng(5));
        a.resize(620, 722);
        b.resize(620, 722);
        a.setAutoFire(true);
        b.setAutoFire(true);
        for (int i = 0; i < Math.round(20f / DT); i++) {
            if (i % 41 == 7) {
                float x = 30 + (i * 37) % 560, y = 40 + (i * 53) % 640;
                T.eq(a.aim(x, y), b.aim(x, y), "tap " + i);
            }
            a.step(DT);
            b.step(DT);
            GameWorld.Ship p = a.ship(), q = b.ship();
            T.check(p.x == q.x && p.y == q.y && p.angle == q.angle && p.spin == q.spin, "ship at step " + i);
            T.eq(a.facingActive(), b.facingActive(), "facing " + i);
            T.eq(a.shots().size(), b.shots().size(), "bolts " + i);
            for (int j = 0; j < a.shots().size(); j++) {
                GameWorld.Shot u = a.shots().get(j), v = b.shots().get(j);
                T.check(u.x == v.x && u.y == v.y && u.angle == v.angle, "bolt " + j + " at step " + i);
            }
        }
    }
}
