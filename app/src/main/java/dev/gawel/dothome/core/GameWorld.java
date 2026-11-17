package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java space-shooter simulation: a self-flying ship, drifting rocks, shots and particles.
 *
 * <p>The ship flies Newtonian (Ship v2, design/mockups/ShipV2.dc.html): it has a heading, a spin and a velocity
 * with near-zero drag, accelerates only along its heading with the main engines and turns only by firing its RCS.
 * A PD autopilot picks a desired velocity (wander target, rock avoidance, screen edges), turns toward the heading
 * that velocity error needs, burns only within 20 degrees of it, and so flips and burns to brake.
 *
 * <p>Tap-to-fire: a tap fires one bolt at once from the next twin gun (they alternate) along the current heading,
 * toed in toward the tap only when the tap lies within 3 degrees of it (the fire cooldown still applies). The tap
 * point also becomes the facing target: the RCS turns the ship to it, engines off unless a rock is about to hit
 * (avoidance wins), and holds the facing until within 3 degrees for 0.4 s or 1.5 s at most; a newer tap replaces it.
 * Auto-fire keeps shooting along the heading on its own cadence while the ship turns toward the nearest rock.
 *
 * <p>Thrusters are reported as per-step events the renderer reads (nozzle thrust levels, RCS firings), kept in
 * fixed arrays so a step allocates nothing for them. Angles are screen angles (y down): a growing angle turns
 * clockwise on screen.
 */
public final class GameWorld {
    public interface Listener {
        void onRockDestroyed();
        void onShipHit();
    }

    /** angle is the heading (radians, -PI..PI), spin its rate (rad/s), thrust the main-engine level 0..1. */
    public static final class Ship { public float x, y, vx, vy, angle, spin, thrust, hitCooldown; }
    public static final class Rock {
        public float x, y, vx, vy, r, rot, vr;
        public int size;
        /** Local x,y pairs of the polygon vertices. */
        public float[] outline;
    }
    /**
     * A laser bolt: position, the previous step's position (hits are swept along the path), velocity, its angle,
     * age, distance flown, life left and, once it hits, the seconds left of its flare at the hit point.
     */
    public static final class Shot { public float x, y, px, py, vx, vy, angle, t, trav, life, hit; }
    /** The red muzzle flash: ship-local position (px), the bolt's angle relative to the heading, seconds left. */
    public static final class Flash { public float x, y, angle, t; }
    public static final class Particle { public float x, y, vx, vy, life, life0, alpha; }
    public static final class Ring { public float x, y, t; }
    public static final class Star {
        public float x, y, alpha, phase, speed, depth;
        /** Twinkle period in seconds: 2 or 4. */
        public float period = 4f;
        /** About one star in ten is bright: drawn up to L100 and 1.5x bigger. */
        public boolean bright;
        /** Seconds left of a glint (a brief flare with four cross dots), 0 when none. */
        public float glint;
    }
    /** The shooting star: head position, unit direction, trail length, time and the end-fade envelope. */
    public static final class Meteor { public float x, y, dx, dy, t, alpha; public boolean active; }
    public static final class Shockwave { public float x, y, radius, maxRadius, t; }

    private static final float TAU = (float) (Math.PI * 2);

    private final Rng rng;
    private GameConfig cfg;
    private float w, h, time;
    /** Seconds since the intro (the opening dot) started; the world is live once it reaches cfg.introSeconds. */
    private float introT;
    /** The intro waits at its first frame (shown behind the keyguard) until restartIntro or skipIntro. */
    private boolean introHeld;
    private final Ship ship = new Ship();
    private final List<Rock> rocks = new ArrayList<Rock>();
    private final List<Shot> shots = new ArrayList<Shot>();
    /** Spent bolts for reuse: firing allocates nothing. */
    private final List<Shot> shotPool = new ArrayList<Shot>();
    /** At most this many bolts fly at once. */
    public static final int MAX_SHOTS = 40;
    private final Flash flash = new Flash();
    // the facing target: a tapped screen point, or (auto-fire) a rock the facing follows
    private boolean facing;
    private float faceX, faceY;
    private Rock faceRock;
    /** Seconds spent on the facing target, and seconds spent within cfg.faceCone of it. */
    private float faceClock, faceSettled;
    /** A tap's bolt waiting for the next step (fired along the heading as it is then), and the tap point. */
    private boolean tapFire;
    /** The gun that fires next (ShipShape: 0 port, 1 starboard). */
    private int gun;
    // laser hits of the last step, for the renderer's sparks
    private static final int MAX_HITS = 8;
    private final float[] hitX = new float[MAX_HITS], hitY = new float[MAX_HITS], hitA = new float[MAX_HITS];
    private int laserHits;
    private final List<Particle> particles = new ArrayList<Particle>();
    private final List<Ring> rings = new ArrayList<Ring>();
    private final List<Star> stars = new ArrayList<Star>();
    /** Star periods, glints and shooting stars draw from their own seeded stream, so the game's sequence is kept. */
    private final Rng fx;
    public static final float GLINT_SECONDS = 0.3f, METEOR_SECONDS = 0.7f;
    public static final float METEOR_MIN_GAP = 20f, METEOR_MAX_GAP = 60f;
    private final Meteor meteor = new Meteor();
    private float meteorT = -1f, meteorX0, meteorY0, meteorDist;
    private float glintT = -1f;
    private Star glintStar;
    private int glints, meteors;
    private Listener listener;
    private float tiltX, tiltY, fireT, spawnT = 2f, autoT;
    private boolean autoFire;
    private boolean autopilot = true;
    private float wanderX, wanderY, wanderT;

