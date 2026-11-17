package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

/**
 * Ship defence: the rock soonest on a collision course within 2 s is shot when the turn to its lead-aim bearing,
 * any cooldown left and the bolt's flight fit inside its time to impact with 0.15 s to spare (in manual mode only
 * when the turn is 40 degrees or less); otherwise today's emergency dodge handles it.
 */
public final class DefenceTest {
    private DefenceTest() {}

    private static final float DT = 0.016f;
    private static final float DEG = (float) (Math.PI / 180);

    private static final class Count implements GameWorld.Listener {
        int destroyed, hits;
        public void onRockDestroyed() { destroyed++; }
        public void onShipHit() { hits++; }
    }

    /**
     * A 620x722 (k = 1) world past its intro, no rocks, the ship at rest at (x, y) heading `angle`, holding it
     * (the intro restart starts the flight computer over from that heading) and station-keeping there.
     */
    private static GameWorld live(long seed, Count c, float x, float y, float angle) {
        GameWorld g = new GameWorld(new Rng(seed));
        g.resize(620, 722);
        if (c != null) g.setListener(c);
        g.rocks().clear();
        GameWorld.Ship s = g.ship();
        s.x = x;
        s.y = y;
        s.angle = angle;
        g.restartIntro();
        g.skipIntro();
        g.setWanderTargetForTest(x, y);
        return g;
    }

    private static GameWorld live(long seed, Count c) { return live(seed, c, 310, 390, 0f); }

    /** Removes the spawner's rocks (they enter off screen); the test's rocks and their fragments stay. */
    private static void dropSpawned(GameWorld g) {
        for (int j = g.rocks().size() - 1; j >= 0; j--) {
            GameWorld.Rock r = g.rocks().get(j);
            if (r.x < 0 || r.x > 620 || r.y < 0 || r.y > 722) g.rocks().remove(j);
        }
    }

    public static void testTurnTime() {
        float acc = (float) Math.toRadians(540), spin = (float) Math.PI, knee = spin * spin / (0.8f * acc);
        T.near(0f, GameWorld.turnTime(0f, acc, spin), 1e-6f, "no turn");
        float a = 0.5f * knee;
        T.near(2f * (float) Math.sqrt(a / (0.8f * acc)), GameWorld.turnTime(-a, acc, spin), 1e-5f, "short turn: bang-bang");
        T.near((float) Math.PI / spin + spin / (0.8f * acc), GameWorld.turnTime((float) Math.PI, acc, spin), 1e-5f,
            "half turn: capped spin");
        T.near(2f * (float) Math.sqrt(knee / (0.8f * acc)), knee / spin + spin / (0.8f * acc), 1e-5f, "continuous at the knee");
    }

    /** A small rock dead ahead closing at 120 px/s is shot down before it arrives, without a dodge. */
    public static void testRockDeadAheadIsShot() {
        Count c = new Count();
        GameWorld g = live(1, c);
        GameWorld.Rock rock = g.addRock(1, 310 + 300, 390, -120, 0);
        boolean evaded = false;
        float goneAt = -1f;
        for (int i = 1; i <= Math.round(4f / DT); i++) {
            g.step(DT);
            dropSpawned(g);
            evaded |= g.evading();
            if (goneAt < 0 && !g.rocks().contains(rock)) {
                goneAt = i * DT;
                T.eq(0, c.hits, "gone before any hit");
            }
        }
        T.check(g.defenceShots() >= 1, "it shot: " + g.defenceShots());
        T.check(goneAt > 0, "the rock is gone");
        T.eq(0, c.hits, "no hit");
        T.check(!evaded, "never a dodge");
    }

