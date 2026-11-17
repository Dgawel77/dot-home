package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** Ship v2 flight: Newtonian motion, the PD autopilot and its thruster events (design/mockups/ShipV2.dc.html). */
public final class ShipFlightTest {
    private ShipFlightTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);

    private static final class Count implements GameWorld.Listener {
        int destroyed, hits;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() { hits++; }
    }

    /** A 620x722 (k = 1) world past its intro, no rocks; the ship at (310, 390) heading up, at rest. */
    private static GameWorld live(long seed, Count c) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        if (c != null) g.setListener(c);
        g.rocks().clear();
        g.skipIntro();
        return g;
    }

    private static float speed(GameWorld.Ship s) { return (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy); }

    private static float dist(GameWorld.Ship s, float x, float y) {
        return (float) Math.hypot(s.x - x, s.y - y);
    }

    public static void testCruiseVisitsWanderTargetsWithinLimits() {
        for (long seed = 1; seed <= 3; seed++) {
            GameWorld g = live(seed, null);
            GameConfig cfg = g.config();
            float tx = Float.NaN, ty = Float.NaN, best = Float.MAX_VALUE, path = 0;
            int targets = 0, reached = 0;
            for (int i = 0, n = Math.round(30f / DT); i < n; i++) {
                g.rocks().clear();
                g.step(DT);
                GameWorld.Ship s = g.ship();
                if (g.wanderTargetX() != tx || g.wanderTargetY() != ty) {
                    if (targets > 0 && best < 40f) reached++;
                    targets++;
                    tx = g.wanderTargetX();
                    ty = g.wanderTargetY();
                    best = Float.MAX_VALUE;
                }
                best = Math.min(best, dist(s, tx, ty));
                path += speed(s) * DT;
                T.check(s.x >= cfg.edgeMargin - 0.01f && s.x <= 620 - cfg.edgeMargin + 0.01f, "x in bounds " + s.x);
                T.check(s.y >= cfg.edgeMargin - 0.01f && s.y <= 722 - cfg.edgeMargin + 0.01f, "y in bounds " + s.y);
                T.check(speed(s) <= cfg.maxSpeed * 1.05f, "seed " + seed + " speed " + speed(s) + " at " + g.time());
                T.check(Math.abs(s.spin) <= cfg.maxSpin * 1.01f, "seed " + seed + " spin " + s.spin + " at " + g.time());
            }
            if (best < 40f) reached++;
            T.check(targets >= 4, "seed " + seed + " wander targets " + targets);
            T.check(reached >= targets - 1, "seed " + seed + " reached " + reached + " of " + targets);
            T.check(path > 1000f, "seed " + seed + " not stuck: flew " + path + " px");
        }
    }

    public static void testFlipAndBurnToBrake() {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        float sp0 = g.config().maxSpeed;
        s.y = 560;
        s.vy = -sp0; // flying nose first (heading up) at top speed
        g.setWanderTargetForTest(s.x, 640); // behind the ship
        float a0 = s.angle;
        float turned = 0;
        boolean braked = false;
        for (int i = 0; i < Math.round(4f / DT) && !braked; i++) {
            g.step(DT);
            turned = Math.max(turned, Math.abs(GameWorld.wrapAngle(s.angle - a0)));
            braked = speed(s) < 0.9f * sp0;
        }
        T.check(braked, "it brakes");
        T.check(turned > 150 * DEG, "flipped " + turned / DEG + " degrees before the speed dropped");
    }

    public static void testMainEngineOnlyNearTheWantedHeading() {
        for (long seed = 1; seed <= 3; seed++) {
            GameWorld g = live(seed, null);
            float prev = 0;
            int burns = 0;
            for (int i = 0, n = Math.round(30f / DT); i < n; i++) {
                if (i % 600 == 0) g.addRock(3, 80 + 70 * (i / 600), 120, 12, 18); // some avoidance too
                g.step(DT);
                float err = Math.abs(g.headingError()), lvl = g.nozzleThrust(0);
                if (g.engineCommand() > 0) {
                    burns++;
                    T.check(err < 25 * DEG, "engine commanded at a heading error of " + err / DEG + " degrees");
                }
                if (lvl > prev + 1e-6f) T.check(err < 25 * DEG, "thrust rising at a heading error of " + err / DEG);
                T.eq(lvl, g.nozzleThrust(1), "both nozzles");
                T.eq(g.ship().thrust, lvl, "nozzle level is the engine level");
                prev = lvl;
            }
            T.check(burns > 100, "the engines do fire: " + burns);
        }
    }

    /** The first RCS firing while the ship (heading up, at rest) turns toward a target to the right or the left. */
    private static int[] firstRcs(float targetX, float[] spinOut) {
        GameWorld g = live(1, null);
        GameWorld.Ship s = g.ship();
        g.setWanderTargetForTest(targetX, s.y);
        for (int i = 0; i < 120; i++) {
            g.step(DT);
            if (g.rcsFirings() > 0) {
                T.eq(2, g.rcsFirings(), "a nose and a tail jet");
                T.check(g.rcsStrength(0) > 0 && g.rcsStrength(0) <= 1f, "strength " + g.rcsStrength(0));
                T.eq(g.rcsStrength(0), g.rcsStrength(1), "a couple: equal strength");
                int[] sides = new int[2];
                for (int j = 0; j < 2; j++) sides[g.rcsPort(j)] = g.rcsSide(j);
                spinOut[0] = s.spin;
                return sides;
            }
        }
        throw new AssertionError("no RCS firing");
    }

    public static void testRcsSidesFlipWithTurnDirection() {
        float[] spin = new float[1];
        int[] cw = firstRcs(560, spin); // heading up, target to the right: a clockwise turn on screen
        T.check(spin[0] > 0, "clockwise spin " + spin[0]);
        int[] ccw = firstRcs(60, spin);
        T.check(spin[0] < 0, "counter-clockwise spin " + spin[0]);
        T.eq(-1, cw[ShipShape.RCS_NOSE], "clockwise: the nose jet blows to port");
        T.eq(1, cw[ShipShape.RCS_TAIL], "clockwise: the tail jet blows to starboard");
        T.eq(-cw[ShipShape.RCS_NOSE], ccw[ShipShape.RCS_NOSE], "nose side flips");
        T.eq(-cw[ShipShape.RCS_TAIL], ccw[ShipShape.RCS_TAIL], "tail side flips");
    }

    public static void testRockBumpSpinsShipAndRcsCancelsIt() {
        Count c = new Count();
        GameWorld g = live(1, c);
        GameWorld.Ship s = g.ship();
        g.setWanderTargetForTest(s.x, s.y); // station-keeping: nothing to correct
        for (int i = 0; i < 30; i++) g.step(DT);
        T.near(0, s.spin, 1e-6, "still before the bump");
        g.addRock(2, s.x + 22, s.y - 6, 0, 0); // off-centre, to the front right
        g.step(DT);
        T.eq(1, c.hits, "bumped");
        float spin0 = s.spin;
        T.check(Math.abs(spin0) >= 1.25f, "the bump spins the ship: " + spin0);
        // keep only the spin: the knock's push is covered by GameWorldTest.testShipHitKnocksBackNoGameOver
        g.rocks().clear();
        s.vx = s.vy = 0;
        g.setWanderTargetForTest(s.x, s.y);
        boolean counter = false;
        int settled = -1;
        for (int i = 0; i < Math.round(3f / DT); i++) {
            g.step(DT);
            if (!counter && g.rcsFirings() > 0) {
                counter = true;
                int noseSide = g.rcsPort(0) == ShipShape.RCS_NOSE ? g.rcsSide(0) : g.rcsSide(1);
                // a nose jet to port turns clockwise; to oppose a clockwise (positive) spin it blows to starboard
                T.eq(spin0 > 0 ? 1 : -1, noseSide, "the first RCS firing counters the spin");
            }
            if (Math.abs(s.spin) < 0.1f) { // the PD deadband lets up to 0.08 rad/s drift
                if (settled < 0) settled = i;
            } else {
                settled = -1;
            }
        }
        T.check(counter, "the RCS fired");
        T.check(settled >= 0 && settled * DT < 2f, "spin cancelled within 2 s, settled at step " + settled);
        T.check(Math.abs(s.spin) < 0.1f, "and stays cancelled: " + s.spin);
    }

    public static void testNoThrustersDuringTheIntro() {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        g.rocks().clear();
        for (int i = 0; i < 70; i++) {
            g.step(DT);
            T.eq(0f, g.nozzleThrust(0), "engines idle in the intro");
            T.eq(0, g.rcsFirings(), "no RCS in the intro");
        }
        for (int i = 0; i < 300; i++) g.step(DT);
        g.restartIntro();
        T.eq(0f, g.ship().spin, "replay stops the spin");
        T.eq(0f, g.nozzleThrust(0), "replay shuts the engines");
        T.eq(0, g.rcsFirings(), "replay clears RCS");
    }

    public static void testSameSeedSameFlight() {
        GameWorld a = new GameWorld(new Rng(7)), b = new GameWorld(new Rng(7));
        a.resize(620, 722);
        b.resize(620, 722);
        for (int i = 0; i < Math.round(25f / DT); i++) {
            if (i % 97 == 50) {
                float x = 40 + (i * 37) % 540, y = 60 + (i * 53) % 600;
                T.eq(a.aim(x, y), b.aim(x, y), "tap " + i);
            }
            if (i == 900) a.shockwave();
            if (i == 900) b.shockwave();
            a.step(DT);
            b.step(DT);
            GameWorld.Ship p = a.ship(), q = b.ship();
            T.check(p.x == q.x && p.y == q.y && p.angle == q.angle && p.spin == q.spin && p.thrust == q.thrust,
                "ship at step " + i);
            T.eq(a.rcsFirings(), b.rcsFirings(), "rcs " + i);
            T.eq(a.rocks().size(), b.rocks().size(), "rocks " + i);
            T.eq(a.shots().size(), b.shots().size(), "shots " + i);
        }
        for (int i = 0; i < a.rocks().size(); i++) {
            T.check(a.rocks().get(i).x == b.rocks().get(i).x && a.rocks().get(i).y == b.rocks().get(i).y, "rock " + i);
        }
    }

    public static void testHullIsForkedFive() {
        float[] d = ShipShape.hullDots();
        T.eq(0, d.length % 4, "quads");
        int n = d.length / 4, cockpit = 0;
        float nose = -1e9f, tail = 1e9f, wing = 0;
        for (int i = 0; i < n; i++) {
            float x = d[4 * i], y = d[4 * i + 1], dia = d[4 * i + 2], lvl = d[4 * i + 3];
            if (lvl < 1f) cockpit++;
            nose = Math.max(nose, x);
            tail = Math.min(tail, x);
            wing = Math.max(wing, Math.abs(y));
            T.check(dia >= 1.4f - 1e-4f && dia <= 3.4f + 1e-4f, "dot " + dia);
            boolean mirrored = false;
            for (int j = 0; j < n && !mirrored; j++) {
                mirrored = Math.abs(d[4 * j] - x) < 1e-4f && Math.abs(d[4 * j + 1] + y) < 1e-4f;
            }
            T.check(mirrored, "symmetric about the axis at " + x + "," + y);
        }
        T.eq(2, cockpit, "two cockpit dots in L65");
        T.near(19, nose, 1e-4, "nose");
        T.near(-13, tail, 1e-4, "tail");
        T.near(12.5, wing, 1e-4, "wingtip fins");
        T.eq(6, ShipShape.nozzleMarks().length / 4, "two nozzle bars of three dots");
    }
}