    // flight computer: the RCS angular acceleration and engine command it set this step, its low-passed desired
    // velocity, the heading it holds when there is nothing to correct, its pointing and burning latches, and
    // whether a rock about to hit has it dodging instead of aiming
    private float angAccel, thrustCmd, desVx, desVy, holdAngle, headingErr;
    private boolean pointing, burning, evading;

    // thruster events of the last step (fixed arrays: nothing is allocated per step)
    private final float[] nozzleThrust = new float[ShipShape.NOZZLES];
    private final int[] rcsPort = new int[2], rcsSide = new int[2];
    private final float[] rcsStrength = new float[2];
    private int rcsFirings;
    private Shockwave wave;
    private float shockCool;
    private float bandLo, bandHi;

    public GameWorld(Rng rng) {
        this.rng = rng;
        this.fx = new Rng(Double.doubleToLongBits(rng.next()));
        for (int i = 0; i < MAX_SHOTS; i++) shotPool.add(new Shot());
    }

    public void setListener(Listener l) { listener = l; }
    public void setTilt(float tx, float ty) { tiltX = clamp(tx, -1f, 1f); tiltY = clamp(ty, -1f, 1f); }
    /** Off, nothing flies the ship: the engines and RCS stay idle and it coasts (tests). */
    public void setAutopilot(boolean on) { autopilot = on; }

    /** Starts a shockwave at the ship; false during the intro or while the cooldown is running. */
    public boolean shockwave() {
        if (!isLive() || shockCool > 0) return false;
        shockCool = cfg.shockCooldown;
        Shockwave sw = new Shockwave();
        sw.x = ship.x;
        sw.y = ship.y;
        sw.maxRadius = hypot(w, h);
        wave = sw;
        return true;
    }

    /** Counts real time spent while the wallpaper was hidden toward the shockwave cooldown. */
    public void elapseHidden(float secs) {
        if (secs > 0 && shockCool > 0) shockCool = Math.max(0f, shockCool - secs);
    }

    /** The active shockwave, or null. */
    public Shockwave wave() { return wave; }

    public void setAutoFire(boolean on) { autoFire = on; }

    public Ship ship() { return ship; }
    public List<Rock> rocks() { return rocks; }
    public List<Shot> shots() { return shots; }
    public List<Particle> particles() { return particles; }
    public List<Ring> rings() { return rings; }
    public List<Star> stars() { return stars; }

    /** The shooting star in flight, or null. */
    public Meteor meteor() { return meteor.active ? meteor : null; }

    /** Glints started and shooting stars launched so far (tests). */
    int glintCount() { return glints; }

    int meteorCount() { return meteors; }
    public GameConfig config() { return cfg; }
    public float time() { return time; }
    /** Seconds since the intro started; at or past GameConfig.INTRO_SECONDS the intro is over. */
    public float introTime() { return introT; }

    /** Main-engine thrust of nozzle i (ShipShape: 0 port, 1 starboard) after the last step, 0..1. */
    public float nozzleThrust(int i) { return nozzleThrust[i]; }

    /** RCS jets fired in the last step: 0, or 2 (a nose and a tail jet on opposite sides, a couple that only turns). */
    public int rcsFirings() { return rcsFirings; }

    /** The port of RCS firing i: ShipShape.RCS_NOSE or RCS_TAIL. */
    public int rcsPort(int i) { return rcsPort[i]; }

    /**
     * The side RCS firing i blows out of: +1 starboard (+y), -1 port (-y). A clockwise turn fires the nose jet to
     * port and the tail jet to starboard; counter-clockwise the reverse.
     */
    public int rcsSide(int i) { return rcsSide[i]; }

    /** RCS firing i's strength: the angular acceleration over the RCS maximum, 0..1. */
    public float rcsStrength(int i) { return rcsStrength[i]; }

    /** Laser bolts that hit a rock in the last step (at most 8 are reported). */
    public int laserHits() { return laserHits; }

    public float laserHitX(int i) { return hitX[i]; }

    public float laserHitY(int i) { return hitY[i]; }

    /** The direction the bolt that made hit i was flying. */
    public float laserHitAngle(int i) { return hitA[i]; }

    /** The muzzle flash of the last bolt; over once t reaches 0. */
    public Flash flash() { return flash; }

    /** Ends the intro now, releasing any hold (a fold, page swipe or visibility flip must not replay it); world time is untouched. */
    public void skipIntro() {
        introHeld = false;
        if (introT < GameConfig.INTRO_SECONDS) introT = GameConfig.INTRO_SECONDS;
    }

    /**
     * Plays the intro from its first frame (the first show after unlock) so the scene starts from one dot: the ship
     * stops where it is and shots, debris, the shockwave and hit rings are cleared. Until the intro ends the ship
     * holds still (no autopilot, no collisions) and nothing fires: no taps, no auto-fire, no shockwave. Releases a hold.
     */
    public void restartIntro() {
        resetIntro();
        introHeld = false;
    }

