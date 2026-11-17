package dev.gawel.dothome.core;

import dev.gawel.dothome.test.MapStore;
import dev.gawel.dothome.test.T;
import java.util.TimeZone;

/**
 * The spin barrage (a shake): 1.6 s on the spot spinning up to 3 rev/s and back, the guns taking turns every 0.045 s
 * along the heading while it spins faster than 1 rev/s, then the autopilot again; a 30 s cooldown.
 */
public final class BarrageTest {
    private BarrageTest() {}

    private static final float DT = 0.016f;
    private static final float REV = (float) (2 * Math.PI);

    private static final class Count implements GameWorld.Listener {
        int destroyed, hits;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() { hits++; }
    }

    /** A 620x722 (k = 1) world past its intro, no rocks; the ship at rest heading up. */
    private static GameWorld live(long seed, GameWorld.Listener c) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        if (c != null) g.setListener(c);
        g.rocks().clear();
        g.skipIntro();
        return g;
    }

    private static float speed(GameWorld.Ship s) { return (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy); }

    /** The first seed whose first barrage spins dir (+1 clockwise, -1 counter-clockwise). */
    private static long seedFor(int dir) {
        for (long seed = 1; seed < 64; seed++) {
            GameWorld g = live(seed, null);
            g.barrage();
            if (g.barrageDirection() == dir) return seed;
        }
        throw new AssertionError("no seed spins " + dir);
    }

    public static void testShakeStartsABarrageOnlyWhenLiveAndCool() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        g.rocks().clear();
        T.eq(false, g.barrage(), "t=0 (intro)");
        for (int i = 0; i < 40; i++) g.step(DT);
        T.eq(false, g.barrage(), "still the intro");
        T.check(!g.isBarrage(), "no barrage in the intro");
        for (int i = 0; i < 40; i++) g.step(DT);
        T.eq(true, g.barrage(), "after the intro");
        T.check(g.isBarrage(), "running");
        T.eq(false, g.barrage(), "not while one runs");
        for (int i = 0; i < Math.round(2f / DT); i++) g.step(DT);
        T.check(!g.isBarrage(), "over");
        T.eq(false, g.barrage(), "cooling down");

        GameWorld h = live(2, null);
        h.holdIntro();
        T.eq(false, h.barrage(), "not while the intro is held");
        h.restartIntro();
        T.eq(false, h.barrage(), "not during the replayed intro");
        h.skipIntro();
        T.eq(true, h.barrage(), "live again");
        h.restartIntro();
        T.check(!h.isBarrage(), "an intro replay ends the barrage");
        T.eq(0f, h.ship().spin, "and stops the spin");
    }

    public static void testCooldownIsThirtySeconds() {
        T.near(30, GameConfig.forSize(620, 722).barrageCooldown, 1e-6, "cooldown");
        GameWorld g = live(1, null);
        g.setAutopilot(false);
        T.eq(true, g.barrage(), "first");
        float t0 = g.time();
        while (g.time() < t0 + 29.8f) {
            g.rocks().clear();
            g.step(0.05f);
        }
        T.eq(false, g.barrage(), "still cooling at 29.8 s");
        while (g.time() < t0 + 30.1f) g.step(0.05f);
        T.eq(true, g.barrage(), "ready again after 30 s");

        GameWorld h = live(1, null);
        T.eq(true, h.barrage(), "first");
        h.step(DT);
        h.elapseHidden(15f);
        T.eq(false, h.barrage(), "15 s hidden is not enough");
        T.check(!h.isBarrage(), "a barrage hidden past its end is over");
        h.elapseHidden(15.1f);
        T.eq(true, h.barrage(), "hidden time counts toward the cooldown");
    }

    public static void testSpinReachesThreeRevsPerSecondAndReturnsToRest() {
        for (int dir = -1; dir <= 1; dir += 2) {
            GameWorld g = live(seedFor(dir), null);
            GameWorld.Ship s = g.ship();
            T.eq(true, g.barrage(), "barrage");
            T.eq(dir, g.barrageDirection(), "direction");
            float top = 0, prev = 0;
            int steps = 0;
            while (g.isBarrage() && steps < 200) {
                g.step(DT);
                steps++;
                float t = steps * DT, spin = s.spin * dir;
                T.check(spin >= -1e-6f, "spins one way: " + s.spin + " at " + t);
                if (t < 0.29f) T.check(spin >= prev, "spins up at " + t);
                if (t > 1.21f) T.check(spin <= prev + 1e-4f, "spins down at " + t);
                if (t > 0.31f && t < 1.19f) T.near(3 * REV, spin, 1e-3, "holds 3 rev/s at " + t);
                top = Math.max(top, spin);
                prev = spin;
                if (t > 1.58f && g.isBarrage()) T.check(spin < 0.05f, "near 0 by 1.6 s: " + spin + " at " + t);
            }
            T.near(3 * REV, top, 0.3 * REV, "top spin");
            T.check(steps >= 99 && steps <= 101, "lasts 1.6 s: " + steps * DT);
            T.eq(0f, s.spin, "at rest after the barrage");
        }
    }

    public static void testRcsFiresHardOnBothRampsFromThePortsForTheSpin() {
        for (int dir = -1; dir <= 1; dir += 2) {
            GameWorld g = live(seedFor(dir), null);
            g.barrage();
            int up = 0, down = 0;
            for (int i = 1; g.isBarrage(); i++) {
                g.step(DT);
                float t = i * DT;
                int nose = 0, tail = 0;
                for (int j = 0; j < g.rcsFirings(); j++) {
                    T.eq(1f, g.rcsStrength(j), "full strength at " + t);
                    if (g.rcsPort(j) == ShipShape.RCS_NOSE) nose = g.rcsSide(j);
                    else tail = g.rcsSide(j);
                }
                if (t < 0.29f) {
                    T.eq(2, g.rcsFirings(), "spin-up fires at " + t);
                    // a clockwise turn fires the nose jet to port and the tail jet to starboard
                    T.eq(-dir, nose, "spin-up nose side");
                    T.eq(dir, tail, "spin-up tail side");
                    up++;
                } else if (t > 0.31f && t < 1.19f) {
                    T.eq(0, g.rcsFirings(), "coasting at 3 rev/s at " + t);
                } else if (t > 1.21f && t < 1.59f) {
                    T.eq(2, g.rcsFirings(), "spin-down fires at " + t);
                    T.eq(dir, nose, "spin-down nose side");
                    T.eq(-dir, tail, "spin-down tail side");
                    down++;
                }
            }
            T.check(up >= 17 && down >= 23, "puffs on both ramps: " + up + ", " + down);
        }
    }

    public static void testShipSpinsInPlaceWhileThePilotWaits() {
        GameWorld g = live(3, null);
        GameWorld.Ship s = g.ship();
        g.step(DT);
        s.vx = 120;
        s.vy = -60;
        float v0 = speed(s), x0 = s.x, y0 = s.y;
        g.setWanderTargetForTest(100, 300);
        T.eq(true, g.barrage(), "barrage");
        T.eq(false, g.aim(300, 100), "taps wait for the barrage");
        while (g.isBarrage()) {
            g.step(DT);
            T.eq(0f, g.engineCommand(), "no main engines");
            T.check(!g.isManual(), "no manual mode");
            T.eq(100f, g.wanderTargetX(), "the autopilot waits (x)");
            T.eq(300f, g.wanderTargetY(), "the autopilot waits (y)");
        }
        T.check(speed(s) < v0 * (float) Math.exp(-2 * 1.55), "drift dies under 2/s drag: " + speed(s) + " from " + v0);
        float moved = (float) Math.hypot(s.x - x0, s.y - y0);
        T.check(moved < 0.55f * v0, "nearly on the spot: moved " + moved);
        float a = s.angle, vx = s.vx, vy = s.vy;
        g.step(DT);
        T.check(g.wanderTargetX() != 100f || g.wanderTargetY() != 300f, "a fresh wander target");
        T.check(Math.abs(GameWorld.wrapAngle(s.angle - a)) < 0.01f, "it keeps the final heading at first");
        float limit = g.config().thrust * DT * 1.01f;
        for (int i = 0; i < 60; i++) {
            T.check(Math.hypot(s.vx - vx, s.vy - vy) <= limit, "a smooth takeover at step " + i);
            T.check(Math.abs(s.spin) <= g.config().maxSpin * 1.01f, "the autopilot's turn rate: " + s.spin);
            vx = s.vx;
            vy = s.vy;
            g.rocks().clear();
            g.step(DT);
        }
    }

    public static void testManualModeWaitsOutTheBarrageThenFliesOn() {
        GameWorld g = live(3, null);
        g.setWanderTargetForTest(100, 300);
        g.aim(500, 600);
        g.step(DT);
        T.check(g.isManual(), "manual before the shake");
        T.eq(true, g.barrage(), "barrage");
        T.check(!g.isManual(), "suspended while it spins");
        while (g.isBarrage()) g.step(DT);
        T.check(g.isManual(), "within its 4 s: manual again");
        T.near(500, g.waypointX(), 1e-6, "the same waypoint");
        float d0 = (float) Math.hypot(g.ship().x - 500, g.ship().y - 600), best = d0;
        for (int i = 0; i < Math.round(2.3f / DT) && g.isManual(); i++) {
            g.step(DT);
            g.rocks().clear();
            best = Math.min(best, (float) Math.hypot(g.ship().x - 500, g.ship().y - 600));
        }
        T.check(best < d0 - 50, "flying on to it: " + best + " from " + d0);

        GameWorld h = live(3, null);
        h.setWanderTargetForTest(100, 300);
        h.ship().vx = h.ship().vy = 0f;
        h.aim(h.ship().x, h.ship().y); // already there: it arrives at once, so the 4 s from the tap decide
        for (int i = 0; i < Math.round(3f / DT); i++) h.step(DT);
        T.eq(true, h.barrage(), "a shake 3 s after the tap");
        while (h.isBarrage()) h.step(DT);
        T.check(!h.isManual(), "the 4 s ran out during the spin: the autopilot");
        h.step(DT);
        T.check(h.wanderTargetX() != 100f || h.wanderTargetY() != 300f, "with a fresh wander target");
    }

    public static void testBoltsFireAboveOneRevFromAllFourGunsAlongTheHeading() {
        GameWorld g = live(4, null);
        GameWorld.Ship s = g.ship();
        GameConfig cfg = g.config();
        g.barrage();
        int last = 0, prevGun = -1, used = 0, quadrants = 0;
        float[] age = new float[ShipShape.GUNS];
        while (g.isBarrage()) {
            for (int i = 0; i < ShipShape.GUNS; i++) age[i] = g.gunAge(i);
            g.step(DT);
            int n = g.barrageBolts();
            if (n == last) continue;
            T.eq(last + 1, n, "one bolt per step at 60 fps");
            last = n;
            T.check(Math.abs(s.spin) > REV, "fires only above 1 rev/s: " + s.spin);
            GameWorld.Shot b = g.shots().get(g.shots().size() - 1);
            int gun = AimTest.fromAGun(g, b); // a barrel tip, within 3 degrees of the heading, the flash there
            T.near(ShipShape.gunAngle(gun), GameWorld.wrapAngle(b.angle - s.angle), 1e-5, "along that barrel");
            T.near(0, GameWorld.wrapAngle((float) Math.atan2(b.vy, b.vx) - b.angle), 1e-4, "velocity along it");
            // the tip where the barrel is now: still sliding home from its shot four bolts ago
            float[] l = AimTest.local(g, b.px, b.py);
            float tip = ShipShape.muzzleReach(gun) - ShipShape.recoil(Math.min(10f, age[gun] + DT)) * ShipShape.recoilStroke(gun);
            T.near(tip, AimTest.along(g, gun, l[0], l[1]), 1e-3, "the barrel's current tip");
            if (prevGun >= 0) T.eq((prevGun + 1) % ShipShape.GUNS, gun, "the guns take turns");
            prevGun = gun;
            used |= 1 << gun;
            T.eq(0f, g.gunAge(gun), "its recoil starts over");
            quadrants |= 1 << ((int) Math.floor((b.angle + Math.PI) / (Math.PI / 2)) & 3);
        }
        T.check(last >= 25 && last <= 32, "bolts " + last);
        T.eq(15, used, "all four guns fire");
        T.eq(15, quadrants, "a spiral: bolts leave in every direction");
    }

    public static void testBoltCountHoldsAtOtherFrameRates() {
        float[] dts = {1f / 120, 1f / 60, DT, 1f / 30, 0.05f};
        for (float dt : dts) {
            GameWorld g = live(5, null);
            g.barrage();
            int prev = 0;
            for (int i = 0; g.isBarrage() && i < 1000; i++) {
                g.step(dt);
                if (g.barrageBolts() > prev) T.check(Math.abs(g.ship().spin) > REV, "above 1 rev/s at dt " + dt);
                prev = g.barrageBolts();
                T.check(g.shots().size() <= GameWorld.MAX_SHOTS, "pool");
            }
            T.check(prev >= 25 && prev <= 32, "bolts " + prev + " at dt " + dt);
        }
        T.check(GameWorld.MAX_SHOTS >= 48, "the bolt pool holds 48");
    }

    public static void testBarrageBoltsKillRocksAndScore() {
        final ScoreBook book = new ScoreBook(new MapStore(), () -> 1_763_000_000_000L, TimeZone.getTimeZone("UTC"));
        final Count c = new Count();
        GameWorld g = live(6, new GameWorld.Listener() { // wired as GameHub does
            public void onRockDestroyed() { c.destroyed++; book.addRock(); }
            public void onShipHit() { c.hits++; }
        });
        GameWorld.Ship s = g.ship();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            g.addRock(3, s.x + 130 * (float) Math.cos(a), s.y + 130 * (float) Math.sin(a), 0, 0);
        }
        T.eq(true, g.barrage(), "barrage");
        int hits = 0;
        while (g.isBarrage()) {
            g.step(DT);
            hits += g.laserHits();
        }
        for (int i = 0; i < 20; i++) g.step(DT); // the last bolts land
        T.check(c.destroyed >= 3, "rocks destroyed " + c.destroyed);
        T.eq(c.destroyed, book.today(), "each kill scores");
        T.check(hits >= c.destroyed, "laser hits for the sparks " + hits);
        T.check(g.particles().size() > 0, "debris");
        int fragments = 0;
        for (GameWorld.Rock r : g.rocks()) if (r.size == 2) fragments++;
        T.check(fragments >= 2, "big rocks split as usual: " + fragments);
        T.eq(0, c.hits, "the ship stayed clear");
    }

    public static void testSameSeedSameBarrage() {
        GameWorld a = live(9, null), b = live(9, null);
        for (int i = 0; i < 6; i++) {
            a.addRock(3, 80 + i * 90, 150 + (i % 3) * 200, 6, -4);
            b.addRock(3, 80 + i * 90, 150 + (i % 3) * 200, 6, -4);
        }
        for (int i = 0; i < Math.round(8f / DT); i++) {
            if (i == 60) {
                T.eq(true, a.barrage(), "a");
                T.eq(true, b.barrage(), "b");
                T.eq(a.barrageDirection(), b.barrageDirection(), "same direction");
            }
            a.step(DT);
            b.step(DT);
            GameWorld.Ship p = a.ship(), q = b.ship();
            T.check(p.x == q.x && p.y == q.y && p.angle == q.angle && p.spin == q.spin, "ship at step " + i);
            T.eq(a.barrageBolts(), b.barrageBolts(), "bolts " + i);
            T.eq(a.shots().size(), b.shots().size(), "shots " + i);
            for (int j = 0; j < a.shots().size(); j++) {
                T.check(a.shots().get(j).x == b.shots().get(j).x && a.shots().get(j).y == b.shots().get(j).y, "shot " + j);
            }
            T.eq(a.rocks().size(), b.rocks().size(), "rocks " + i);
            T.eq(a.rcsFirings(), b.rcsFirings(), "rcs " + i);
        }
        boolean cw = false, ccw = false;
        for (long seed = 1; seed <= 16; seed++) {
            GameWorld g = live(seed, null);
            g.barrage();
            if (g.barrageDirection() > 0) cw = true; else ccw = true;
        }
        T.check(cw && ccw, "the direction is picked at random per seed");
    }
}
