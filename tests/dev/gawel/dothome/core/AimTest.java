package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * Rotate-to-aim (design/mockups/ShipV2.dc.html, aimMode "rotate"): a tap queues a target, the RCS turns the ship to
 * face it and one bolt leaves the nose along the heading once that is within 5 degrees of the bearing.
 */
public final class AimTest {
    private AimTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);
    private static final float CONE = 5 * DEG;

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

    /** The bolt left the nose (px, py hold where it started) along the heading at the bolt speed. */
    private static void fromTheNose(GameWorld g, GameWorld.Shot b) {
        GameWorld.Ship s = g.ship();
        float k = g.config().k, c = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
        float nx = ShipShape.NOSE_X * k, ny = ShipShape.NOSE_Y * k;
        T.near(s.x + nx * c - ny * sn, b.px, 1e-3, "spawned at the nose (x)");
        T.near(s.y + nx * sn + ny * c, b.py, 1e-3, "spawned at the nose (y)");
        T.near(0, GameWorld.wrapAngle(b.angle - s.angle), 1e-6, "along the heading");
        T.near(g.config().shotSpeed, Math.hypot(b.vx, b.vy), 1e-2, "bolt speed");
        T.near(b.angle, Math.atan2(b.vy, b.vx), 1e-5, "flies along its angle");
    }

    public static void testTapBehindTurnsWithRcsThenFiresFromTheNose() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        float tx = 510, ty = 590; // 135 degrees clockwise of the nose
        T.near(135 * DEG, GameWorld.wrapAngle(bearing(s, tx, ty) - s.angle), 0.01, "behind the ship");
        T.eq(true, g.aim(tx, ty), "queued");
        T.eq(0, g.shots().size(), "nothing fires at the tap");
        boolean rcs = false;
        GameWorld.Shot b = null;
        int n = 0;
        while (b == null && n < Math.round(g.config().aimTimeout / DT)) {
            g.step(DT);
            n++;
            rcs |= g.rcsFirings() > 0;
            b = newBolt(g);
            float e = err(s, tx, ty);
            if (b == null) T.check(e > CONE, "lined up at " + e / DEG + " degrees but did not fire");
            else T.check(e <= CONE, "fired at a heading error of " + e / DEG + " degrees");
            T.eq(0f, s.thrust, "the engines stay off while aiming");
        }
        T.check(rcs, "the RCS turned it");
        T.check(b != null, "fired within the timeout");
        T.check(n * DT > 0.5f, "it turned first: fired after " + n * DT + " s");
        T.eq(1, g.shots().size(), "one bolt");
        fromTheNose(g, b);
        float k = g.config().k;
        GameWorld.Flash f = g.flash();
        T.near(g.config().muzzleFlash, f.t, 1e-6, "muzzle flash");
        T.near(ShipShape.NOSE_X * k, f.x, 1e-4, "flash at the nose (x)");
        T.near(ShipShape.NOSE_Y * k, f.y, 1e-4, "flash at the nose (y)");
        T.near(0, f.angle, 1e-6, "flash along the heading");
        T.eq(0, g.aimCount(), "the target is done");
        for (int i = 0; i < 6; i++) g.step(DT);
        T.check(f.t <= 0, "the flash is gone after about 0.08 s: " + f.t);
    }

    public static void testTapAheadFiresAtOnce() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        float a0 = s.angle;
        T.eq(true, g.aim(320, 120), "queued"); // 2 degrees right of the nose
        g.step(DT);
        GameWorld.Shot b = newBolt(g);
        T.check(b != null, "already lined up: fires on the next step");
        fromTheNose(g, b);
        T.check(Math.abs(GameWorld.wrapAngle(s.angle - a0)) < 0.01f, "barely turned");
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
            int bolts = 0;
            for (int i = 0, n = Math.round(30f / DT); i < n; i++) {
                if (i % 23 == 0) g.aim((float) taps.next() * 620, (float) taps.next() * 722);
                boolean aimed = g.aimCount() > 0;
                float tx = aimed ? g.aimTargetX() : 0, ty = aimed ? g.aimTargetY() : 0;
                int hits = c.hits;
                g.step(DT);
                GameWorld.Shot b = newBolt(g);
                if (b == null) continue;
                bolts++;
                GameWorld.Ship s = g.ship();
                float off = Math.abs(GameWorld.wrapAngle(b.angle - s.angle));
                T.check(off <= CONE, "seed " + seed + ": a bolt " + off / DEG + " degrees off the heading");
                if (c.hits != hits) continue; // a knock in the same step moves the ship after the shot
                if (aimed) T.check(err(s, tx, ty) <= CONE + 1e-5f, "seed " + seed + ": fired off target " + err(s, tx, ty) / DEG);
                fromTheNose(g, b);
            }
            T.check(bolts > 20, "seed " + seed + " fired " + bolts);
        }
    }

    public static void testTapsQueueUpToThreeAndTheNewestIsReplaced() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        float[][] taps = {{110, 250}, {510, 250}, {120, 560}, {560, 390}};
        for (float[] t : taps) T.eq(true, g.aim(t[0], t[1]), "accepted");
        T.eq(GameWorld.AIM_QUEUE, g.aimCount(), "three at most");
        // the fourth tap replaced the newest waiting one (the third)
        float[][] order = {taps[0], taps[1], taps[3]};
        int shots = 0;
        float lastShot = -1;
        for (int i = 0; i < Math.round(4f / DT) && shots < 3; i++) {
            g.step(DT);
            GameWorld.Shot b = newBolt(g);
            if (b == null) continue;
            T.check(err(s, order[shots][0], order[shots][1]) <= CONE, "shot " + shots + " at its own target, in order");
            T.check(g.time() - lastShot >= g.config().fireCooldown - 1e-4f || lastShot < 0, "the cooldown holds");
            fromTheNose(g, b);
            lastShot = g.time();
            shots++;
            T.eq(3 - shots, g.aimCount(), "then the next target");
        }
        T.eq(3, shots, "one bolt per target");
        T.eq(0, g.aimCount(), "the queue is empty");
    }

    public static void testRepeatedTapsOnOnePointFireAtTheCooldown() {
        GameWorld g = live(1, null);
        for (int i = 0; i < 3; i++) g.aim(310, 100);
        int[] at = new int[3];
        int shots = 0;
        for (int i = 0; i < 40 && shots < 3; i++) {
            g.step(DT);
            if (newBolt(g) != null) at[shots++] = i;
        }
        T.eq(3, shots, "three bolts");
        T.eq(0, at[0], "the first at once");
        T.check(at[1] - at[0] >= 7 && at[1] - at[0] <= 9, "then at the 0.12 s cooldown: " + (at[1] - at[0]) + " steps");
        T.check(at[2] - at[1] >= 7 && at[2] - at[1] <= 9, "and again: " + (at[2] - at[1]) + " steps");
    }

    public static void testAutopilotResumesWhenTheQueueEmpties() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        g.setWanderTargetForTest(310, 120); // the autopilot wants to fly up there at once
        g.aim(510, 590);
        int steps = 0;
        while (g.aimCount() > 0 && steps < 100) {
            g.step(DT);
            steps++;
            T.eq(0f, g.engineCommand(), "no burn while aiming");
            T.eq(0f, s.thrust, "engines off while aiming");
        }
        T.eq(0, g.aimCount(), "shot");
        boolean burned = false;
        for (int i = 0; i < Math.round(3f / DT) && !burned; i++) {
            g.step(DT);
            burned = g.engineCommand() > 0;
        }
        T.check(burned, "the autopilot flies again");
    }

    public static void testTimeoutDropsATargetItCannotLineUp() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        s.spin = (float) -Math.PI; // spinning counter-clockwise at the turn-rate cap
        float a = s.angle + 120 * DEG; // and the target 120 degrees clockwise
        float tx = s.x + 200 * (float) Math.cos(a), ty = s.y + 200 * (float) Math.sin(a);
        g.aim(tx, ty);
        float nx = s.x + 200 * (float) Math.cos(a + 30 * DEG), ny = s.y + 200 * (float) Math.sin(a + 30 * DEG);
        g.aim(nx, ny); // the next target, 30 degrees further on
        int steps = Math.round(g.config().aimTimeout / DT);
        for (int i = 0; i < steps - 1; i++) {
            g.step(DT);
            T.eq(0, g.shots().size(), "not lined up: no bolt at step " + i);
        }
        T.eq(2, g.aimCount(), "still trying just before the timeout");
        T.check(err(s, tx, ty) > CONE, "not lined up yet");
        g.step(DT);
        g.step(DT);
        T.eq(1, g.aimCount(), "dropped after 1.2 s");
        T.eq(0, g.shots().size(), "without a bolt");
        GameWorld.Shot b = null;
        for (int i = 0; i < Math.round(1.2f / DT) && b == null; i++) {
            g.step(DT);
            b = newBolt(g);
        }
        T.check(b != null, "the next target gets its own 1.2 s and is shot");
        T.check(err(s, nx, ny) <= CONE, "at the next target");
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

    public static void testAutoFireTurnsToTheNearestRockAndFiresFromTheNose() {
        Count c = new Count();
        GameWorld g = live(1, c);
        GameWorld.Ship s = g.ship();
        GameWorld.Rock r = g.addRock(1, s.x + 100, s.y, 0, 0); // 90 degrees right of the nose
        g.setAutoFire(true);
        boolean rcs = false;
        GameWorld.Shot b = null;
        for (int i = 0; i < Math.round(1.2f / DT) && b == null; i++) {
            g.step(DT);
            rcs |= g.rcsFirings() > 0;
            b = newBolt(g);
            if (b == null) T.check(err(s, r.x, r.y) > CONE, "not lined up yet");
        }
        T.check(rcs, "it turned with the RCS");
        T.check(b != null, "and fired");
        T.check(err(s, r.x, r.y) <= CONE, "at the rock");
        fromTheNose(g, b);
        for (int i = 0; i < 20; i++) g.step(DT);
        T.eq(1, c.destroyed, "and hit it");
    }

    public static void testIntroAndReplayClearTheQueue() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        T.eq(false, g.aim(100, 100), "no taps during the intro");
        T.eq(0, g.aimCount(), "nothing queued");
        g.skipIntro();
        g.aim(310, 700);
        g.aim(100, 100);
        g.restartIntro();
        T.eq(0, g.aimCount(), "a replay clears the queue");
    }

    public static void testQueuedPointsFollowAResize() {
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
            for (int i = 0; i < 8; i++) {
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
            T.eq(a.aimCount(), b.aimCount(), "queue " + i);
            T.eq(a.shots().size(), b.shots().size(), "bolts " + i);
            for (int j = 0; j < a.shots().size(); j++) {
                GameWorld.Shot u = a.shots().get(j), v = b.shots().get(j);
                T.check(u.x == v.x && u.y == v.y && u.angle == v.angle, "bolt " + j + " at step " + i);
            }
        }
    }
}