    /** Like restartIntro, but the intro stays on its first frame (one dot) until restartIntro or skipIntro. */
    public void holdIntro() {
        resetIntro();
        introHeld = true;
    }

    private void resetIntro() {
        introT = 0f;
        ship.vx = 0f;
        ship.vy = 0f;
        ship.spin = 0f;
        ship.thrust = 0f;
        ship.hitCooldown = 0f;
        resetFlight();
        clearShots();
        clearAim();
        meteor.active = false;
        meteorT = -1f;
        flash.t = 0f;
        laserHits = 0;
        particles.clear();
        rings.clear();
        wave = null;
    }

    /** Past the intro: the autopilot steers, the ship collides, and taps, auto-fire and the shockwave work. */
    private boolean isLive() { return cfg != null && introT >= cfg.introSeconds; }
    public float width() { return w; }
    public float height() { return h; }

    public void resize(float nw, float nh) {
        if (!(nw > 0 && nh > 0)) return;
        GameConfig old = cfg;
        cfg = GameConfig.forSize(nw, nh);
        if (old == null) {
            w = nw;
            h = nh;
            computeWanderBand();
            ship.x = w / 2;
            ship.y = h * 0.54f;
            ship.angle = (float) (-Math.PI / 2);
            resetFlight();
            retargetStars();
            for (int i = 0; i < cfg.rocksTarget; i++) rocks.add(newRock(i % 3 == 2 ? 2 : 3, null, true));
            return;
        }
        float sx = nw / w, sy = nh / h, kr = cfg.k / old.k;
        w = nw;
        h = nh;
        ship.x = clamp(ship.x * sx, cfg.edgeMargin, w - cfg.edgeMargin);
        ship.y = clamp(ship.y * sy, cfg.edgeMargin, h - cfg.edgeMargin);
        for (Rock r : rocks) {
            r.x *= sx;
            r.y *= sy;
            r.r *= kr;
            for (int i = 0; i < r.outline.length; i++) r.outline[i] *= kr;
        }
        for (Shot s : shots) { s.x *= sx; s.y *= sy; s.px *= sx; s.py *= sy; }
        faceX *= sx;
        faceY *= sy;
        flash.x *= kr;
        flash.y *= kr;
        for (Particle p : particles) { p.x *= sx; p.y *= sy; }
        for (Ring g : rings) { g.x *= sx; g.y *= sy; }
        for (Star s : stars) { s.x *= sx; s.y *= sy; }
        computeWanderBand();
        wanderX *= sx;
        wanderY = clamp(wanderY * sy, bandLo, bandHi);
        if (wave != null) { wave.x *= sx; wave.y *= sy; wave.radius *= kr; wave.maxRadius = hypot(w, h); }
        retargetStars();
    }

    /** Wander band: the largest vertical gap between the widget zones, inset by 15k; falls back to 20-80% of the height. */
    private void computeWanderBand() {
        java.util.List<float[]> zs = new ArrayList<float[]>(Zones.defaults(w, h));
        java.util.Collections.sort(zs, new java.util.Comparator<float[]>() {
            @Override public int compare(float[] a, float[] b) { return Float.compare(a[1], b[1]); }
        });
        float cursor = 0, bestLo = 0, bestHi = 0;
        for (float[] z : zs) {
            if (z[1] - cursor > bestHi - bestLo) { bestLo = cursor; bestHi = z[1]; }
            cursor = Math.max(cursor, z[1] + z[3]);
        }
        if (h - cursor > bestHi - bestLo) { bestLo = cursor; bestHi = h; }
        float inset = 15f * cfg.k;
        bandLo = bestLo + inset;
        bandHi = bestHi - inset;
        if (bandHi - bandLo < 20f * cfg.k) { bandLo = 0.2f * h; bandHi = 0.8f * h; }
    }

    /** The flight computer starts over: nothing to correct, holding the current heading, engines idle. */
    private void resetFlight() {
        angAccel = thrustCmd = desVx = desVy = headingErr = 0f;
        holdAngle = ship.angle;
        pointing = burning = evading = false;
        java.util.Arrays.fill(nozzleThrust, 0f);
        rcsFirings = 0;
    }

    /** {lo, hi} y range of wander targets (tests). */
    float[] wanderBand() { return new float[] {bandLo, bandHi}; }

    float wanderTargetX() { return wanderX; }

    float wanderTargetY() { return wanderY; }

    /** The autopilot's last heading error (wanted minus actual, -PI..PI) (tests). */
    float headingError() { return headingErr; }

    /** The autopilot's last main-engine command, 0..1 (tests). */
    float engineCommand() { return thrustCmd; }

    /** True while a rock about to hit has the autopilot dodging instead of aiming (tests). */
    boolean evading() { return evading; }

    /** True while the ship is turning to, or holding, a facing target (tests). */
    boolean facingActive() { return facing; }

    /** The facing target: where a rock target is now, or the tapped point. */
    float aimTargetX() { return faceRock != null ? faceRock.x : faceX; }

    float aimTargetY() { return faceRock != null ? faceRock.y : faceY; }