    /**
     * A rock 120 px behind closing at 260 px/s touches in 0.32 s; a half turn alone takes 1.42 s, so it is
     * dodged, never shot.
     */
    public static void testFastCloseRockFromBehindIsDodged() {
        GameWorld g = live(2, null);
        GameWorld.Rock rock = g.addRock(2, 310 - 120, 390, 260, 0);
        boolean dodged = false;
        for (int i = 1; i <= Math.round(1.5f / DT) && !dodged; i++) {
            g.step(DT);
            dropSpawned(g);
            if (g.evading()) {
                dodged = true;
                T.eq(0, g.defenceShots(), "no shot before the dodge");
            }
            T.check(!g.defending(), "no defence aim at " + i * DT);
        }
        T.check(dodged, "it dodged");
        T.check(g.rocks().contains(rock), "the rock was not shot");
    }

    /**
     * A rock 300 px behind closing at 100 px/s (2.6 s out; it comes within 2 s after 0.64 s): the half turn (1.42 s)
     * plus the bolt's flight (0.24 s) fit with 0.15 s to spare, so the ship turns round and shoots it. (The brief's
     * 160 px/s gives 1.42 + 0.34 + 0.15 = 1.91 s > the 1.65 s it has, so by the rule that one is dodged.)
     */
    public static void testSlowFarRockFromBehindIsTurnedOnAndShot() {
        Count c = new Count();
        GameWorld g = live(3, c);
        GameWorld.Ship s = g.ship();
        GameWorld.Rock rock = g.addRock(2, 310 - 300, 390, 100, 0);
        float defendedAt = -1f, turned = 0f;
        boolean evaded = false;
        for (int i = 1; i <= Math.round(4f / DT); i++) {
            g.step(DT);
            dropSpawned(g);
            if (defendedAt < 0 && g.defending()) defendedAt = i * DT;
            turned = Math.max(turned, Math.abs(GameWorld.wrapAngle(s.angle)));
            evaded |= g.evading();
        }
        T.check(defendedAt > 0 && defendedAt <= 0.7f, "it turns to defend as soon as the rock is 2 s out: " + defendedAt);
        T.check(turned > 120 * DEG, "it turns round: " + turned / DEG);
        T.check(g.defenceShots() >= 1, "it shot: " + g.defenceShots());
        T.check(!g.rocks().contains(rock), "the rock is gone");
        T.eq(0, c.hits, "no hit");
        T.check(!evaded, "never a dodge");
    }

    /**
     * Manual mode, flying to a tap on the right; a rock comes straight down at it from 90 degrees off the heading:
     * more than 40 degrees of turn, so it is dodged and the heading stays with the waypoint (the dodge cone).
     */
    public static void testManualRockAt90IsDodged() {
        GameWorld g = live(4, null);
        GameWorld.Ship s = g.ship();
        g.aim(560, 390);
        float bearing = (float) Math.atan2(g.waypointY() - 390, g.waypointX() - 310);
        g.addRock(2, 310, 390 - 180, 0, 250);
        boolean dodged = false;
        float worst = 0f;
        for (int i = 1; i <= Math.round(1.5f / DT); i++) {
            g.step(DT);
            dropSpawned(g);
            if (g.evading() && !dodged) {
                dodged = true;
                T.eq(0, g.defenceShots(), "no shot before the dodge");
            }
            T.check(!g.defending(), "no defence aim at " + i * DT);
            worst = Math.max(worst, Math.abs(GameWorld.wrapAngle(s.angle - bearing)));
        }
        T.check(dodged, "it dodged");
        T.eq(0, g.defenceShots(), "never shot");
        T.check(worst <= 60 * DEG, "the heading stays with the waypoint: " + worst / DEG + " degrees off");
    }

