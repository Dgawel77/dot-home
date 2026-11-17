package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** A tap switches the autopilot off for 4 s after the last tap: RCS holds the facing, the ship coasts under drag. */
public final class ManualModeTest {
    private ManualModeTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);

    private static final class Count implements GameWorld.Listener {
        int hits;
        public void onRockDestroyed() {}
        public void onShipHit() { hits++; }
    }

    private static GameWorld live(long seed) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        g.rocks().clear();
        g.skipIntro();
        return g;
    }

    private static float err(GameWorld g, float x, float y) {
        GameWorld.Ship s = g.ship();
        return Math.abs(GameWorld.wrapAngle((float) Math.atan2(y - s.y, x - s.x) - s.angle));
    }

    private static float speed(GameWorld.Ship s) { return (float) Math.sqrt(s.vx * s.vx + s.vy * s.vy); }

    public static void testNoBurnsAndNoWanderChangesForFourSeconds() {
        GameWorld g = live(3);
        g.setWanderTargetForTest(310, 100);
        float wx = g.wanderTargetX(), wy = g.wanderTargetY();
        g.aim(510, 590);
        T.eq(true, g.isManual(), "manual after a tap");
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.step(DT);
            T.check(g.isManual(), "still manual");
            T.eq(0f, g.engineCommand(), "no main-engine command");
            T.eq(0f, g.ship().thrust, "no thrust");
            T.eq(0f, g.nozzleThrust(0), "no nozzle event");
            T.eq(wx, g.wanderTargetX(), "wander x unchanged");
            T.eq(wy, g.wanderTargetY(), "wander y unchanged");
        }
    }

    public static void testHeadingConvergesAndHoldsTheTapBearing() {
        float[][] taps = {{510, 590}, {60, 390}, {310, 60}, {100, 680}};
        for (float[] t : taps) {
            GameWorld g = live(4);
            g.aim(t[0], t[1]);
            for (int i = 0; i < Math.round(3.9f / DT); i++) {
                g.step(DT);
                if (i * DT >= 1.5f) T.check(err(g, t[0], t[1]) < 5 * DEG, "within 5 deg at " + i * DT + " s: " + err(g, t[0], t[1]) / DEG);
            }
        }
    }

    public static void testSpeedDecaysUnderManualDrag() {
        GameWorld g = live(5);
        GameWorld.Ship s = g.ship();
        s.vx = 120;
        s.vy = -60;
        float v0 = speed(s);
        g.aim(310, 60);
        for (int i = 0; i < Math.round(3f / DT); i++) g.step(DT);
        T.check(speed(s) < v0 * 0.25f, "slowed: " + speed(s) + " from " + v0);
        T.check(speed(s) > 0f, "still coasting");
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

    public static void testAutopilotBurnsAgainAfterTheTimeout() {
        GameWorld g = live(7);
        g.setWanderTargetForTest(g.ship().x, g.ship().y);
        g.aim(510, 590);
        for (int i = 0; i < Math.round(4.1f / DT); i++) g.step(DT);
        T.check(!g.isManual(), "manual ended");
        boolean burned = false;
        for (int i = 0; i < Math.round(15f / DT) && !burned; i++) {
            g.step(DT);
            burned = g.engineCommand() > 0;
        }
        T.check(burned, "the autopilot burns again");
    }

    public static void testNoAvoidanceInManualMode() {
        GameWorld g = live(8);
        GameWorld.Rock keep = g.addRock(3, 310, 190, 0, 40);
        g.aim(60, 390);
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.shots().clear();
            g.step(DT);
            T.check(!g.evading(), "no evading");
            T.eq(0f, g.engineCommand(), "no dodge burn");
        }
    }

    public static void testRockHitStillBumpsAndRcsRecovers() {
        GameWorld g = live(9);
        Count c = new Count();
        g.setListener(c);
        GameWorld.Ship s = g.ship();
        g.aim(310, 60);
        for (int i = 0; i < 100; i++) g.step(DT);
        g.addRock(3, s.x + 40, s.y + 10, -60, 0);
        for (int i = 0; i < Math.round(1f / DT); i++) { g.shots().clear(); g.step(DT); }
        T.check(c.hits > 0, "bumped");
        for (int i = 0; i < Math.round(1.5f / DT); i++) { g.rocks().clear(); g.shots().clear(); g.step(DT); }
        T.check(err(g, 310, 60) < 5 * DEG, "facing the tap again");
    }

    public static void testShipStaysInBounds() {
        GameWorld g = live(10);
        GameWorld.Ship s = g.ship();
        s.vx = 400;
        s.vy = 400;
        g.aim(310, 60);
        float m = g.config().edgeMargin;
        for (int i = 0; i < Math.round(3.9f / DT); i++) {
            g.step(DT);
            T.check(s.x >= m - 1e-3f && s.x <= 620 - m + 1e-3f && s.y >= m - 1e-3f && s.y <= 722 - m + 1e-3f, "inside");
        }
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
            g.aim(510, 590);
            for (int i = 0; i < 100; i++) g.step(DT);
            g.aim(60, 200);
            for (int i = 0; i < 600; i++) g.step(DT);
            out[r] = g.ship().x * 1000f + g.ship().y + g.ship().angle;
        }
        T.eq(out[0], out[1], "same seed, same flight");
    }
}