    /** Pins the wander target (tests). */
    void setWanderTargetForTest(float x, float y) { wanderX = x; wanderY = y; wanderT = 1e9f; }

    /** Adds or trims stars so the field matches the star count for the current size. */
    private void retargetStars() {
        int n = GameConfig.starCount(w, h);
        while (stars.size() > n) stars.remove(stars.size() - 1);
        while (stars.size() < n) {
            Star s = new Star();
            s.x = (float) rng.next() * w;
            s.y = (float) rng.next() * h;
            s.alpha = (float) fx.range(0.12, 0.55);
            s.period = fx.next() < 0.5 ? 2f : 4f;
            s.bright = fx.next() < 0.1;
            s.phase = (float) rng.range(0, TAU);
            s.speed = (float) rng.range(0.3, 1.2);
            s.depth = (float) rng.range(0.2, 1.0);
            stars.add(s);
        }
    }

    /**
     * A tap: fires one bolt at once from the next gun along the heading (unless the fire cooldown is running) and
     * makes (x, y) the facing target, replacing any earlier one; false during the intro. The ship turns to face it
     * and holds the facing until within cfg.faceCone for cfg.faceHold seconds, or cfg.faceTimeout at most.
     */
    public boolean aim(float x, float y) {
        if (!isLive()) return false;
        faceX = x;
        faceY = y;
        setFacing(null);
        tapFire = fireT <= 0;
        return true;
    }

    private void setFacing(Rock r) {
        facing = true;
        faceRock = r;
        faceClock = 0f;
        faceSettled = 0f;
    }

    /** Fires a pending tap's bolt, runs auto-fire's cadence and ends the facing hold. */
    private void aimAndFire(float dt) {
        Ship s = ship;
        if (tapFire && fireT <= 0) shoot(faceX, faceY);
        tapFire = false;
        if (autoFire) {
            autoT -= dt;
            if (autoT <= 0) {
                Rock r = nearestRock();
                if (r != null) {
                    if (!facing) setFacing(r);
                    if (fireT <= 0) shoot(r.x, r.y);
                    autoT = cfg.autoFireEvery;
                }
            }
        }
        if (!facing) return;
        if (faceRock != null && !rocks.contains(faceRock)) {
            endFacing();
            return;
        }
        faceClock += dt;
        float bearing = (float) Math.atan2(aimTargetY() - s.y, aimTargetX() - s.x);
        if (Math.abs(wrapAngle(bearing - s.angle)) <= cfg.faceCone) faceSettled += dt;
        else faceSettled = 0f;
        if (faceSettled >= cfg.faceHold || faceClock > cfg.faceTimeout) endFacing();
    }

    /** The hold is over: the autopilot takes over, keeping the heading it has. */
    private void endFacing() {
        facing = false;
        faceRock = null;
        faceClock = 0f;
        faceSettled = 0f;
        holdAngle = ship.angle;
    }

    private void clearAim() {
        facing = false;
        faceRock = null;
        tapFire = false;
        faceClock = 0f;
        faceSettled = 0f;
    }