    /**
     * One bolt per engagement: a rock far ahead (the bolt's flight 0.35 s, past the 0.12 s cooldown) is fired on once;
     * with that bolt taken away (a miss) it is not fired on again until the flight plus 0.1 s are up, then it is
     * judged again and shot at once more.
     */
    public static void testOneBoltPerEngagement() {
        GameWorld g = live(5, null, 200, 390, 0f);
        GameWorld.Rock rock = g.addRock(1, 600, 390, -173, 0);
        float firstAt = -1f, secondAt = -1f;
        for (int i = 1; i <= Math.round(1.5f / DT) && secondAt < 0; i++) {
            g.step(DT);
            g.shots().clear(); // every bolt misses
            dropSpawned(g);
            T.check(g.rocks().contains(rock), "the rock stays");
            if (firstAt < 0 && g.defenceShots() >= 1) firstAt = i * DT;
            if (secondAt < 0 && g.defenceShots() >= 2) secondAt = i * DT;
        }
        T.check(firstAt > 0, "the first bolt");
        float flight = 0.35f;
        T.check(secondAt - firstAt >= flight + 0.1f - 2 * DT, "one bolt until the engagement is up: next after "
            + (secondAt - firstAt) + " s");
        T.check(secondAt - firstAt <= flight + 0.1f + 3 * DT, "then judged again at once: " + (secondAt - firstAt) + " s");
    }

    /**
     * A big rock dead ahead closing at 220 px/s is shot and splits; a fragment still on a collision course within
     * 2 s is shot or dodged (never ignored until it bumps the ship).
     */
    public static void testFragmentsAreJudgedAgain() {
        int threatened = 0, shotAgain = 0;
        for (long seed = 1; seed <= 6; seed++) {
            Count c = new Count();
            GameWorld g = live(seed, c);
            GameWorld.Ship s = g.ship();
            GameWorld.Rock big = g.addRock(3, 310 + 300, 390, -220, 0); // its pieces keep 0.6 of that
            boolean split = false, threat = false, handled = false;
            for (int i = 1; i <= Math.round(5f / DT); i++) {
                int hits = c.hits;
                g.step(DT);
                dropSpawned(g);
                if (!split && !g.rocks().contains(big)) split = true;
                handled |= g.defenceShots() >= 2 || g.evading();
                if (c.hits > hits) T.check(handled, "seed " + seed + ": a fragment hit at " + i * DT + " s, never judged");
                if (!split) continue;
                for (GameWorld.Rock r : g.rocks()) {
                    float rx = r.x - s.x, ry = r.y - s.y, ux = r.vx - s.vx, uy = r.vy - s.vy, u2 = ux * ux + uy * uy;
                    float tc = -(rx * ux + ry * uy) / Math.max(1e-6f, u2);
                    if (tc <= 0f || tc > 2f) continue; // on a collision course within 2 s
                    float reach = r.r * 0.82f + 11f + 8f;
                    if (Math.hypot(rx + ux * tc, ry + uy * tc) < reach) threat = true;
                }
            }
            T.check(split, "seed " + seed + ": the big rock was shot");
            if (threat) {
                threatened++;
                T.check(handled, "seed " + seed + ": a fragment on a collision course was shot or dodged");
            }
            if (g.defenceShots() >= 2) shotAgain++;
        }
        T.check(threatened >= 1, "some fragment came at the ship: " + threatened);
        T.check(shotAgain >= 1, "some fragment was shot: " + shotAgain);
    }

    public static void testDefenceDeterministic() {
        GameWorld a = live(7, null), b = live(7, null);
        GameWorld[] gs = {a, b};
        for (GameWorld g : gs) {
            g.addRock(3, 310 + 300, 390, -120, 0);
            g.addRock(2, 310 - 300, 390, 100, 0);
        }
        for (int i = 0; i < Math.round(6f / DT); i++) {
            a.step(DT);
            b.step(DT);
        }
        T.check(a.defenceShots() >= 1, "it defended: " + a.defenceShots());
        T.eq(a.defenceShots(), b.defenceShots(), "shots");
        T.eq(a.shots().size(), b.shots().size(), "bolts flying");
        T.eq(a.ship().x, b.ship().x, "x");
        T.eq(a.ship().y, b.ship().y, "y");
        T.eq(a.ship().angle, b.ship().angle, "angle");
    }
}
