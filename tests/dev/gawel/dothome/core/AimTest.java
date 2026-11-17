package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * Tap-to-fire: a tap fires one bolt at once from the next of the four guns along the heading (within 3 degrees of
 * it), and the tap point becomes the waypoint the ship then turns and flies to (ManualModeTest covers the flight).
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

    /** Ship-local (x along the nose, y to starboard, world px) of world point (px, py). */
    static float[] local(GameWorld g, float px, float py) {
        GameWorld.Ship s = g.ship();
        float c = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle), dx = px - s.x, dy = py - s.y;
        return new float[] {dx * c + dy * sn, -dx * sn + dy * c};
    }

    /** How far along gun i's axis from its breech the ship-local point (lx, ly) in world px lies, in ship units. */
    static float along(GameWorld g, int i, float lx, float ly) {
        float k = g.config().k, ga = ShipShape.gunAngle(i);
        return (lx / k - ShipShape.breechX(i)) * (float) Math.cos(ga) + (ly / k - ShipShape.breechY(i)) * (float) Math.sin(ga);
    }

    /**
     * The gun whose barrel tip is at ship-local (lx, ly), world px: on its axis, between its tip fully recoiled and
     * its tip at rest. -1 if none.
     */
    static int gunAt(GameWorld g, float lx, float ly) {
        float k = g.config().k;
        for (int i = 0; i < ShipShape.GUNS; i++) {
            float ga = ShipShape.gunAngle(i), rx = lx / k - ShipShape.breechX(i), ry = ly / k - ShipShape.breechY(i);
            float across = -rx * (float) Math.sin(ga) + ry * (float) Math.cos(ga), a = along(g, i, lx, ly);
            float tip = ShipShape.muzzleReach(i);
            if (Math.abs(across) < 1e-3f && a <= tip + 1e-3f && a >= tip - ShipShape.recoilStroke(i) - 1e-3f) return i;
        }
        return -1;
    }

    /**
     * The bolt fired this step left a gun's barrel tip (px, py hold where it started) within 3 degrees of the
     * heading at the bolt speed, with the muzzle flash at that tip. Returns the gun (ShipShape order).
     */
    static int fromAGun(GameWorld g, GameWorld.Shot b) {
        GameWorld.Ship s = g.ship();
        float[] l = local(g, b.px, b.py);
        float lx = l[0], ly = l[1];
        int gun = gunAt(g, lx, ly);
        T.check(gun >= 0, "spawned at a barrel tip: " + lx + "," + ly);
        float off = GameWorld.wrapAngle(b.angle - s.angle);
        T.check(Math.abs(off) <= GUN_CONE + 1e-6f, "within 3 degrees of the heading: " + off / DEG);
        T.near(g.config().shotSpeed, Math.hypot(b.vx, b.vy), 1e-2, "bolt speed");
        T.near(b.angle, Math.atan2(b.vy, b.vx), 1e-5, "flies along its angle");
        GameWorld.Flash f = g.flash();
        T.near(g.config().muzzleFlash, f.t, 1e-6, "muzzle flash");
        T.near(lx, f.x, 1e-3, "flash at that gun (x)");
        T.near(ly, f.y, 1e-3, "flash at that gun (y)");
        T.near(off, f.angle, 1e-5, "flash along the bolt");
        return gun;
    }

    /** The shortest distance from the line through (x, y) along angle a to the point (px, py). */
    private static float miss(float x, float y, float a, float px, float py) {
        return (float) Math.abs(Math.cos(a) * (py - y) - Math.sin(a) * (px - x));
    }

    public static void testTapBehindFiresAtOnceAlongTheHeadingThenTurnsToFly() {
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
        for (int i = 0; i < Math.round(1.5f / DT); i++) {
            g.step(DT);
            rcs |= g.rcsFirings() > 0;
            T.check(newBolt(g) == null, "no further bolt");
            if (g.engineCommand() > 0) T.check(Math.abs(g.headingError()) < 25 * DEG, "the engines wait for the turn");
            ok |= err(s, tx, ty) < 10 * DEG;
        }
        T.check(rcs, "the RCS turned it");
        T.check(ok, "within 1.5 s it faces the tap to fly there");
        T.check(g.shots().size() <= 1, "no delayed shot");
    }

    public static void testTapAheadToesTheBoltInTowardTheTap() {
        GameWorld g = live(1, null);
        T.eq(true, g.aim(320, 120), "accepted"); // 2 degrees right of the nose
        g.step(DT);
        GameWorld.Shot b = newBolt(g);
        T.check(b != null, "fires on the next step");
        int side = ShipShape.gunSide(fromAGun(g, b));
        T.check(GameWorld.wrapAngle(b.angle - g.ship().angle) * side < 0, "toed in toward the tap's side");
    }

    public static void testTapsCycleTheFourGunsCrossing() {
        GameWorld g = live(1, null);
        float[][] taps = {{310, 100}, {560, 390}, {100, 200}, {200, 600}, {500, 90}, {80, 650}, {300, 300}, {450, 500}};
        int[] order = {ShipShape.NOSE_PORT, ShipShape.WING_STARBOARD, ShipShape.NOSE_STARBOARD, ShipShape.WING_PORT};
        java.util.Set<Long> muzzles = new java.util.HashSet<Long>();
        for (int n = 0; n < taps.length; n++) {
            for (int i = 0; i < 10; i++) g.step(DT); // past the cooldown; each barrel is back at rest
            g.aim(taps[n][0], taps[n][1]);
            g.step(DT);
            GameWorld.Shot b = newBolt(g);
            T.check(b != null, "bolt for tap " + n);
            int gun = fromAGun(g, b);
            T.eq(order[n % 4], gun, "tap " + n + ": nose-left, wing-right, nose-right, wing-left");
            float[] l = local(g, b.px, b.py);
            T.near(ShipShape.muzzleReach(gun), along(g, gun, l[0], l[1]), 1e-3, "at the tip of a barrel at rest");
            muzzles.add(Math.round(l[0] * 10) * 100000L + Math.round(l[1] * 10));
        }
        T.eq(4, muzzles.size(), "four distinct muzzles");
    }

    public static void testRecoilPhaseResetsOnlyForTheGunThatFired() {
        GameWorld g = live(1, null);
        for (int i = 0; i < ShipShape.GUNS; i++) T.check(g.gunAge(i) >= 10f, "idle at first");
        for (int n = 0; n < 6; n++) {
            for (int i = 0; i < 12; i++) g.step(DT);
            float[] before = new float[ShipShape.GUNS];
            for (int i = 0; i < ShipShape.GUNS; i++) before[i] = g.gunAge(i);
            g.aim(310, 60);
            g.step(DT);
            int gun = fromAGun(g, newBolt(g));
            for (int i = 0; i < ShipShape.GUNS; i++) {
                if (i == gun) T.eq(0f, g.gunAge(i), "shot " + n + ": gun " + i + " starts its recoil");
                else T.near(Math.min(10f, before[i] + DT), g.gunAge(i), 1e-5, "shot " + n + ": gun " + i + " untouched");
            }
        }
    }

    public static void testRecoilAndKickTiming() {
        T.eq(0f, ShipShape.recoil(0f), "at rest as it fires");
        T.near(0.75, ShipShape.recoil(0.03f), 1e-5, "three quarters back at 0.03 s");
        T.near(1, ShipShape.recoil(0.06f), 1e-5, "fully back at 0.06 s");
        T.near(Math.pow(1 - 0.06 / 0.22, 3), ShipShape.recoil(0.12f), 1e-5, "springing forward (cubic ease-out)");
        T.check(ShipShape.recoil(0.2f) < 0.06f, "almost home at 0.2 s");
        T.eq(0f, ShipShape.recoil(0.28f), "home at 0.28 s");
        T.eq(0f, ShipShape.recoil(10f), "idle");
        for (int i = 0; i < ShipShape.GUNS; i++) {
            T.near(0.4 * ShipShape.gunLength(i), ShipShape.recoilStroke(i), 1e-5, "a 40% stroke");
        }
        T.near(1, ShipShape.kick(0.06f), 1e-5, "the hull kicks a ship unit back");
        T.check(ShipShape.kick(0.2f) > 0f && ShipShape.kick(0.2f) < 0.2f, "and settles");
        T.eq(0f, ShipShape.kick(0.36f), "settled");
    }

    public static void testTapDuringCooldownSetsTheWaypointWithoutFiring() {
        GameWorld g = live(1, null);
        g.aim(310, 100);
        g.step(DT);
        T.check(newBolt(g) != null, "first tap fires");
        g.aim(510, 590);
        g.step(DT);
        T.eq(null, newBolt(g), "no bolt during the cooldown");
        T.near(510, g.waypointX(), 1e-6, "but it is the waypoint now (x)");
        T.near(590, g.waypointY(), 1e-6, "y");
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
        T.near(510, g.waypointX(), 1e-6, "x replaced");
        T.near(590, g.waypointY(), 1e-6, "y replaced");
        GameWorld.Ship s = g.ship();
        float best = 99;
        for (int i = 0; i < Math.round(1.2f / DT); i++) {
            g.step(DT);
            best = Math.min(best, err(s, 510, 590));
        }
        T.check(best < 10 * DEG, "it turned to the newer tap: " + best / DEG);
    }

    public static void testManualEndsAndAutopilotResumes() {
        GameWorld g = live(1, null);
        g.setWanderTargetForTest(310, 120); // the autopilot wants to fly up there at once
        g.aim(510, 590);
        T.eq(false, g.facingActive(), "a tap is a waypoint, not a facing hold");
        int steps = 0;
        while (g.isManual() && steps < 600) {
            g.step(DT);
            steps++;
        }
        T.check(!g.isManual(), "manual ended");
        T.check(Math.abs(steps * DT - g.config().manualHoldSeconds) <= 0.05f, "after the manual hold: " + steps * DT);
        boolean burned = false;
        for (int i = 0; i < Math.round(8f / DT) && !burned; i++) {
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
            int bolts = 0, prevGun = -1;
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
                int gun = gunAt(g, g.flash().x, g.flash().y);
                T.check(gun >= 0, "seed " + seed + ": the flash sits at a barrel tip");
                if (prevGun >= 0) T.eq((prevGun + 1) % ShipShape.GUNS, gun, "seed " + seed + ": the guns take turns");
                prevGun = gun;
                if (c.hits != hits) continue; // a knock in the same step moves the ship after the shot
                T.eq(gun, fromAGun(g, b), "seed " + seed + ": from the gun that flashed");
            }
            T.check(bolts > 15, "seed " + seed + " fired " + bolts);
        }
    }

    public static void testGunPlacementsFollowTheForkedGunsBoard() {
        T.eq(4, ShipShape.GUNS, "four guns");
        for (int i : new int[] {ShipShape.NOSE_PORT, ShipShape.NOSE_STARBOARD}) { // D: the under-nose twin
            T.near(14.2, ShipShape.breechX(i), 1e-5, "nose breech x");
            T.near(1.25, Math.abs(ShipShape.breechY(i)), 1e-5, "close either side of the centreline");
            T.eq(0f, ShipShape.gunAngle(i), "parallel to the heading");
            T.eq(5, ShipShape.gunDots(i), "five dots");
            T.near(10.1, ShipShape.gunLength(i), 1e-4, "length");
            T.near(4.5, ShipShape.muzzleX(i) - ShipShape.NOSE_X, 0.1, "the muzzle pokes past the nose");
            T.check(ShipShape.gunUnder(i), "under the hull");
        }
        float[] d = ShipShape.hullDots();
        for (int i : new int[] {ShipShape.WING_PORT, ShipShape.WING_STARBOARD}) { // C: the wingtip guns
            T.near(-10.4, ShipShape.breechX(i), 1e-5, "wing breech x");
            T.near(12.5, Math.abs(ShipShape.breechY(i)), 1e-5, "on the wingtip");
            T.near(-1.5 * DEG * ShipShape.gunSide(i), ShipShape.gunAngle(i), 1e-6, "toed in 1.5 degrees");
            T.eq(3, ShipShape.gunDots(i), "three dots");
            T.near(5.5, ShipShape.gunLength(i), 1e-4, "length");
            T.check(!ShipShape.gunUnder(i), "over the hull");
            boolean fin = false;
            for (int j = 0; j < d.length && !fin; j += 4) {
                fin = Math.hypot(d[j] - ShipShape.breechX(i), d[j + 1] - ShipShape.breechY(i)) < 1.5;
            }
            T.check(fin, "gun " + i + " sits on its fin tip");
        }
        int[] sides = {-1, 1, 1, -1};
        for (int i = 0; i < 4; i++) T.eq(sides[i], ShipShape.gunSide(i), "crossing order: left, right, right, left");
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
        T.eq(false, g.isManual(), "and manual mode");
    }

    public static void testWaypointFollowsAResize() {
        GameWorld g = live(1, null);
        g.aim(310, 100);
        g.resize(330, 810);
        T.near(310 * 330f / 620f, g.waypointX(), 1e-3, "x scaled");
        T.near(100 * 810f / 722f, g.waypointY(), 1e-3, "y scaled");
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