    /**
     * One bolt from the next gun (they alternate) along the heading, toed in toward (tx, ty) from its muzzle only
     * when that bearing is within cfg.gunCone of the heading. The muzzle flash shows at that gun.
     */
    private void shoot(float tx, float ty) {
        fireT = cfg.fireCooldown;
        Ship s = ship;
        float k = cfg.k, c = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle);
        float lx = ShipShape.GUN_X * k, ly = ShipShape.gunY(gun) * k;
        gun = (gun + 1) % ShipShape.GUNS;
        float mx = s.x + lx * c - ly * sn, my = s.y + lx * sn + ly * c;
        float off = wrapAngle((float) Math.atan2(ty - my, tx - mx) - s.angle);
        if (Math.abs(off) > cfg.gunCone) off = 0f;
        float a = wrapAngle(s.angle + off);
        flash.x = lx;
        flash.y = ly;
        flash.angle = off;
        flash.t = cfg.muzzleFlash;
        if (shots.size() < MAX_SHOTS) {
            Shot b = shotPool.isEmpty() ? new Shot() : shotPool.remove(shotPool.size() - 1);
            b.x = b.px = mx;
            b.y = b.py = my;
            b.vx = (float) Math.cos(a) * cfg.shotSpeed;
            b.vy = (float) Math.sin(a) * cfg.shotSpeed;
            b.angle = a;
            b.t = b.trav = b.hit = 0f;
            b.life = cfg.shotLife;
            shots.add(b);
        }
    }

    private void clearShots() {
        for (int i = shots.size() - 1; i >= 0; i--) shotPool.add(shots.remove(i));
    }

    Rock addRock(int size, float x, float y, float vx, float vy) {
        Rock r = newRock(size, null, false);
        r.x = x;
        r.y = y;
        r.vx = vx;
        r.vy = vy;
        rocks.add(r);
        return r;
    }

    /** Glints (a star every 3-6 s) and the shooting star (every 20-60 s once live, one at a time). */
    private void stepStars(float dt, boolean live) {
        if (glintStar != null) {
            glintStar.glint = Math.max(0f, glintStar.glint - dt);
            if (glintStar.glint == 0f) glintStar = null;
        }
        if (glintT < 0f) glintT = (float) fx.range(3, 6);
        glintT -= dt;
        if (glintT <= 0f && !stars.isEmpty()) {
            if (glintStar != null) glintStar.glint = 0f;
            glintStar = stars.get(fx.nextInt(stars.size()));
            glintStar.glint = GLINT_SECONDS;
            glints++;
            glintT = (float) fx.range(3, 6);
        }
        if (meteor.active) {
            meteor.t += dt;
            if (meteor.t >= METEOR_SECONDS) meteor.active = false;
            else {
                float u = meteor.t / METEOR_SECONDS, p = 1f - (1f - u) * (1f - u) * (1f - u);
                meteor.x = meteorX0 + meteor.dx * meteorDist * p;
                meteor.y = meteorY0 + meteor.dy * meteorDist * p;
                meteor.alpha = Math.min(1f, Math.min(meteor.t / 0.1f, (METEOR_SECONDS - meteor.t) / 0.2f));
            }
        }
        if (!live) return;
        if (meteorT < 0f) meteorT = (float) fx.range(METEOR_MIN_GAP, METEOR_MAX_GAP);
        meteorT -= dt;
        if (meteorT <= 0f) {
            boolean ltr = fx.next() < 0.5;
            float ang = (float) Math.toRadians(fx.range(20, 40));
            float run = (float) fx.range(0.4, 0.6) * w;
            meteor.dx = (ltr ? 1f : -1f) * (float) Math.cos(ang);
            meteor.dy = (float) Math.sin(ang);
            meteorDist = run / (float) Math.cos(ang);
            meteorX0 = ltr ? -(float) fx.range(0, 0.1) * w : w + (float) fx.range(0, 0.1) * w;
            meteorY0 = (float) fx.range(0.02, 0.5) * h;
            meteor.x = meteorX0;
            meteor.y = meteorY0;
            meteor.t = 0f;
            meteor.alpha = 0f;
            meteor.active = true;
            meteors++;
            meteorT = (float) fx.range(METEOR_MIN_GAP, METEOR_MAX_GAP);
        }
    }

    public void step(float dt) {
        if (cfg == null) return;
        if (dt > 0.05f) dt = 0.05f;
        if (dt < 0) dt = 0;
        time += dt;
        if (!introHeld) introT += dt;
        if (shockCool > 0) shockCool -= dt;
        laserHits = 0;
        boolean live = isLive();
        Ship s = ship;
        if (live && autopilot) pilot(dt);
        else {
            angAccel = 0f;
            thrustCmd = 0f;
        }
        // Newtonian step: the engines push along the heading only; the RCS couple only changes the spin
        s.thrust += (thrustCmd - s.thrust) * (1f - (float) Math.exp(-dt / 0.07f));
        if (s.thrust < 0.003f) s.thrust = 0f;
        float acc = s.thrust * cfg.thrust;
        s.vx += (float) Math.cos(s.angle) * acc * dt;
        s.vy += (float) Math.sin(s.angle) * acc * dt;
        float spinCap = GameConfig.SPIN_CAP * cfg.maxSpin;
        s.spin = clamp(s.spin + angAccel * dt, -spinCap, spinCap);
        float dr = (float) Math.exp(-cfg.drag * dt);
        s.vx *= dr;
        s.vy *= dr;
        s.spin *= dr;
        float sp = hypot(s.vx, s.vy), cap = GameConfig.SPEED_CAP * cfg.maxSpeed;
        if (sp > cap) {
            s.vx *= cap / sp;
            s.vy *= cap / sp;
        }
        s.angle = wrapAngle(s.angle + s.spin * dt);
        s.x += s.vx * dt;
        s.y += s.vy * dt;
        float m = cfg.edgeMargin;
        if (s.x < m) { s.x = m; s.vx = Math.abs(s.vx) * 0.45f; }
        if (s.x > w - m) { s.x = w - m; s.vx = -Math.abs(s.vx) * 0.45f; }
        if (s.y < m) { s.y = m; s.vy = Math.abs(s.vy) * 0.45f; }
        if (s.y > h - m) { s.y = h - m; s.vy = -Math.abs(s.vy) * 0.45f; }
        for (int i = 0; i < ShipShape.NOZZLES; i++) nozzleThrust[i] = s.thrust;
        rcsFirings = 0;
        if (angAccel != 0f) {
            int dir = angAccel > 0 ? 1 : -1;
            float st = Math.min(1f, Math.abs(angAccel) / cfg.rcsAccel);
            rcsPort[0] = ShipShape.RCS_NOSE;
            rcsSide[0] = -dir;
            rcsPort[1] = ShipShape.RCS_TAIL;
            rcsSide[1] = dir;
            rcsStrength[0] = rcsStrength[1] = st;
            rcsFirings = 2;
        }
        stepStars(dt, live);
        if (fireT > 0) fireT -= dt;
        if (flash.t > 0) flash.t = Math.max(0f, flash.t - dt);
        if (s.hitCooldown > 0) s.hitCooldown -= dt;

        if (live) aimAndFire(dt);

        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            r.x += r.vx * dt;
            r.y += r.vy * dt;
            r.rot += r.vr * dt;
            float e = r.r * 1.3f;
            if (r.x < -e) r.x = w + e; else if (r.x > w + e) r.x = -e;
            if (r.y < -e) r.y = h + e; else if (r.y > h + e) r.y = -e;
        }
        float cull = cfg.shotCull;
        for (int j = 0; j < shots.size(); j++) {
            Shot b = shots.get(j);
            b.t += dt;
            if (b.hit > 0) { // a flare where it hit, then gone
                b.hit -= dt;
                if (b.hit <= 0) b.life = 0;
                continue;
            }
            b.px = b.x;
            b.py = b.y;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.trav += hypot(b.vx, b.vy) * dt;
            b.life -= dt;
            if (b.x < -cull || b.x > w + cull || b.y < -cull || b.y > h + cull) b.life = 0;
            if (b.life <= 0) continue;
            for (int i = 0; i < rocks.size(); i++) {
                Rock r = rocks.get(i);
                float u = segmentHit(b.px, b.py, b.x, b.y, r.x, r.y, r.r * 0.92f);
                if (u < 0) continue;
                b.x = b.px + (b.x - b.px) * u;
                b.y = b.py + (b.y - b.py) * u;
                b.hit = cfg.shotHitFlare;
                if (laserHits < MAX_HITS) {
                    hitX[laserHits] = b.x;
                    hitY[laserHits] = b.y;
                    hitA[laserHits] = b.angle;
                    laserHits++;
                }
                kill(i);
                break;
            }
        }
        for (int i = shots.size() - 1; i >= 0; i--) if (shots.get(i).life <= 0) shotPool.add(shots.remove(i));

        if (live) {
            for (int i = 0; i < rocks.size(); i++) {
                Rock r = rocks.get(i);
                float dx = s.x - r.x, dy = s.y - r.y;
                float d = hypot(dx, dy);
                if (d == 0) d = 1;
                float lim = r.r * 0.82f + cfg.shipRadius;
                if (d < lim) {
                    float nx = dx / d, ny = dy / d;
                    s.x = r.x + nx * (lim + 1);
                    s.y = r.y + ny * (lim + 1);
                    if (s.hitCooldown <= 0) {
                        s.vx += nx * cfg.hitKnock;
                        s.vy += ny * cfg.hitKnock;
                        r.vx -= nx * 20f * cfg.k;
                        r.vy -= ny * 20f * cfg.k;
                        // an off-centre knock also spins the ship; the RCS has to counter-fire to stop it
                        float tq = (float) Math.cos(s.angle) * ny - (float) Math.sin(s.angle) * nx;
                        s.spin += (tq >= 0 ? 1f : -1f) * (float) rng.range(1.3, 2.3);
                        s.hitCooldown = cfg.hitCooldown;
                        Ring g = new Ring();
                        g.x = s.x - nx * 6f * cfg.k;
                        g.y = s.y - ny * 6f * cfg.k;
                        rings.add(g);
                        if (listener != null) listener.onShipHit();
                    }
                }
            }
        }
        if (wave != null) {
            wave.t += dt;
            wave.radius = wave.maxRadius * Math.min(1f, wave.t / cfg.shockDuration);
            boolean again = true;
            while (again) {
                again = false;
                for (int i = 0; i < rocks.size(); i++) {
                    Rock r = rocks.get(i);
                    if (hypot(r.x - wave.x, r.y - wave.y) - r.r < wave.radius) { kill(i); again = true; break; }
                }
            }
            if (wave.t >= cfg.shockDuration) wave = null;
        }
        for (int i = 0; i < rings.size(); i++) rings.get(i).t += dt;
        for (int i = rings.size() - 1; i >= 0; i--) if (rings.get(i).t >= cfg.ringLife) rings.remove(i);
        float fr = (float) Math.exp(-0.8 * dt);
        float gx = tiltX * cfg.gravityAccel * dt, gy = tiltY * cfg.gravityAccel * dt;
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            p.vx += gx;
            p.vy += gy;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= fr;
            p.vy *= fr;
            p.life -= dt;
        }
        for (int i = particles.size() - 1; i >= 0; i--) if (particles.get(i).life <= 0) particles.remove(i);

        spawnT -= dt;
        if (spawnT <= 0 && mass() < cfg.rocksTarget * 0.85f) {
            rocks.add(newRock(3, null, false));
            spawnT = (float) rng.range(1.6, 3.0);
        }
    }

    /**
     * The autopilot, a PD controller. Sets angAccel (RCS) and thrustCmd (main engines, 0..1).
     * 1. Desired velocity: toward the wander target no faster than the ship can still flip and brake in time, away
     * from where nearby rocks will be at closest approach, and in from the screen edges; low-passed over 0.4 s.
     * 2. The velocity error sets the wanted heading, so slowing down points the ship against its motion.
     * 3. RCS: PD on the heading error, the turn rate capped at maxSpin and at what it can still stop from.
     * 4. Main engines only while the nose is within 20 degrees of the wanted heading.
     * While facing a target the wanted heading is its bearing and the engines stay off, unless a rock is about to
     * hit (its path comes within the avoidance clearance in the next 1.6 s): then it flies as above until clear.
     */
    private void pilot(float dt) {
        float k = cfg.k, vmax = cfg.maxSpeed;
        Ship s = ship;
        wanderT -= dt;
        if (wanderT <= 0) {
            wanderX = w * (float) rng.range(0.18, 0.82);
            wanderY = bandLo + (bandHi - bandLo) * (float) rng.next();
            wanderT = (float) rng.range(cfg.autopilotWanderMin, cfg.autopilotWanderMax);
        }
        float dx = wanderX - s.x, dy = wanderY - s.y, d = hypot(dx, dy);
        float vx = 0f, vy = 0f;
        boolean threat = false;
        if (d > 1e-3f) {
            float ab = 0.8f * cfg.thrust, tf = cfg.flipTime, dd = Math.max(0f, d - 10f * k);
            float sp = Math.min(vmax, ab * ((float) Math.sqrt(tf * tf + 2f * dd / ab) - tf));
            vx = dx / d * sp;
            vy = dy / d * sp;
        }
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            float rx = r.x - s.x, ry = r.y - s.y, nowD = hypot(rx, ry);
            if (nowD > r.r + 260f * k) continue;
            float rvx = r.vx - s.vx, rvy = r.vy - s.vy, rv2 = rvx * rvx + rvy * rvy;
            float tc = rv2 > 1e-6f ? clamp(-(rx * rvx + ry * rvy) / rv2, 0f, 1.6f) : 0f;
            float cx = rx + rvx * tc, cy = ry + rvy * tc, cd = hypot(cx, cy);
            float clear = r.r + 40f * k;
            float u = cd < clear ? (1f - cd / clear) * (1f - 0.5f * tc / 1.6f) : 0f;
            if (tc > 0 && cd < clear) threat = true; // closing, and its path comes within the clearance
            float near = 1f - nowD / (r.r + 50f * k);
            if (near > u) {
                u = near;
                cx = rx;
                cy = ry;
                cd = nowD;
            }
            if (u <= 0) continue;
            if (cd < 1e-3f) {
                float rv = (float) Math.sqrt(rv2);
                if (rv == 0) rv = 1;
                cx = -rvy / rv;
                cy = rvx / rv;
                cd = 1;
            }
            vx -= cx / cd * u * vmax * 1.4f;
            vy -= cy / cd * u * vmax * 1.4f;
        }
        float m = 60f * k;
        if (s.x < m) vx += (m - s.x) / m * vmax;
        if (s.x > w - m) vx -= (s.x - w + m) / m * vmax;
        if (s.y < m) vy += (m - s.y) / m * vmax;
        if (s.y > h - m) vy -= (s.y - h + m) / m * vmax;
        float vm = hypot(vx, vy);
        if (vm > vmax) {
            vx *= vmax / vm;
            vy *= vmax / vm;
        }
        float lp = 1f - (float) Math.exp(-dt / 0.4f);
        desVx += (vx - desVx) * lp;
        desVy += (vy - desVy) * lp;

        float ex = desVx - s.vx, ey = desVy - s.vy, em = hypot(ex, ey);
        if (pointing) {
            if (em < 5f * k) {
                pointing = false;
                holdAngle = s.angle;
            }
        } else if (em > 20f * k) {
            pointing = true;
        }
        float want = pointing ? (float) Math.atan2(ey, ex) : holdAngle;
        evading = facing && threat;
        boolean aiming = facing && !threat;
        if (aiming) {
            want = (float) Math.atan2(aimTargetY() - s.y, aimTargetX() - s.x);
            holdAngle = s.angle; // a dodge that starts mid-turn holds this heading, not a stale one
        }

        float ae = wrapAngle(want - s.angle);
        float lim = Math.min(cfg.maxSpin, (float) Math.sqrt(2f * 0.8f * cfg.rcsAccel * Math.abs(ae)));
        float wd = clamp(4f * ae, -lim, lim);
        float al = clamp(8f * (wd - s.spin), -cfg.rcsAccel, cfg.rcsAccel);
        if (Math.abs(ae) < 0.05f && Math.abs(s.spin) < 0.08f) al = 0f;
        angAccel = al;
        headingErr = ae;

        boolean aligned = !aiming && pointing && Math.abs(ae) < MAIN_ENGINE_CONE;
        if (burning) {
            if (!aligned || em < 5f * k) burning = false;
        } else if (aligned && em > 20f * k) {
            burning = true;
        }
        thrustCmd = burning ? clamp(em / (0.25f * cfg.thrust), 0.3f, 1f) * (float) Math.cos(ae) : 0f;
    }

    /** The main engines fire only while the heading is within this of the wanted one (20 degrees). */
    static final float MAIN_ENGINE_CONE = (float) Math.toRadians(20);

    /** Auto-fire: the nearest rock within 0.6 of the short side, or null. */
    private Rock nearestRock() {
        float best = 0.6f * Math.min(w, h);
        Rock target = null;
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            float d = hypot(r.x - ship.x, r.y - ship.y);
            if (d < best) { best = d; target = r; }
        }
        return target;
    }

    private float mass() {
        float m = 0;
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            m += r.size == 3 ? 1f : r.size == 2 ? 0.45f : 0.2f;
        }
        return m;
    }

    private void kill(int i) {
        Rock r = rocks.remove(i);
        float c = (float) Math.cos(r.rot), sn = (float) Math.sin(r.rot);
        float spacing = 4f * cfg.k;
        int nv = r.outline.length / 2;
        outer:
        for (int v = 0; v < nv; v++) {
            int u = (v + 1) % nv;
            float ax = r.outline[2 * v], ay = r.outline[2 * v + 1];
            float bx = r.outline[2 * u], by = r.outline[2 * u + 1];
            int n = Math.max(1, Math.round(hypot(bx - ax, by - ay) / spacing));
            for (int j = 0; j < n; j++) {
                if (particles.size() >= cfg.particleCap) break outer;
                float t = (float) j / n;
                float lx = ax + (bx - ax) * t, ly = ay + (by - ay) * t;
                float dx = lx * c - ly * sn, dy = lx * sn + ly * c;
                float dd = hypot(dx, dy);
                if (dd == 0) dd = 1;
                float out = (float) rng.range(18, 93) * cfg.k;
                Particle p = new Particle();
                p.x = r.x + dx;
                p.y = r.y + dy;
                p.vx = r.vx + dx / dd * out + (float) rng.range(-12, 12) * cfg.k;
                p.vy = r.vy + dy / dd * out + (float) rng.range(-12, 12) * cfg.k;
                p.life = p.life0 = (float) rng.range(0.7, 1.7);
                p.alpha = 1f;
                particles.add(p);
            }
        }
        if (r.size > 1) {
            rocks.add(newRock(r.size - 1, r, false));
            rocks.add(newRock(r.size - 1, r, false));
        }
        if (listener != null) listener.onRockDestroyed();
    }

    private Rock newRock(int size, Rock parent, boolean anywhere) {
        float k = cfg.k;
        Rock r = new Rock();
        r.size = size;
        r.r = cfg.rockRadius * (size == 3 ? 1f : size == 2 ? 0.62f : 0.38f);
        if (parent != null) {
            r.x = parent.x;
            r.y = parent.y;
            float ang = (float) rng.range(0, TAU), sp = (float) rng.range(18, 44) * k;
            r.vx = parent.vx * 0.6f + (float) Math.cos(ang) * sp;
            r.vy = parent.vy * 0.6f + (float) Math.sin(ang) * sp;
        } else if (anywhere) {
            int tries = 0;
            do {
                r.x = (float) rng.next() * w;
                r.y = (float) rng.next() * h;
                tries++;
            } while (tries < 30 && hypot(r.x - ship.x, r.y - ship.y) < 150f * k);
            float ang = (float) rng.range(0, TAU), sp = (float) rng.range(cfg.rockSpeedMin, cfg.rockSpeedMax);
            r.vx = (float) Math.cos(ang) * sp;
            r.vy = (float) Math.sin(ang) * sp;
        } else {
            int side = rng.nextInt(4);
            if (side == 0) { r.x = -r.r; r.y = (float) rng.next() * h; }
            else if (side == 1) { r.x = w + r.r; r.y = (float) rng.next() * h; }
            else if (side == 2) { r.x = (float) rng.next() * w; r.y = -r.r; }
            else { r.x = (float) rng.next() * w; r.y = h + r.r; }
            float tx = w * (float) rng.range(0.25, 0.75), ty = h * (float) rng.range(0.25, 0.75);
            float ang = (float) Math.atan2(ty - r.y, tx - r.x), sp = (float) rng.range(cfg.rockSpeedMin, cfg.rockSpeedMax);
            r.vx = (float) Math.cos(ang) * sp;
            r.vy = (float) Math.sin(ang) * sp;
        }
        int nv = 9 + rng.nextInt(4);
        r.outline = new float[nv * 2];
        for (int i = 0; i < nv; i++) {
            float a = (float) i / nv * TAU;
            float rr = r.r * (float) rng.range(0.72, 1.12);
            r.outline[2 * i] = (float) Math.cos(a) * rr;
            r.outline[2 * i + 1] = (float) Math.sin(a) * rr;
        }
        r.rot = (float) rng.range(0, TAU);
        r.vr = (float) rng.range(-0.25, 0.25);
        return r;
    }

    /**
     * Where segment (x0,y0)-(x1,y1) first enters the circle, as a 0..1 fraction of the segment: 0 if it starts
     * inside, -1 for a miss. Bolts move up to 45 px a step, so hits are swept along the path.
     */
    static float segmentHit(float x0, float y0, float x1, float y1, float cx, float cy, float r) {
        double dx = x1 - x0, dy = y1 - y0, fx = x0 - cx, fy = y0 - cy;
        double c = fx * fx + fy * fy - (double) r * r;
        if (c <= 0) return 0f;
        double a = dx * dx + dy * dy;
        if (a < 1e-9) return -1f;
        double b = 2 * (fx * dx + fy * dy), disc = b * b - 4 * a * c;
        if (disc < 0) return -1f;
        double u = (-b - Math.sqrt(disc)) / (2 * a);
        return u >= 0 && u <= 1 ? (float) u : -1f;
    }

    /** a wrapped into -PI..PI. */
    static float wrapAngle(float a) {
        double t = (a + Math.PI) % (2 * Math.PI);
        if (t < 0) t += 2 * Math.PI;
        return (float) (t - Math.PI);
    }

    private static float hypot(float a, float b) { return (float) Math.sqrt(a * a + b * b); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
