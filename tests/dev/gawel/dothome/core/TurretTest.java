package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/** Ship v2 turret lasers: instant bolts toward the tap, from the hull edge facing it (design/mockups/ShipV2.dc.html). */
public final class TurretTest {
    private TurretTest() {}

    private static final float DT = 0.016f;

    private static final class Count implements GameWorld.Listener {
        int destroyed;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() {}
    }

    /** Past the intro, no rocks, autopilot off: the ship rests at (310, 390) heading up. */
    private static GameWorld live(Count c) {
        GameWorld g = new GameWorld(new Rng(1));
        g.resize(620, 722);
        if (c != null) g.setListener(c);
        g.rocks().clear();
        g.setAutopilot(false);
        g.skipIntro();
        return g;
    }

    /** The bolt flies at the bolt speed along the line from where it starts to the tap. */
    private static void aimedAt(GameWorld g, GameWorld.Shot b, float x, float y) {
        float sp = (float) Math.hypot(b.vx, b.vy);
        T.near(900f * g.config().k, sp, 1e-2, "bolt speed");
        T.near(g.config().shotSpeed, sp, 1e-2, "bolt speed is the config's");
        float tx = x - b.x, ty = y - b.y, d = (float) Math.hypot(tx, ty);
        T.near(0, (b.vx * ty - b.vy * tx) / (sp * d), 1e-4, "aimed at the tap (cross)");
        T.check(b.vx * tx + b.vy * ty > 0, "toward the tap, not away");
        T.near(Math.atan2(b.vy, b.vx), b.angle, 1e-5, "bolt angle follows its path");
    }

    public static void testTapAheadFiresFromTheNoseAtOnce() {
        GameWorld g = live(null);
        GameWorld.Ship s = g.ship();
        float a0 = s.angle;
        T.eq(true, g.fire(500, 120), "fired");
        T.eq(1, g.shots().size(), "one bolt, at once");
        GameWorld.Shot b = g.shots().get(0);
        float k = g.config().k;
        T.near(s.x, b.x, 1e-3, "nose x"); // heading up: the nose is straight above the centre
        T.near(s.y - ShipShape.NOSE_X * k, b.y, 1e-3, "nose y");
        aimedAt(g, b, 500, 120);
        T.near(a0, s.angle, 1e-6, "a turret: the ship does not turn to fire");
        GameWorld.Flash f = g.flash();
        T.near(g.config().muzzleFlash, f.t, 1e-6, "muzzle flash");
        T.near(ShipShape.NOSE_X * k, f.x, 1e-4, "flash at the nose");
        T.near(GameWorld.wrapAngle(b.angle - s.angle), f.angle, 1e-5, "flash along the bolt");
        for (int i = 0; i < 6; i++) g.step(DT);
        T.check(f.t <= 0, "the flash is gone after about 0.08 s: " + f.t);
    }

    public static void testTapBehindFiresFromTheFacingHullEdge() {
        float[][] taps = {{310, 700}, {120, 560}, {520, 600}};
        for (float[] tap : taps) {
            GameWorld g = live(null);
            GameWorld.Ship s = g.ship();
            T.eq(true, g.fire(tap[0], tap[1]), "fired");
            GameWorld.Shot b = g.shots().get(0);
            float k = g.config().k;
            float off = (float) Math.hypot(b.x - s.x, b.y - s.y);
            T.near(0.6f * ShipShape.NOSE_X * k, off, 1e-3, "on the 0.6 x nose circle, not the nose");
            float rel = GameWorld.wrapAngle((float) Math.atan2(tap[1] - s.y, tap[0] - s.x) - s.angle);
            float edge = GameWorld.wrapAngle((float) Math.atan2(b.y - s.y, b.x - s.x) - s.angle);
            T.near(0, GameWorld.wrapAngle(edge - rel), 1e-4, "the edge point faces the tap");
            T.check(Math.hypot(tap[0] - b.x, tap[1] - b.y) < Math.hypot(tap[0] - s.x, tap[1] - s.y), "nearer the tap");
            aimedAt(g, b, tap[0], tap[1]);
        }
    }

    public static void testFireCooldownIsTheTurretRate() {
        GameWorld g = live(null);
        T.eq(true, g.fire(100, 100), "first");
        T.eq(false, g.fire(100, 100), "cooling");
        for (int i = 0; i < 6; i++) g.step(DT); // 0.096 s
        T.eq(false, g.fire(100, 100), "still cooling at 0.1 s");
        for (int i = 0; i < 2; i++) g.step(DT);
        T.eq(true, g.fire(100, 100), "ready after 0.12 s");
    }

    public static void testBoltHitsKillScoresAndFlares() {
        Count c = new Count();
        GameWorld g = live(c);
        GameWorld.Rock r = g.addRock(2, 310, 150, 0, 0);
        T.eq(true, g.fire(310, 150), "fired");
        GameWorld.Shot b = g.shots().get(0);
        int hitStep = -1;
        for (int i = 0; i < 40 && hitStep < 0; i++) {
            g.step(DT);
            if (g.laserHits() > 0) hitStep = i;
        }
        T.check(hitStep >= 0, "hit");
        T.eq(1, c.destroyed, "kill path: the listener scores it");
        T.eq(2, g.rocks().size() - (g.rocks().contains(r) ? 1 : 0), "split into two");
        T.check(g.particles().size() > 0, "grey debris");
        T.eq(1, g.laserHits(), "one hit event");
        T.near(r.y + r.r * 0.92f, g.laserHitY(0), 0.5, "hit on the rock's edge");
        T.near(310, g.laserHitX(0), 0.5, "hit x");
        T.near(b.angle, g.laserHitAngle(0), 1e-6, "hit angle is the bolt's");
        T.check(b.hit > 0, "the bolt lingers as a flare");
        T.near(g.laserHitY(0), b.y, 1e-3, "flare at the hit point");
        g.step(DT);
        T.eq(0, g.laserHits(), "events last one step");
        for (int i = 0; i < 4; i++) g.step(DT);
        T.eq(0, g.shots().size(), "flare over after about 0.06 s");
    }

    public static void testFastBoltCannotTunnelThroughSmallRock() {
        Count c = new Count();
        GameWorld g = live(c);
        g.addRock(1, 310, 200, 0, 0); // radius 12.7: a bolt moves 45 px in one 0.05 s step
        T.eq(true, g.fire(310, 200), "fired");
        for (int i = 0; i < 10; i++) g.step(0.05f);
        T.eq(1, c.destroyed, "a long frame still hits");
    }

    public static void testBoltsArePooled() {
        GameWorld g = live(null);
        java.util.Set<GameWorld.Shot> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<GameWorld.Shot, Boolean>());
        for (int n = 0; n < 200; n++) {
            g.fire(n % 2 == 0 ? 600 : 20, 40);
            for (GameWorld.Shot b : g.shots()) seen.add(b);
            for (int i = 0; i < 8; i++) g.step(DT);
        }
        T.check(seen.size() <= GameWorld.MAX_SHOTS, "bolts come from a fixed pool: " + seen.size());
        g.restartIntro();
        T.eq(0, g.shots().size(), "a replay clears the bolts");
        T.check(g.flash().t <= 0, "and the flash");
    }

    public static void testOffScreenBoltsAreDropped() {
        GameWorld g = live(null);
        g.fire(310, 0);
        for (int i = 0; i < 40; i++) g.step(DT); // 390 px to the top edge at 900 px/s: under 0.5 s
        T.eq(0, g.shots().size(), "gone past the edge");
    }
}
