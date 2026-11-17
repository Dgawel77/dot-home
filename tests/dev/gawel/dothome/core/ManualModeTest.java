package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * A tap is a waypoint: until the later of 4 s after the last tap and 2 s after it arrives (12 s cap) the flight
 * computer flies the ship straight there (Newtonian: main engines forward, nose retros to brake, no flip) and holds
 * station once it arrives; then the autopilot wanders again. Rocks it would merely pass are ignored; only one on a
 * collision course within 0.5 s makes it dodge, briefly. A newer tap retargets at once.
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

    /** Removes every rock but the kept ones (the spawner's, and the pieces of one a stray bolt splits). */
    private static void keepOnly(GameWorld g, GameWorld.Rock... keep) {
        outer:
        for (int j = g.rocks().size() - 1; j >= 0; j--) {
            for (GameWorld.Rock r : keep) if (g.rocks().get(j) == r) continue outer;
            g.rocks().remove(j);
        }
    }

    /** The ship's velocity toward (x, y), px/s. */
    private static float closing(GameWorld g, float x, float y) {
        GameWorld.Ship s = g.ship();
        float dx = x - s.x, dy = y - s.y, d = (float) Math.hypot(dx, dy);
        return (s.vx * dx + s.vy * dy) / d;
    }

    /** Seconds a clean 300 px trip (from rest, the tap 90 degrees off the heading) takes to arrive, measured. */
    private static final float TRIP_300 = 4.37f;

    /**
     * The ship at rest at (110, 560) heading up, a tap 300 px to its right, and, when rocks is set, rocks it passes
     * without touching: two still big ones 70 px either side of the straight line and a small one drifting down
     * across it well ahead. The old avoidance (73.5 px clearance around a big rock) would have swerved round all
     * three. Returns the heading at each step until arrival (NaN after), and the arrival time in [0].
     */
    private static float[] tripPastRocks(boolean rocks) {
        GameWorld g = live(13);
        place(g, 110, 560);
        GameWorld.Rock[] keep = rocks
            ? new GameWorld.Rock[] {g.addRock(3, 230, 490, 0, 0), g.addRock(3, 330, 630, 0, 0), g.addRock(2, 300, 380, 0, 40)}
            : new GameWorld.Rock[0];
        g.aim(410, 560);
        int n = Math.round(6f / DT);
        float[] out = new float[n + 1];
        java.util.Arrays.fill(out, Float.NaN);
        out[0] = -1;
        for (int i = 1; i <= n && out[0] < 0; i++) {
            g.step(DT);
            g.shots().clear();
            keepOnly(g, keep);
            T.check(!g.evading(), "no dodge: nothing is on a collision course (" + i * DT + " s)");
            out[i] = g.ship().angle;
            if (g.arrived()) out[0] = i * DT;
        }
        return out;
    }

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
            for (int i = 1; i <= Math.round(5.5f / DT); i++) {
                g.step(DT);
                g.shots().clear();
                float d = dist(g, tx, ty);
                if (i % Math.round(0.5f / DT) == 0 && i * DT >= 1f && mark > 60) { // after the first turn
                    T.check(d < mark, f[0] + "," + f[1] + ": closing in at " + i * DT + " s: " + d + " from " + mark);
                    mark = d;
                }
                if (reached < 0 && d < 60 && speed(g.ship()) < slow) reached = i * DT;
            }
            T.check(reached > 0, f[0] + "," + f[1] + ": within 60 px and slow within 5.5 s");
            T.check(dist(g, tx, ty) < 60 && speed(g.ship()) < slow, "holding there at 5.5 s: " + dist(g, tx, ty) + " px, "
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

    public static void testTapIsObeyedPastRocksItWillNotHit() {
        float[] clean = tripPastRocks(false), rocky = tripPastRocks(true);
        T.near(TRIP_300, clean[0], 0.1, "a clean 300 px trip takes what it measured");
        T.check(rocky[0] > 0 && rocky[0] <= TRIP_300 * 1.2f, "arrives as fast as the physics allows (+20%): " + rocky[0] + " s");
        float worst = 0f, facing = -1f;
        for (int i = 1; i < clean.length && !Float.isNaN(clean[i]) && !Float.isNaN(rocky[i]); i++) {
            worst = Math.max(worst, Math.abs(GameWorld.wrapAngle(rocky[i] - clean[i])));
            if (facing < 0 && Math.abs(rocky[i]) < 10 * DEG) facing = i * DT; // the waypoint bears 0 degrees
        }
        T.check(worst <= 5 * DEG, "the rocks do not deflect the heading: " + worst / DEG + " degrees");
        T.check(facing > 0 && facing <= 1f, "it turns onto the waypoint's bearing within 1 s: " + facing);
    }

    public static void testDodgesOnlyARockOnACollisionCourseWithinHalfASecond() {
        // in flight to the right at 140 px/s; a big rock dead ahead touches (hit radius 38.5 px, +8 margin) 1 s out
        for (int pass = 0; pass < 2; pass++) {
            boolean hit = pass == 0;
            GameWorld g = live(14);
            place(g, 110, 560);
            GameWorld.Ship s = g.ship();
            s.angle = 0f;
            s.vx = 140f;
            float rx = 110 + 46.5f + 140, ry = hit ? 560 : 560 + 60; // the miss passes 60 px off, outside 46.5
            GameWorld.Rock rock = g.addRock(3, rx, ry, 0, 0);
            g.aim(510, 560);
            GameWorld twin = live(14);
            place(twin, 110, 560);
            twin.ship().angle = 0f;
            twin.ship().vx = 140f;
            twin.aim(510, 560);
            float dodgedAt = -1, worst = 0;
            for (int i = 1; i <= Math.round(2f / DT); i++) {
                float gap = (float) Math.hypot(s.x - rx, s.y - ry) - 46.5f, toTouch = gap / Math.max(1f, closing(g, rx, ry));
                g.step(DT);
                twin.step(DT);
                g.shots().clear();
                twin.shots().clear();
                keepOnly(g, rock);
                keepOnly(twin);
                if (g.evading() && dodgedAt < 0) {
                    dodgedAt = i * DT;
                    T.check(toTouch <= 0.5f + DT, "no dodge earlier than 0.5 s out: " + toTouch + " s");
                }
                if (dodgedAt < 0) worst = Math.max(worst, Math.abs(GameWorld.wrapAngle(s.angle - twin.ship().angle)));
            }
            if (hit) {
                T.check(dodgedAt > 0, "a rock about to hit makes it dodge");
                T.check(worst <= 1e-6f, "and nothing before then: " + worst / DEG);
            } else {
                T.eq(-1f, dodgedAt, "a rock it will pass is no reason to dodge");
                T.check(worst <= 5 * DEG, "nor to turn: " + worst / DEG + " degrees");
            }
        }
    }

    public static void testRockOnTheStraightPathOnlyBumpsIt() {
        for (float[] f : FLIGHTS) {
            Count c = new Count();
            GameWorld g = live(4);
            g.setListener(c);
            place(g, f[0], f[1]);
            float tx = f[2], ty = f[3];
            GameWorld.Rock keep = g.addRock(3, (f[0] + tx) / 2, (f[1] + ty) / 2, 0, 0);
            g.aim(tx, ty);
            float reached = -1;
            boolean dodged = false;
            for (int i = 1; i <= Math.round(7f / DT); i++) {
                g.step(DT);
                g.shots().clear(); // only the flight is under test, not the guns
                keepOnly(g, keep);
                dodged |= g.evading();
                if (reached < 0 && dist(g, tx, ty) < 60) reached = i * DT;
            }
            T.check(dodged, f[0] + "," + f[1] + ": the emergency dodge fires");
            T.check(c.hits <= 1, f[0] + "," + f[1] + ": at most a bump: " + c.hits);
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

    /**
     * Seconds a reversal (flying right at about 130 px/s, then a tap 90 px behind) takes to close on the new point,
     * measured: it turns round, the retros braking while it turns.
     */
    private static final float REVERSE = 1.90f;

    public static void testOppositeTapStillReverses() {
        GameWorld g = live(5);
        place(g, 110, 560);
        g.aim(510, 560);
        for (int i = 0; i < Math.round(1.5f / DT); i++) {
            g.step(DT);
            g.shots().clear();
        }
        T.check(g.ship().vx > 120 && g.engineCommand() > 0, "burning right: " + g.ship().vx);
        g.aim(80, 560);
        g.step(DT);
        g.shots().clear();
        T.eq(0f, g.engineCommand(), "the burn for the old point stops at once");
        T.check(Math.abs(g.headingError()) > 150 * DEG, "and it wants to face the new one (a turn): " + g.headingError() / DEG);
        float toward = -1, turned = 0;
        for (int i = 2; i <= Math.round(4f / DT) && toward < 0; i++) {
            g.step(DT);
            g.shots().clear();
            turned = Math.max(turned, Math.abs(g.ship().angle));
            if (closing(g, 80, 560) > 0) toward = i * DT;
        }
        T.check(toward > 0 && toward <= REVERSE * 1.2f, "closing on the new point within " + REVERSE * 1.2f + " s: " + toward);
        for (int i = 0; i < Math.round(1f / DT); i++) {
            g.step(DT);
            g.shots().clear();
            turned = Math.max(turned, Math.abs(g.ship().angle));
        }
        T.check(turned > 120 * DEG, "it turned round for the point behind: " + turned / DEG);
    }

    public static void testTapAheadDropsTheRetroBraking() {
        GameWorld g = live(5);
        place(g, 110, 560);
        g.ship().angle = 0f; // facing the tap: the flight is a burn, a coast and a retro brake
        g.aim(330, 560);
        boolean braking = false;
        for (int i = 0; i < Math.round(4f / DT) && !braking; i++) {
            g.step(DT);
            g.shots().clear();
            braking = g.retroCommand() > 0 && Math.cos(g.ship().angle) > 0.9 && g.ship().vx > 30;
        }
        T.check(braking, "nose forward and braking on the retros for the first tap");
        g.aim(540, 560); // further on: no more braking
        g.step(DT);
        g.shots().clear();
        T.eq(0f, g.retroCommand(), "the retro braking stops at once");
        T.check(Math.abs(g.headingError()) < 30 * DEG, "it keeps facing forward: " + g.headingError() / DEG);
        boolean burn = false;
        for (int i = 0; i < Math.round(0.5f / DT) && !burn; i++) {
            g.step(DT);
            g.shots().clear();
            burn = g.engineCommand() > 0;
        }
        T.check(burn, "and burns on for the newer tap");
    }

    /** Seconds a clean 300 px trip (from rest, facing the tap) takes to settle on the waypoint, measured. */
    private static final float ARRIVE_300 = 3.78f;

    public static void testArrivesWithRetros() {
        GameWorld g = live(15);
        place(g, 110, 560);
        g.ship().angle = 0f;
        g.aim(410, 560);
        float arrivedAt = -1, retro = 0, best = 1e9f, worst = 0;
        for (int i = 1; i <= Math.round(8f / DT) && g.isManual(); i++) { // until manual mode lets go, 2 s after arrival
            g.step(DT);
            g.shots().clear();
            g.rocks().clear();
            retro = Math.max(retro, g.retroThrust());
            float d = dist(g, 410, 560);
            best = Math.min(best, d);
            if (best < 20) worst = Math.max(worst, d);
            if (arrivedAt < 0 && g.arrived()) arrivedAt = i * DT;
        }
        T.check(arrivedAt > 0, "arrives: " + dist(g, 410, 560) + " px off");
        T.check(arrivedAt <= ARRIVE_300 * 1.2f, "within " + ARRIVE_300 * 1.2f + " s (measured +20%): " + arrivedAt);
        T.check(retro > 0.3f, "braking on the retros: " + retro);
        T.check(worst < 30, "settles without overshooting: strayed " + worst);
        T.check(dist(g, 410, 560) < GameWorld.ARRIVE_NEAR && speed(g.ship()) < GameWorld.ARRIVE_SPEED, "holds station there: "
            + dist(g, 410, 560) + " px, " + speed(g.ship()) + " px/s");
    }

    /**
     * A tap 35 px behind a resting ship (inside the arrival radius, so it counts as arrived at once) still turns
     * the ship to face it: there is no speed to shed, so it is not braking.
     */
    public static void testTapJustBehindASlowShipTurnsToIt() {
        GameWorld g = live(16);
        place(g, 310, 560); // heading up, at rest
        g.aim(310, 595);
        float turned = 0;
        for (int i = 1; i <= Math.round(1.9f / DT); i++) { // manual mode holds 2 s after arrival
            g.step(DT);
            g.shots().clear();
            g.rocks().clear();
            turned = Math.max(turned, Math.abs(GameWorld.wrapAngle(g.ship().angle + 90 * DEG)));
        }
        T.check(g.isManual(), "still holding station");
        T.check(turned > 150 * DEG, "it turns round: " + turned / DEG);
        T.check(dist(g, 310, 595) < GameWorld.ARRIVE_NEAR, "near the tap: " + dist(g, 310, 595));
        T.check(Math.abs(GameWorld.wrapAngle(g.ship().angle - 90 * DEG)) < 30 * DEG,
            "and faces the way it came (down): " + g.ship().angle / DEG);
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

    public static void testSingleTapSettlesInManualThenAutopilotResumesTwoSecondsAfterArrival() {
        GameWorld g = live(7);
        place(g, 110, 560);
        g.setWanderTargetForTest(310, 300);
        g.aim(510, 560);
        float arrivedAt = -1;
        int steps = 0;
        while (g.isManual() && steps < 1000) {
            T.eq(310f, g.wanderTargetX(), "no wandering in manual mode");
            g.step(DT);
            g.shots().clear();
            steps++;
            if (arrivedAt < 0 && g.arrived()) {
                arrivedAt = steps * DT;
                T.check(dist(g, 510, 560) < GameWorld.ARRIVE_NEAR, "arrived within the radius: " + dist(g, 510, 560));
                T.check(g.isManual(), "still manual on arrival");
            }
        }
        T.check(arrivedAt > 3f, "a 400 px trip takes a while: " + arrivedAt);
        T.near(arrivedAt + 2, steps * DT, 0.05, "manual until 2 s after arrival");
        T.check(steps * DT > 4f, "later than 4 s");
        g.step(DT);
        T.check(g.wanderTargetX() != 310f || g.wanderTargetY() != 300f, "a fresh wander target");
        float vx = g.ship().vx, vy = g.ship().vy;
        g.step(DT);
        T.check(Math.hypot(g.ship().vx - vx, g.ship().vy - vy) <= g.config().thrust * DT * 1.01f, "no jerk");
        boolean burned = false;
        for (int i = 0; i < Math.round(10f / DT) && !burned; i++) {
            g.step(DT);
            g.shots().clear();
            burned = g.engineCommand() > 0;
        }
        T.check(burned, "the autopilot flies on");
    }

    public static void testManualModeIsCappedAtTwelveSecondsWhenTheWaypointIsUnreachable() {
        GameWorld g = live(8);
        place(g, 110, 560);
        g.aim(510, 560);
        int steps = 0;
        while (g.isManual() && steps < 2000) {
            place(g, 110, 560); // held back: it can never get there
            g.step(DT);
            g.shots().clear();
            steps++;
        }
        T.check(!g.arrived(), "never arrived");
        T.near(12, steps * DT, 0.05, "released at the 12 s cap");
    }

    public static void testSecondTapExtendsManualMode() {
        GameWorld g = live(6);
        place(g, 310, 600);
        g.aim(310, 570);
        for (int i = 0; i < Math.round(3f / DT); i++) g.step(DT);
        T.check(g.isManual(), "manual at 3 s");
        g.aim(310, 580);
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
        int[] dodges = new int[2];
        for (int r = 0; r < 2; r++) {
            GameWorld g = live(12);
            g.addRock(3, 400, 450, -8, 5); // across the first leg: an emergency dodge
            g.aim(510, 590);
            for (int i = 0; i < 700; i++) {
                if (i == 100) g.aim(60, 200);
                g.step(DT);
                if (g.evading()) dodges[r]++;
            }
            out[r] = g.ship().x * 1000f + g.ship().y + g.ship().angle;
        }
        T.check(dodges[0] > 0, "the flight includes a dodge");
        T.eq(dodges[0], dodges[1], "the same dodges");
        T.eq(out[0], out[1], "same seed, same flight");
    }
}
