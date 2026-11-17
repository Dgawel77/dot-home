package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * A tap is a waypoint: until 4 s after the last tap the flight computer flies the ship there (Newtonian, flip and
 * burn, still dodging rocks) and holds station once it arrives; then the autopilot wanders again.
 */
public final class ManualModeTest {
    private ManualModeTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);

    private static final class Count implements GameWorld.Listener {
        int hits;
        public void onRockDestroyed() {}
        public void onShipHit() { hits++; }
    }

    /** 620x722 (k = 1) past the intro, no rocks. */
    private static GameWorld live(long seed) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        g.rocks().clear();
        g.skipIntro();
        return g;
    }

    /** The ship at rest at (x, y), heading up. */
    private static void place(GameWorld g, float x, float y) {
        GameWorld.Ship s = g.ship();
        s.x = x;
        s.y = y;
        s.vx = s.vy = s.spin = 0f;
        s.angle = -90 * DEG;
    }

    private static float dist(GameWorld g, float x, float y) { return (float) Math.hypot(g.ship().x - x, g.ship().y - y); }

    private static float speed(GameWorld.Ship s) { return (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy); }

    /** Ship start and tap point, 400 px apart: to the right, straight ahead, behind, and on a diagonal. */
    private static final float[][] FLIGHTS = {{110, 560, 510, 560}, {310, 600, 310, 200}, {310, 200, 310, 600}, {500, 200, 180, 440}};

    public static void testFliesToTheTapAndSettles() {
        for (float[] f : FLIGHTS) {
            GameWorld g = live(1);
            place(g, f[0], f[1]);
            float tx = f[2], ty = f[3], slow = 0.2f * g.config().maxSpeed;
            T.near(400, dist(g, tx, ty), 8, "400 px away");
            g.aim(tx, ty);
            float mark = dist(g, tx, ty), reached = -1;
            for (int i = 1; i <= Math.round(6f / DT); i++) {
                if (i == Math.round(3f / DT)) g.aim(tx, ty); // the user taps it again on the way: manual for 4 s more
                g.step(DT);
                g.shots().clear();
                float d = dist(g, tx, ty);
                if (i % Math.round(0.5f / DT) == 0 && i * DT >= 1f && mark > 60) { // after the first turn
                    T.check(d < mark, f[0] + "," + f[1] + ": closing in at " + i * DT + " s: " + d + " from " + mark);
                    mark = d;
                }
                if (reached < 0 && d < 60 && speed(g.ship()) < slow) reached = i * DT;
            }
            T.check(reached > 0, f[0] + "," + f[1] + ": within 60 px and slow within 6 s");
            T.check(dist(g, tx, ty) < 60 && speed(g.ship()) < slow, "holding there at 6 s: " + dist(g, tx, ty) + " px, "
                + speed(g.ship()) + " px/s");
            T.check(g.arrived(), "arrived");
            T.check(g.isManual(), "still manual");
        }
    }

    public static void testNoOvershoot() {
        for (float[] f : FLIGHTS) {
            GameWorld g = live(2);
            place(g, f[0], f[1]);
            float tx = f[2], ty = f[3];
            g.aim(tx, ty);
            float best = 1e9f, worst = 0;
            for (int i = 1; i <= Math.round(7f / DT); i++) {
                if (i % Math.round(3f / DT) == 0) g.aim(tx, ty);
                g.step(DT);
                g.shots().clear();
                float d = dist(g, tx, ty);
                best = Math.min(best, d);
                if (best < 30) worst = Math.max(worst, d); // once close, it does not sail past and come back
            }
            T.check(best < 30, "close: " + best);
            T.check(worst < 45, f[0] + "," + f[1] + ": settles without overshooting: strayed " + worst);
        }
    }

    public static void testEnginesFireInManualMode() {
        GameWorld g = live(3);
        place(g, 110, 560);
        g.aim(510, 560);
        int burns = 0;
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.step(DT);
            T.check(g.isManual(), "manual");
            if (g.engineCommand() > 0) {
                burns++;
                T.check(Math.abs(g.headingError()) < 25 * DEG, "burns only near the wanted heading: " + g.headingError() / DEG);
                T.check(g.nozzleThrust(0) > 0 || burns == 1, "the nozzles light");
            }
        }
        T.check(burns > 30, "the engines fire: " + burns);
    }

    public static void testRockOnTheStraightPathIsAvoided() {
        for (float[] f : FLIGHTS) {
            Count c = new Count();
            GameWorld g = live(4);
            g.setListener(c);
            place(g, f[0], f[1]);
            float tx = f[2], ty = f[3];
            GameWorld.Rock keep = g.addRock(3, (f[0] + tx) / 2, (f[1] + ty) / 2, 0, 0);
            g.aim(tx, ty);
            float reached = -1, clearance = 1e9f;
            for (int i = 1; i <= Math.round(7f / DT); i++) {
                if (i % Math.round(3f / DT) == 0) g.aim(tx, ty);
                g.step(DT);
                g.shots().clear(); // only the dodge is under test, not the guns
                for (int j = g.rocks().size() - 1; j >= 0; j--) if (g.rocks().get(j) != keep) g.rocks().remove(j);
                clearance = Math.min(clearance, (float) Math.hypot(g.ship().x - keep.x, g.ship().y - keep.y));
                if (reached < 0 && dist(g, tx, ty) < 60) reached = i * DT;
            }
            T.eq(0, c.hits, f[0] + "," + f[1] + ": no collision (closest " + clearance + " px from the centre)");
            T.check(reached > 0, f[0] + "," + f[1] + ": still reaches the waypoint, now " + dist(g, tx, ty) + " px off");
        }
    }

    public static void testSecondTapRetargets() {
        GameWorld g = live(5);
        place(g, 110, 560);
        g.aim(510, 560);
        for (int i = 0; i < Math.round(1.5f / DT); i++) g.step(DT);
        T.check(g.ship().vx > 40, "on its way right: " + g.ship().vx);
        g.aim(150, 200);
        T.near(150, g.waypointX(), 1e-6, "waypoint x replaced at once");
        T.near(200, g.waypointY(), 1e-6, "waypoint y replaced at once");
        float d0 = dist(g, 150, 200), best = d0;
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.step(DT);
            g.shots().clear();
            best = Math.min(best, dist(g, 150, 200));
        }
        T.check(best < d0 - 200, "it heads for the newer tap: " + best + " from " + d0);
        T.check(dist(g, 510, 560) > 150, "not the old one");
    }

    public static void testEachTapFiresExactlyOneBoltAlongTheHeading() {
        GameWorld g = live(6);
        float[][] taps = {{310, 100}, {560, 390}, {100, 200}, {200, 600}, {500, 90}, {80, 650}};
        for (float[] t : taps) {
            for (int i = 0; i < 19; i++) {
                g.step(DT);
                for (GameWorld.Shot b : g.shots()) T.check(b.t > DT, "no bolt between taps");
            }
            int before = g.shots().size();
            g.aim(t[0], t[1]);
            g.step(DT);
            int fresh = 0;
            for (GameWorld.Shot b : g.shots()) {
                if (b.t != DT) continue;
                fresh++;
                T.check(Math.abs(GameWorld.wrapAngle(b.angle - g.ship().angle)) <= 3 * DEG + 1e-6f, "along the heading");
            }
            T.eq(1, fresh, "one bolt for the tap at " + t[0] + "," + t[1]);
            T.check(g.shots().size() <= before + 1, "and only one");
        }
    }

    public static void testAutopilotResumesFourSecondsAfterTheLastTap() {
        GameWorld g = live(7);
        g.setWanderTargetForTest(310, 300);
        g.aim(510, 590);
        for (int i = 0; i < Math.round(2f / DT); i++) g.step(DT);
        g.aim(100, 200);
        int steps = 0;
        while (g.isManual() && steps < 1000) {
            T.eq(310f, g.wanderTargetX(), "no wandering in manual mode");
            g.step(DT);
            steps++;
        }
        T.near(4, steps * DT, 0.05, "manual until 4 s after the last tap");
        g.step(DT);
        T.check(g.wanderTargetX() != 310f || g.wanderTargetY() != 300f, "a fresh wander target");
        float vx = g.ship().vx, vy = g.ship().vy;
        g.step(DT);
        T.check(Math.hypot(g.ship().vx - vx, g.ship().vy - vy) <= g.config().thrust * DT * 1.01f, "no jerk");
        boolean burned = false;
        for (int i = 0; i < Math.round(10f / DT) && !burned; i++) {
            g.step(DT);
            burned = g.engineCommand() > 0;
        }
        T.check(burned, "the autopilot flies on");
    }

    public static void testSecondTapExtendsToSevenSeconds() {
        GameWorld g = live(6);
        g.aim(510, 590);
        for (int i = 0; i < Math.round(3f / DT); i++) g.step(DT);
        T.check(g.isManual(), "manual at 3 s");
        g.aim(100, 200);
        for (int i = 0; i < Math.round(3.8f / DT); i++) g.step(DT);
        T.check(g.isManual(), "manual at 6.8 s");
        for (int i = 0; i < Math.round(0.4f / DT); i++) g.step(DT);
        T.check(!g.isManual(), "over after 7 s");
    }

    public static void testShipStaysInBounds() {
        GameWorld g = live(10);
        GameWorld.Ship s = g.ship();
        s.vx = 400;
        s.vy = 400;
        float m = g.config().edgeMargin, band = 60 * g.config().k;
        float[][] taps = {{310, 60}, {2, 3}, {619, 721}, {619, 2}, {-50, 400}};
        for (float[] t : taps) {
            g.aim(t[0], t[1]);
            T.check(g.waypointX() >= band && g.waypointX() <= 620 - band, "waypoint x inside the edge band");
            T.check(g.waypointY() >= band && g.waypointY() <= 722 - band, "waypoint y inside the edge band");
            for (int i = 0; i < Math.round(3.9f / DT); i++) {
                g.step(DT);
                T.check(s.x >= m - 1e-3f && s.x <= 620 - m + 1e-3f && s.y >= m - 1e-3f && s.y <= 722 - m + 1e-3f, "inside");
                T.check(speed(s) <= GameConfig.SPEED_CAP * g.config().maxSpeed + 1e-3f, "speed cap");
            }
        }
    }

    public static void testCalmTopSpeed() {
        GameWorld g = live(11);
        place(g, 60, 600);
        float top = 0;
        g.aim(560, 120);
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.step(DT);
            g.rocks().clear();
            top = Math.max(top, speed(g.ship()));
        }
        T.check(top > g.config().maxSpeed * 0.9f, "it gets going: " + top);
        T.check(top <= g.config().maxSpeed * 1.3f, "but stays calm: " + top);
    }

    public static void testAutoFireIsNotATap() {
        GameWorld g = live(11);
        g.addRock(3, 310, 150, 0, 0);
        g.setAutoFire(true);
        for (int i = 0; i < 100; i++) g.step(DT);
        T.check(!g.isManual(), "auto-fire does not enter manual mode");
    }

    public static void testDeterministic() {
        float[] out = new float[2];
        for (int r = 0; r < 2; r++) {
            GameWorld g = live(12);
            g.addRock(3, 400, 450, -8, 5);
            g.aim(510, 590);
            for (int i = 0; i < 100; i++) g.step(DT);
            g.aim(60, 200);
            for (int i = 0; i < 600; i++) g.step(DT);
            out[r] = g.ship().x * 1000f + g.ship().y + g.ship().angle;
        }
        T.eq(out[0], out[1], "same seed, same flight");
    }
}
