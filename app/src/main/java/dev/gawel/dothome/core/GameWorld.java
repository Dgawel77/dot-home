package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java space-shooter simulation: a self-flying ship, drifting rocks, shots and particles.
 *
 * <p>The ship flies Newtonian (Ship v2, design/mockups/ShipV2.dc.html): it has a heading, a spin and a velocity
 * with near-zero drag, accelerates forward with its main engines, backward with its two nose retros (0.45x the
 * thrust) and turns only by firing its RCS. A PD autopilot picks a desired velocity (wander target, screen edges)
 * and turns toward the heading that velocity error needs, burning the main engines only within 20 degrees of it.
 * To brake it does not flip: while the needed acceleration points back along the goal's bearing it keeps its nose
 * on the goal and slows on the retros, which fire within 30 degrees of straight behind it, alongside any RCS turn.
 * A goal behind it is still a turn (the retros brake while it turns). Defence: the rock soonest on a collision
 * course within 2 s is shot when the turn to its lead-aim bearing, any cooldown left and the bolt's flight fit before
 * it arrives with 0.15 s to spare (in manual mode only within a 40 degree turn, so the tap is still followed); the
 * ship turns to it, engines off, and fires once at the intercept point. Any other rock is dodged only in an
 * emergency: when one is on a collision course within the next 1 s (0.5 s in manual mode) the ship burns sideways
 * for a moment, then flies on. Nothing else about the rocks steers it; a rock it does hit just bumps it.
 *
 * <p>Guns: four (ShipShape), the under-nose twin and the wingtip pair, taking turns in a crossing order. Each bolt
 * leaves its barrel's tip along the barrel's axis (within 1.5 degrees of the heading); each gun's time since its
 * shot drives the renderer's barrel recoil and hull kick, which are drawing only.
 *
 * <p>Tap-to-fly: a tap fires one bolt at once from the next gun along the current heading, toed in toward the tap
 * only when the tap lies within 3 degrees of it (the fire cooldown still applies). The tap point also becomes the
 * waypoint and starts manual mode, until the later of 4 s after the last tap and 2 s after arrival (12 s cap): the
 * same flight computer flies the ship straight to the waypoint instead of a wander target (up to 1.25x the cruise
 * speed, slowing within 120k px of it), dodging only a rock about to hit, and once there holds station facing the
 * way it came. A newer tap retargets at once: the flight computer drops what it was doing for the old point (a turn,
 * a retro brake, a dodge) and flies to the new one. Then the autopilot resumes from the current state with a fresh
 * wander target.
 *
 * <p>The spin barrage (a shake, 30 s cooldown): for 1.6 s the ship spins on the spot, a random way, its spin
 * easing in to 3 rev/s over 0.3 s and out to 0 over the last 0.4 s, with the RCS firing hard on both ramps. The
 * autopilot and manual mode are off and the ship's drift dies under a 2/s drag. While it spins faster than 1 rev/s
 * the guns fire in turn every 0.045 s along the heading, so the bolts spray out in a spiral; they hit like any
 * other bolt. Then manual mode flies on if a tap's 4 s are not yet up (its clock keeps running through the spin),
 * otherwise the autopilot takes over with a fresh wander target, keeping the final heading.
 *
 * <p>Thrusters are reported as per-step events the renderer reads (nozzle thrust levels, the retro level, RCS
 * firings), kept in fixed arrays so a step allocates nothing for them. Angles are screen angles (y down): a
 * growing angle turns clockwise on screen.
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
    public static final class Particle {
        public float x, y, vx, vy, life, life0, alpha;
        /** A milestone shape: the target, the start of the gather, the seconds forming (< 0 when not), and whether it draws L100. */
        public float tx, ty, sx0, sy0;
        public float formT = -1f;
        public boolean lit;
    }
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
    /** At most this many bolts fly at once (a barrage has about 10 in flight on top of any taps'). */
    public static final int MAX_SHOTS = 48;
    private final Flash flash = new Flash();
    // auto-fire's facing target: a rock the heading follows
    private boolean facing;
    private Rock faceRock;
    /** The last tap (its bolt toes in toward it) and the waypoint: the tap kept out of the 60k px edge band. */
    private float tapX, tapY, wpX, wpY;
    /** The bearing from the ship to the waypoint when it was set; once arrived, the ship faces that way. */
    private float wpBearing;
    private boolean arrived;
    /** Seconds spent on the facing target, and seconds spent within cfg.faceCone of it. */
    private float faceClock, faceSettled;
    /** A tap's bolt waiting for the next step (fired along the heading as it is then), and the tap point. */
    private boolean tapFire;
    /** Manual mode is on while > 0 (a tap sets it); the ship flies to the waypoint. See manualAge and arrivedAge. */
    private float manualT;
    /** Seconds since the last tap, and since the ship arrived at the waypoint (-1 until it has). */
    private float manualAge, arrivedAge = -1f;
    /** The gun that fires next: the four take turns in ShipShape's order (nose port, wing starboard, nose starboard, wing port). */
    private int gun;
    /** Seconds since each gun last fired (capped at GUN_IDLE): the renderer's recoil and hull kick phase. */
    private final float[] gunAge = new float[ShipShape.GUNS];
    private static final float GUN_IDLE = 10f;
    // laser hits of the last step, for the renderer's sparks
    private static final int MAX_HITS = 8;
    private final float[] hitX = new float[MAX_HITS], hitY = new float[MAX_HITS], hitA = new float[MAX_HITS];
    private int laserHits;
    private final List<Particle> particles = new ArrayList<Particle>();
    private final List<Ring> rings = new ArrayList<Ring>();
    private final List<Star> stars = new ArrayList<Star>();
    /** Star periods, glints and shooting stars draw from their own seeded stream, so the game's sequence is kept. */
    private final Rng fx;
    /** Weather (snow, rain, wind lean) has its own stream too: it never draws from the game's Rng. */
    private final Rng precipRng;
    private final Precip precip = new Precip();
    /** The charging stream has its own stream too. */
    private final Rng chargeRng;
    /** Where the last kill's debris starts in the particle list (-1: no kill this step) and the kill point. */
    private int lastKillFirst = -1;
    private float killX, killY;
    private final ChargeStream chargeStream = new ChargeStream();
    private boolean charging;
    private int batteryPercent = -1;
    private WeatherFx weather = WeatherFx.NONE;
    private float dayness = Float.NaN;
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
    // velocity, the heading it holds when there is nothing to correct, and its pointing and burning latches
    private float angAccel, thrustCmd, desVx, desVy, holdAngle, headingErr;
    private boolean pointing, burning;
    /**
     * The nose retros: the pilot's command and the level it eases to (0..1), its firing latch, and whether it is
     * braking (the wanted heading keeps the nose on the goal and the retros take the needed acceleration).
     */
    private float retroCmd, retroLvl;
    private boolean retroing, braking;
    /** An emergency dodge: seconds left of it (0 when none) and its sideways direction (a unit vector). */
    private float dodgeT, dodgeX, dodgeY;
    /**
     * Defence: whether this step turns to (or fires at) a threat, its lead-aim bearing, intercept point and the
     * bolt's flight time there, and the bolts it has fired. Each engagement is a rock with a defence bolt in flight
     * at it and the seconds until that bolt counts as a miss; an engaged rock is left out of the threat scan and the
     * dodge.
     */
    private boolean defending;
    private Rock defRock;
    private float defBearing, defX, defY, defFlight;
    private int defenceShots;
    private final Rock[] engRock = new Rock[ENGAGEMENTS];
    private final float[] engT = new float[ENGAGEMENTS];
    /** The next pilot step takes its desired velocity at once, unfiltered (a new waypoint, or a dodge just over). */
    private boolean snap;

    // thruster events of the last step (fixed arrays: nothing is allocated per step)
    private final float[] nozzleThrust = new float[ShipShape.NOZZLES];
    private final int[] rcsPort = new int[2], rcsSide = new int[2];
    private final float[] rcsStrength = new float[2];
    private int rcsFirings;
    /** Seconds into the spin barrage, or -1 when there is none; its spin direction (+1 clockwise) and cooldown. */
    private float barrageT = -1f, barrageCool;
    private int barrageDir;
    /** Barrage time of its next bolt, and the bolts it has fired. */
    private float barrageBoltT;
    private int barrageBolts;
    private float bandLo, bandHi;

    public GameWorld(Rng rng) {
        this.rng = rng;
        long seed = Double.doubleToLongBits(rng.next());
        this.fx = new Rng(seed);
        this.precipRng = new Rng(seed ^ 0x5DEECE66DL);
        this.chargeRng = new Rng(seed ^ 0x2545F4914F6CDD1DL);
        for (int i = 0; i < MAX_SHOTS; i++) shotPool.add(new Shot());
        java.util.Arrays.fill(gunAge, GUN_IDLE);
    }

    public void setListener(Listener l) { listener = l; }
    /** What the sky does now (from the cached weather); null clears it. */
    public void setWeather(WeatherFx f) { weather = f == null ? WeatherFx.NONE : f; }
    public WeatherFx weather() { return weather; }
    /** 0 night .. 1 day from the real sun times; NaN when unknown. */
    public void setDayness(float d) { dayness = d; }
    public float dayness() { return dayness; }
    public Precip precip() { return precip; }
    public ChargeStream chargeStream() { return chargeStream; }
    /** The battery: charging or not, and the percent (-1 = unknown). */
    public void setPower(boolean charging, int percent) { this.charging = charging; batteryPercent = percent; }
    /** True iff the battery is low and not charging: the ship draws dimmer. */
    public boolean shipDim() { return !charging && batteryPercent >= 0 && batteryPercent < cfg.lowBattery; }
    public void setTilt(float tx, float ty) { tiltX = clamp(tx, -1f, 1f); tiltY = clamp(ty, -1f, 1f); }
    /** Off, nothing flies the ship: the engines and RCS stay idle and it coasts (tests). */
    public void setAutopilot(boolean on) { autopilot = on; }

    /**
     * A shake: starts a spin barrage, its direction picked at random; false during the intro (or its hold) or while
     * the cooldown is running. Drops auto-fire's facing and a pending tap bolt; manual mode waits for it to end.
     */
    public boolean barrage() {
        if (!isLive() || barrageCool > 0 || barrageT >= 0f) return false;
        barrageCool = cfg.barrageCooldown;
        barrageT = 0f;
        barrageDir = rng.next() < 0.5 ? 1 : -1;
        // the first bolt goes when the spin-up passes the firing spin, then one every barrageFireEvery
        barrageBoltT = cfg.barrageSpinUp * (float) Math.cbrt(cfg.barrageFireSpin / cfg.barrageSpin);
        barrageBolts = 0;
        tapFire = false;
        endFacing();
        return true;
    }

    /**
     * Counts real time spent while the wallpaper was hidden toward the barrage cooldown; a barrage that would have
     * finished meanwhile is over.
     */
    public void elapseHidden(float secs) {
        if (!(secs > 0)) return;
        if (barrageCool > 0) barrageCool = Math.max(0f, barrageCool - secs);
        if (barrageT >= 0f && barrageT + secs >= cfg.barrageSeconds) endBarrage();
    }

    /** True while a spin barrage runs. */
    public boolean isBarrage() { return barrageT >= 0f; }

    /** The current or last barrage's spin direction: +1 clockwise on screen, -1 counter-clockwise (tests). */
    int barrageDirection() { return barrageDir; }

    /** Bolts the current or last barrage fired (tests). */
    int barrageBolts() { return barrageBolts; }

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

    /** Nose retro level after the last step, 0..1 (both retros fire together). */
    public float retroThrust() { return retroLvl; }

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

    /** Seconds since gun i (ShipShape order) last fired; GUN_IDLE (10) or more when it has been idle. */
    public float gunAge(int i) { return gunAge[i]; }

    /** The muzzle flash of the last bolt; over once t reaches 0. */
    public Flash flash() { return flash; }

    /** Ends the intro now, releasing any hold (a fold, page swipe or visibility flip must not replay it); world time is untouched. */
    public void skipIntro() {
        introHeld = false;
        if (introT < GameConfig.INTRO_SECONDS) introT = GameConfig.INTRO_SECONDS;
    }

    /**
     * Plays the intro from its first frame (the first show after unlock) so the scene starts from one dot: the ship
     * stops where it is and shots, debris, a barrage and hit rings are cleared. Until the intro ends the ship
     * holds still (no autopilot, no collisions) and nothing fires: no taps, no auto-fire, no barrage. Releases a hold.
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
        java.util.Arrays.fill(gunAge, GUN_IDLE);
        laserHits = 0;
        particles.clear();
        rings.clear();
        barrageT = -1f;
    }

    /** Past the intro: the autopilot steers, the ship collides, and taps, auto-fire and the barrage work. */
    private boolean isLive() { return cfg != null && introT >= cfg.introSeconds; }
    public float width() { return w; }
    public float height() { return h; }

    // the fold/unfold burst: start scales and the time since it was armed (idle when burstT >= FoldBurst.SECONDS)
    private float burstSx0 = 1f, burstSy0 = 1f, burstT = FoldBurst.SECONDS;

    /** Scene scale about the centre while a fold/unfold burst runs; 1 when idle. */
    public float burstScaleX() { return FoldBurst.scaleAt(burstSx0, burstT); }
    public float burstScaleY() { return FoldBurst.scaleAt(burstSy0, burstT); }

    public void resize(float nw, float nh) {
        if (!(nw > 0 && nh > 0)) return;
        if (cfg != null && w > 0 && h > 0) {
            float[] b = FoldBurst.startScale(w, h, nw, nh);
            if (b[0] != 1f || b[1] != 1f) {
                burstSx0 = b[0];
                burstSy0 = b[1];
                burstT = 0f;
            }
        }
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
        precip.scale(sx, sy);
        chargeStream.scale(sx, sy);
        tapX *= sx;
        tapY *= sy;
        float band = EDGE_BAND * cfg.k;
        wpX = clamp(wpX * sx, band, w - band);
        wpY = clamp(wpY * sy, band, h - band);
        flash.x *= kr;
        flash.y *= kr;
        for (Particle p : particles) {
            p.x *= sx; p.y *= sy;
            p.tx *= sx; p.ty *= sy; p.sx0 *= sx; p.sy0 *= sy;
        }
        killX *= sx;
        killY *= sy;
        for (Ring g : rings) { g.x *= sx; g.y *= sy; }
        for (Star s : stars) { s.x *= sx; s.y *= sy; }
        computeWanderBand();
        wanderX *= sx;
        wanderY = clamp(wanderY * sy, bandLo, bandHi);
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
        angAccel = thrustCmd = desVx = desVy = headingErr = retroCmd = retroLvl = 0f;
        holdAngle = ship.angle;
        pointing = burning = snap = retroing = braking = false;
        dodgeT = 0f;
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

    /** The autopilot's last retro command, 0..1 (tests). */
    float retroCommand() { return retroCmd; }

    /** True while a rock on a collision course has the ship dodging (tests). */
    boolean evading() { return dodgeT > 0f; }

    /** True when this step the ship turned to, or fired at, a threat it can shoot in time (tests). */
    boolean defending() { return defending; }

    /** Bolts fired by the defence so far (tests). */
    int defenceShots() { return defenceShots; }

    /** True while a recent tap has the ship flying to its waypoint (not during a barrage, which suspends it). */
    public boolean isManual() { return manualT > 0f && barrageT < 0f; }

    /** The waypoint the last tap set (tests). */
    float waypointX() { return wpX; }

    float waypointY() { return wpY; }

    /** True once the ship has reached the waypoint and is holding station there (tests). */
    boolean arrived() { return arrived; }

    /** True while auto-fire has the ship turning to, or holding, a rock (tests). */
    boolean facingActive() { return facing; }

    /** Auto-fire's facing target: where the rock is now (the waypoint when there is none). */
    float aimTargetX() { return faceRock != null ? faceRock.x : wpX; }

    float aimTargetY() { return faceRock != null ? faceRock.y : wpY; }

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
     * makes (x, y), kept out of the edge band, the waypoint, replacing any earlier one; manual mode runs until
     * cfg.manualHoldSeconds after it. False during the intro or a barrage (ignored).
     */
    public boolean aim(float x, float y) {
        if (!isLive() || barrageT >= 0f) return false;
        tapX = x;
        tapY = y;
        float band = EDGE_BAND * cfg.k;
        wpX = clamp(x, band, w - band);
        wpY = clamp(y, band, h - band);
        wpBearing = (float) Math.atan2(wpY - ship.y, wpX - ship.x);
        arrived = false;
        endFacing();
        // retarget at once: the desired velocity (and the turn, burn or retro braking it drives) and any dodge were for the old goal
        snap = true;
        dodgeT = 0f;
        manualT = cfg.manualHoldSeconds;
        manualAge = 0f;
        arrivedAge = -1f;
        tapFire = fireT <= 0;
        return true;
    }

    /** The autopilot keeps out of this band along the screen edges, and waypoints sit inside it, mockup px. */
    private static final float EDGE_BAND = 60f;

    private void setFacing(Rock r) {
        facing = true;
        faceRock = r;
        faceClock = 0f;
        faceSettled = 0f;
    }

    /** Fires a pending tap's bolt, runs auto-fire's cadence (it fires but does not steer in manual mode) and ends its facing hold. */
    private void aimAndFire(float dt) {
        Ship s = ship;
        if (tapFire && fireT <= 0) shoot(tapX, tapY, true);
        tapFire = false;
        if (autoFire) {
            autoT -= dt;
            if (autoT <= 0) {
                Rock r = nearestRock();
                if (r != null) {
                    if (!facing && manualT <= 0f) setFacing(r);
                    if (fireT <= 0) shoot(r.x, r.y, true);
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
        manualT = 0f;
        arrived = false;
        facing = false;
        faceRock = null;
        tapFire = false;
        faceClock = 0f;
        faceSettled = 0f;
    }

    /**
     * One bolt from the next of the four guns, leaving its barrel's tip (where the barrel is now, its recoil from an
     * earlier shot included) along the barrel's axis: the heading, or 1.5 degrees in for a wingtip gun. With toeIn
     * it flies at (tx, ty) instead when that bearing from the muzzle is within cfg.gunCone of the heading. The muzzle
     * flash shows at the tip, and the barrel's recoil starts over. False when MAX_SHOTS are already flying (the flash
     * still shows).
     */
    private boolean shoot(float tx, float ty, boolean toeIn) {
        fireT = cfg.fireCooldown;
        Ship s = ship;
        int g = gun;
        gun = (gun + 1) % ShipShape.GUNS;
        float k = cfg.k, c = (float) Math.cos(s.angle), sn = (float) Math.sin(s.angle), ga = ShipShape.gunAngle(g);
        float reach = ShipShape.muzzleReach(g) - ShipShape.recoil(gunAge[g]) * ShipShape.recoilStroke(g);
        float lx = (ShipShape.breechX(g) + (float) Math.cos(ga) * reach) * k;
        float ly = (ShipShape.breechY(g) + (float) Math.sin(ga) * reach) * k;
        gunAge[g] = 0f;
        float mx = s.x + lx * c - ly * sn, my = s.y + lx * sn + ly * c;
        float off = ga, a = ga == 0f ? s.angle : wrapAngle(s.angle + ga);
        if (toeIn) {
            float to = wrapAngle((float) Math.atan2(ty - my, tx - mx) - s.angle);
            if (Math.abs(to) <= cfg.gunCone) {
                off = to;
                a = wrapAngle(s.angle + to);
            }
        }
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
            return true;
        }
        return false;
    }

    /** The barrage's spin rate t seconds in, rad/s: a cubic ease-in to cfg.barrageSpin, held, a cubic ease-out to 0. */
    private float barrageSpinAt(float t) {
        GameConfig c = cfg;
        if (t <= 0f || t >= c.barrageSeconds) return 0f;
        if (t < c.barrageSpinUp) {
            float u = t / c.barrageSpinUp;
            return c.barrageSpin * u * u * u;
        }
        float down = c.barrageSeconds - c.barrageSpinDown;
        if (t <= down) return c.barrageSpin;
        float v = 1f - (t - down) / c.barrageSpinDown;
        return c.barrageSpin * v * v * v;
    }

    /** Fires the barrage's bolts that are due, while the ship spins faster than cfg.barrageFireSpin. */
    private void barrageFire() {
        while (barrageT >= barrageBoltT && Math.abs(ship.spin) > cfg.barrageFireSpin) {
            if (shoot(0f, 0f, false)) barrageBolts++;
            barrageBoltT += cfg.barrageFireEvery;
        }
    }

    /**
     * The barrage is over: the flight computer takes over from the current state, holding the heading, flying to
     * the waypoint again if manual mode's time is not up, otherwise to a fresh wander target.
     */
    private void endBarrage() {
        barrageT = -1f;
        ship.spin = 0f;
        resetFlight();
        desVx = ship.vx;
        desVy = ship.vy;
        wanderT = 0f;
        arrived = false;
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
        if (burstT < FoldBurst.SECONDS) burstT += dt;
        for (int i = 0; i < ShipShape.GUNS; i++) gunAge[i] = Math.min(GUN_IDLE, gunAge[i] + dt);
        if (!introHeld) introT += dt;
        if (barrageCool > 0) barrageCool -= dt;
        laserHits = 0;
        lastKillFirst = -1;
        boolean live = isLive();
        boolean spinning = barrageT >= 0f; // a barrage: the spin is scripted, the autopilot and manual mode wait
        if (spinning) barrageT += dt;
        Ship s = ship;
        if (live && manualT > 0f) { // manual mode's clock runs through a barrage too
            manualAge += dt;
            if (arrived) arrivedAge = arrivedAge < 0f ? 0f : arrivedAge + dt;
            else arrivedAge = -1f;
            boolean held = manualAge < cfg.manualHoldSeconds || (arrivedAge >= 0f && arrivedAge < cfg.manualArrivedSeconds);
            if (manualAge >= cfg.manualMaxSeconds || !held && arrivedAge >= 0f) manualT = 0f;
            if (manualT <= 0f) { // the autopilot resumes from the current state, with a fresh wander target
                manualT = 0f;
                arrived = false;
                wanderT = 0f;
                holdAngle = s.angle;
            }
        }
        ageEngagements(dt);
        if (live && autopilot && !spinning) pilot(dt);
        else {
            defending = false;
            defRock = null;
            angAccel = 0f;
            thrustCmd = 0f;
            retroCmd = 0f;
        }
        // Newtonian step: the main engines push along the heading, the nose retros against it; the RCS couple
        // only changes the spin
        float ease = 1f - (float) Math.exp(-dt / 0.07f);
        s.thrust += (thrustCmd - s.thrust) * ease;
        if (s.thrust < 0.003f) s.thrust = 0f;
        retroLvl += (retroCmd - retroLvl) * ease;
        if (retroLvl < 0.003f) retroLvl = 0f;
        float acc = s.thrust * cfg.thrust - retroLvl * cfg.retroThrust;
        s.vx += (float) Math.cos(s.angle) * acc * dt;
        s.vy += (float) Math.sin(s.angle) * acc * dt;
        float dr = (float) Math.exp(-cfg.drag * dt), dv = dr;
        if (spinning) {
            s.spin = barrageDir * barrageSpinAt(barrageT);
            dv = (float) Math.exp(-cfg.barrageDrag * dt);
        } else {
            float spinCap = GameConfig.SPIN_CAP * cfg.maxSpin;
            s.spin = clamp(s.spin + angAccel * dt, -spinCap, spinCap) * dr;
        }
        s.vx *= dv;
        s.vy *= dv;
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
        if (spinning) { // full RCS on both ramps: with the spin while it spins up, against it while it spins down
            if (barrageT < cfg.barrageSpinUp) fireRcs(barrageDir, 1f);
            else if (barrageT > cfg.barrageSeconds - cfg.barrageSpinDown && barrageT < cfg.barrageSeconds) fireRcs(-barrageDir, 1f);
        } else if (angAccel != 0f) {
            fireRcs(angAccel > 0 ? 1 : -1, Math.min(1f, Math.abs(angAccel) / cfg.rcsAccel));
        }
        stepStars(dt, live);
        if (fireT > 0) fireT -= dt;
        if (flash.t > 0) flash.t = Math.max(0f, flash.t - dt);
        if (s.hitCooldown > 0) s.hitCooldown -= dt;

        if (spinning) {
            if (live) barrageFire();
            if (barrageT >= cfg.barrageSeconds) endBarrage();
        } else if (live) {
            aimAndFire(dt);
            defenceFire();
        }

        float windX = weather.windX, windY = weather.windY;
        precip.step(dt, weather, tiltX, tiltY, w, h, cfg, precipRng);
        chargeStream.step(dt, charging, s.x, s.y, w, h, cfg, chargeRng);
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            r.x += (r.vx + windX * cfg.windRockBias) * dt;
            r.y += (r.vy + windY * cfg.windRockBias) * dt;
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
                float lim = hitRadius(r);
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
        for (int i = 0; i < rings.size(); i++) rings.get(i).t += dt;
        for (int i = rings.size() - 1; i >= 0; i--) if (rings.get(i).t >= cfg.ringLife) rings.remove(i);
        float fr = (float) Math.exp(-0.8 * dt);
        float gx = tiltX * cfg.gravityAccel * dt, gy = tiltY * cfg.gravityAccel * dt;
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            if (p.formT >= 0f) {
                p.formT += dt;
                if (p.formT < cfg.shapeHold) {
                    float u = Math.min(1f, p.formT / cfg.shapeGather), e = 1f - (1f - u) * (1f - u) * (1f - u);
                    p.x = p.sx0 + (p.tx - p.sx0) * e;
                    p.y = p.sy0 + (p.ty - p.sy0) * e;
                    continue;
                }
                float ang = (float) fx.range(0, TAU), rel = (float) fx.range(10, 30) * cfg.k;
                p.formT = -1f;
                p.vx = (float) Math.cos(ang) * rel;
                p.vy = (float) Math.sin(ang) * rel;
                p.life = p.life0 = (float) fx.range(0.7, 1.7);
            }
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
     * The flight computer, a PD controller. Sets angAccel (RCS), thrustCmd (main engines, 0..1) and retroCmd (nose
     * retros, 0..1).
     * 1. Desired velocity: toward the goal (the wander target, or in manual mode the waypoint) no faster than the
     * ship can still brake on its retros in time (cfg.brakeLead of lag), and in from the screen edges; low-passed
     * over 0.4 s (MANUAL_FILTER in manual mode), or taken at once after a new waypoint or a dodge.
     * 2. Emergency avoidance: while a rock is on a collision course (collisionCourse) the desired velocity is the
     * current one plus a sideways burn instead; DODGE_SECONDS after the last such step it flies on to the goal.
     * 3. The velocity error sets the wanted heading: its direction, or, braking (its reverse within BRAKE_ENTER of
     * the goal's bearing, or near the goal of the travel direction, and the nose not already nearer the error), its
     * reverse, so the nose stays on the goal. Braking never flips.
     * 4. RCS: PD on the heading error, the turn rate capped at maxSpin and at what it can still stop from.
     * 5. Main engines only while the nose is within 20 degrees of the velocity error; nose retros only while it is
     * within RETRO_CONE of straight behind and slows the ship, each scaled by cos of the miss.
     * While facing a target the wanted heading is its bearing and the engines stay off, unless it is dodging.
     * Defence (defend), checked before the dodge, overrides that: the wanted heading is the threat's lead-aim
     * bearing, engines off, and it fires once within cfg.gunCone; a dodge of another rock still wins.
     * Manual mode flies up to MANUAL_SPEED x faster with MANUAL_LEAD more braking lead, slows in proportion within
     * ARRIVE_SLOW of the waypoint, dodges on a shorter horizon, and once arrived faces the way it came.
     */
    private void pilot(float dt) {
        float k = cfg.k;
        Ship s = ship;
        boolean manual = manualT > 0f;
        float vmax = manual ? MANUAL_SPEED * cfg.maxSpeed : cfg.maxSpeed;
        float gx, gy;
        if (manual) {
            gx = wpX;
            gy = wpY;
        } else {
            wanderT -= dt;
            if (wanderT <= 0) {
                wanderX = w * (float) rng.range(0.18, 0.82);
                wanderY = bandLo + (bandHi - bandLo) * (float) rng.next();
                wanderT = (float) rng.range(cfg.autopilotWanderMin, cfg.autopilotWanderMax);
            }
            gx = wanderX;
            gy = wanderY;
        }
        float dx = gx - s.x, dy = gy - s.y, d = hypot(dx, dy);
        if (manual && !arrived && d < ARRIVE_NEAR * k && hypot(s.vx, s.vy) < ARRIVE_SPEED * k) arrived = true;
        float vx = 0f, vy = 0f;
        if (d > 1e-3f) {
            // braking is on the retros (no turn): the lead covers the engine and filter lag, manual mode's filter
            // more, so it brakes in time instead of overshooting
            float ab = 0.8f * cfg.retroThrust, tf = cfg.brakeLead + (manual ? MANUAL_LEAD : 0f), dd = Math.max(0f, d - 10f * k);
            float sp = Math.min(vmax, ab * ((float) Math.sqrt(tf * tf + 2f * dd / ab) - tf));
            if (manual) sp = Math.min(sp, vmax * d / (ARRIVE_SLOW * k)); // arrival: ease in, do not overshoot
            vx = dx / d * sp;
            vy = dy / d * sp;
        }
        float m = EDGE_BAND * k, ev = cfg.maxSpeed;
        if (s.x < m) vx += (m - s.x) / m * ev;
        if (s.x > w - m) vx -= (s.x - w + m) / m * ev;
        if (s.y < m) vy += (m - s.y) / m * ev;
        if (s.y > h - m) vy -= (s.y - h + m) / m * ev;
        float vm = hypot(vx, vy);
        if (vm > vmax) {
            vx *= vmax / vm;
            vy *= vmax / vm;
        }
        Rock threat = defend(manual);
        if (collisionCourse(manual ? DODGE_HORIZON_MANUAL : DODGE_HORIZON, threat)) {
            dodgeT = DODGE_SECONDS;
        } else if (dodgeT > 0f) {
            dodgeT -= dt;
            if (dodgeT <= 0f) { // the dodge is over: straight back to the goal, no filter lag
                dodgeT = 0f;
                snap = true;
            }
        }
        if (dodgeT > 0f) { // keep the velocity it has and burn sideways
            desVx = s.vx + dodgeX * DODGE_SPEED * cfg.maxSpeed;
            desVy = s.vy + dodgeY * DODGE_SPEED * cfg.maxSpeed;
        } else {
            float lp = snap ? 1f : 1f - (float) Math.exp(-dt / (manual ? MANUAL_FILTER : 0.4f));
            snap = false;
            desVx += (vx - desVx) * lp;
            desVy += (vy - desVy) * lp;
        }

        float ex = desVx - s.vx, ey = desVy - s.vy, em = hypot(ex, ey);
        if (pointing) {
            if (em < 5f * k) {
                pointing = false;
                holdAngle = s.angle;
            }
        } else if (em > 20f * k) {
            pointing = true;
        }
        // braking: the needed acceleration points back along the goal's bearing (near the goal, along the travel
        // direction), so the nose stays forward and the retros take it; anything else turns to burn the main engines
        float need = (float) Math.atan2(ey, ex), back = wrapAngle(need + (float) Math.PI);
        float ref = d > ARRIVE_NEAR * k ? (float) Math.atan2(dy, dx)
            : hypot(s.vx, s.vy) > ARRIVE_SPEED * k ? (float) Math.atan2(s.vy, s.vx) : s.angle;
        float off = Math.abs(wrapAngle(back - ref)), am = wrapAngle(need - s.angle);
        // a nose already nearer the needed acceleration than its reverse brakes on the main engines instead, and
        // braking means slowing: a slow ship with the goal behind it (no speed to shed) turns to it
        braking = pointing && dodgeT <= 0f && off < (braking ? BRAKE_EXIT : BRAKE_ENTER) && Math.abs(am) > HALF_TURN
            && ex * s.vx + ey * s.vy < 0f;
        float want = pointing ? braking ? back : need : manual && arrived ? wpBearing : holdAngle;
        defending = threat != null && dodgeT <= 0f; // a dodge wins: the defence aim is abandoned this step
        boolean aiming = (facing || defending) && dodgeT <= 0f;
        if (aiming) {
            want = defending ? defBearing : (float) Math.atan2(aimTargetY() - s.y, aimTargetX() - s.x);
            holdAngle = s.angle; // a dodge that starts mid-turn holds this heading, not a stale one
        }
        defRock = defending ? threat : null;

        float ae = wrapAngle(want - s.angle);
        float lim = Math.min(cfg.maxSpin, (float) Math.sqrt(2f * 0.8f * cfg.rcsAccel * Math.abs(ae)));
        float wd = clamp(4f * ae, -lim, lim);
        float al = clamp(8f * (wd - s.spin), -cfg.rcsAccel, cfg.rcsAccel);
        if (Math.abs(ae) < 0.05f && Math.abs(s.spin) < 0.08f) al = 0f;
        angAccel = al;
        headingErr = ae;

        // main engines: forward only, within 20 degrees of the needed acceleration
        boolean aligned = !aiming && pointing && Math.abs(am) < MAIN_ENGINE_CONE;
        if (burning) {
            if (!aligned || em < 5f * k) burning = false;
        } else if (aligned && em > 20f * k) {
            burning = true;
        }
        thrustCmd = burning ? clamp(em / (0.25f * cfg.thrust), 0.3f, 1f) * (float) Math.cos(am) : 0f;
        // nose retros: backward only, within RETRO_CONE of the needed acceleration, alongside any RCS turn, and
        // only to brake (the needed acceleration slows the ship): from rest a goal behind is still a turn and a burn
        float ar = wrapAngle(back - s.angle);
        boolean behind = !aiming && pointing && Math.abs(ar) < GameConfig.RETRO_CONE && ex * s.vx + ey * s.vy < 0f;
        if (retroing) {
            if (!behind || em < 5f * k) retroing = false;
        } else if (behind && em > 20f * k) {
            retroing = true;
        }
        retroCmd = retroing ? clamp(em / (0.25f * cfg.retroThrust), 0.3f, 1f) * (float) Math.cos(ar) : 0f;
    }

    /**
     * True when a rock is on a collision course: with both holding their velocities, it comes within the hit radius
     * plus DODGE_MARGIN of the ship within the next `horizon` seconds. Then (dodgeX, dodgeY) is the way to dodge the
     * soonest such rock: away from where it will pass (on the side a dodge already under way took, or for a rock dead
     * on, the side the nose is already turned to), within DODGE_CONE of square to their relative motion and as near
     * the nose as that allows, so the turn is short and the burn starts soon. Rocks the defence has a bolt in
     * flight at, and `shooting` (the threat it is turning to shoot this step, or null), are left to the defence.
     */
    private boolean collisionCourse(float horizon, Rock shooting) {
        Ship s = ship;
        float margin = DODGE_MARGIN * cfg.k, soonest = horizon;
        Rock threat = null;
        float cx = 0f, cy = 0f, rvx = 0f, rvy = 0f;
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            if (r == shooting || engaged(r)) continue;
            float rx = r.x - s.x, ry = r.y - s.y, ux = r.vx - s.vx, uy = r.vy - s.vy, u2 = ux * ux + uy * uy;
            if (u2 < 1e-6f) continue; // not closing
            float tc = -(rx * ux + ry * uy) / u2; // time of closest approach
            if (tc <= 0f) continue; // moving apart (one already touching is the bump's business)
            float t = Math.min(tc, horizon), px = rx + ux * t, py = ry + uy * t;
            if (hypot(px, py) >= hitRadius(r) + margin || (threat != null && t >= soonest)) continue;
            soonest = t;
            threat = r;
            cx = rx + ux * tc;
            cy = ry + uy * tc;
            rvx = ux;
            rvy = uy;
        }
        if (threat == null) return false;
        float u = hypot(rvx, rvy), nx = -rvy / u, ny = rvx / u; // square to the relative motion
        float side;
        if (dodgeT > 0f) side = nx * dodgeX + ny * dodgeY;
        else if (hypot(cx, cy) > DODGE_DEAD_ON * hitRadius(threat)) side = -(nx * cx + ny * cy);
        else side = nx * (float) Math.cos(s.angle) + ny * (float) Math.sin(s.angle);
        float a = (float) Math.atan2(side < 0f ? -ny : ny, side < 0f ? -nx : nx);
        a += clamp(wrapAngle(s.angle - a), -DODGE_CONE, DODGE_CONE);
        dodgeX = (float) Math.cos(a);
        dodgeY = (float) Math.sin(a);
        return true;
    }

    /** The ship and a rock touch when their centres are closer than this. */
    private float hitRadius(Rock r) { return r.r * 0.82f + cfg.shipRadius; }

    /**
     * Defence's threat scan: the rock not already engaged that is soonest on a collision course within
     * DEFEND_HORIZON, its contact time the first root of |r + u t| = hitRadius + DODGE_MARGIN (r and u relative to
     * the ship, both holding their velocities). Returns it when it can be shot in time, with defBearing and
     * (defX, defY) its lead aim: the turn to that bearing (turnTime, none within cfg.gunCone), any fire cooldown
     * left and the bolt's flight (bolts do not inherit the ship's velocity) plus DEFEND_MARGIN_S come before
     * contact, and in manual mode the turn is at most DEFEND_MANUAL_TURN. Otherwise null: the dodge handles it.
     */
    private Rock defend(boolean manual) {
        Ship s = ship;
        float margin = DODGE_MARGIN * cfg.k, soonest = DEFEND_HORIZON;
        Rock threat = null;
        for (int i = 0; i < rocks.size(); i++) {
            Rock r = rocks.get(i);
            if (engaged(r)) continue;
            float rx = r.x - s.x, ry = r.y - s.y, ux = r.vx - s.vx, uy = r.vy - s.vy, u2 = ux * ux + uy * uy;
            float b = rx * ux + ry * uy;
            if (u2 < 1e-6f || b >= 0f) continue; // not closing
            float reach = hitRadius(r) + margin, c = rx * rx + ry * ry - reach * reach, t;
            if (c <= 0f) t = 0f; // already within reach
            else {
                float disc = b * b - u2 * c;
                if (disc < 0f) continue; // it passes wide
                t = (-b - (float) Math.sqrt(disc)) / u2;
            }
            if (t > soonest || threat != null && t == soonest) continue;
            soonest = t;
            threat = r;
        }
        if (threat == null) return null;
        float px = threat.x - s.x, py = threat.y - s.y;
        float flight = interceptTime(px, py, threat.vx, threat.vy, cfg.shotSpeed);
        if (flight < 0f) return null;
        defX = threat.x + threat.vx * flight;
        defY = threat.y + threat.vy * flight;
        defBearing = (float) Math.atan2(defY - s.y, defX - s.x);
        defFlight = flight;
        float turn = Math.abs(wrapAngle(defBearing - s.angle));
        if (manual && turn > DEFEND_MANUAL_TURN) return null;
        float est = (turn <= cfg.gunCone ? 0f : turnTime(turn, cfg.rcsAccel, cfg.maxSpin)) + Math.max(0f, fireT) + flight;
        return est + DEFEND_MARGIN_S < soonest ? threat : null;
    }

    /**
     * Lead aim: the earliest t > 0 with |p + v t| = speed t (a bolt from the origin at `speed` meets a target at p
     * moving at v), or -1 when it never does.
     */
    private static float interceptTime(float px, float py, float vx, float vy, float speed) {
        float a = vx * vx + vy * vy - speed * speed, b = px * vx + py * vy, c = px * px + py * py;
        if (Math.abs(a) < 1e-6f) return b < 0f ? -c / (2f * b) : -1f;
        float disc = b * b - a * c;
        if (disc < 0f) return -1f;
        float q = (float) Math.sqrt(disc), t1 = (-b - q) / a, t2 = (-b + q) / a;
        float lo = Math.min(t1, t2), hi = Math.max(t1, t2);
        return lo > 0f ? lo : hi > 0f ? hi : -1f;
    }

    /**
     * Seconds to turn through `angle` from rest: bang-bang at 0.8 x rcsAccel, the spin capped at maxSpin.
     */
    static float turnTime(float angle, float rcsAccel, float maxSpin) {
        float a = Math.abs(angle), acc = 0.8f * rcsAccel;
        if (a < maxSpin * maxSpin / acc) return 2f * (float) Math.sqrt(a / acc);
        return a / maxSpin + maxSpin / acc;
    }

    /**
     * Fires the defence's bolt (after the ship has moved this step, so it leaves the barrel where it now is; after a
     * tap's bolt, which goes first): once the heading is within cfg.gunCone of the lead-aim bearing and the cooldown
     * is over, at the intercept point. The rock is then engaged for the bolt's flight plus DEFEND_ENGAGED.
     */
    private void defenceFire() {
        if (defRock == null || fireT > 0f) return;
        Ship s = ship;
        if (Math.abs(wrapAngle((float) Math.atan2(defY - s.y, defX - s.x) - s.angle)) > cfg.gunCone) return;
        int slot = freeEngagement();
        if (slot < 0) return;
        shoot(defX, defY, true);
        defenceShots++;
        engRock[slot] = defRock;
        engT[slot] = defFlight + DEFEND_ENGAGED;
        defRock = null;
    }

    /** True while the defence has a bolt in flight at r. */
    private boolean engaged(Rock r) {
        for (int i = 0; i < ENGAGEMENTS; i++) if (engRock[i] == r) return true;
        return false;
    }

    /** A free engagement slot, or -1 when all are in use (the defence then holds fire). */
    private int freeEngagement() {
        for (int i = 0; i < ENGAGEMENTS; i++) if (engRock[i] == null) return i;
        return -1;
    }

    /** Ends engagements whose bolt has had its time (a miss: the rock is judged again) or whose rock is gone. */
    private void ageEngagements(float dt) {
        for (int i = 0; i < ENGAGEMENTS; i++) {
            if (engRock[i] == null) continue;
            engT[i] -= dt;
            if (engT[i] <= 0f || !rocks.contains(engRock[i])) engRock[i] = null;
        }
    }

    /** RCS event: a nose and a tail jet on opposite sides, turning the ship dir (+1 clockwise) at strength 0..1. */
    private void fireRcs(int dir, float strength) {
        rcsPort[0] = ShipShape.RCS_NOSE;
        rcsSide[0] = -dir;
        rcsPort[1] = ShipShape.RCS_TAIL;
        rcsSide[1] = dir;
        rcsStrength[0] = rcsStrength[1] = strength;
        rcsFirings = 2;
    }

    /** The main engines fire only while the heading is within this of the needed acceleration (20 degrees). */
    static final float MAIN_ENGINE_CONE = (float) Math.toRadians(20);
    /**
     * Braking on the retros: the needed acceleration's reverse is within BRAKE_ENTER of the goal's bearing (and
     * stays braking until it is BRAKE_EXIT off, so it does not swap between facing two ways); further off, the
     * ship turns to burn its main engines.
     */
    static final float BRAKE_ENTER = (float) Math.toRadians(45), BRAKE_EXIT = (float) Math.toRadians(70);
    private static final float HALF_TURN = (float) (Math.PI / 2);
    /** Manual mode's top speed over the autopilot's (calm, but a tap gets a response). */
    static final float MANUAL_SPEED = 1.25f;
    /**
     * Arrival, mockup px: manual mode's desired speed falls in proportion within ARRIVE_SLOW of the waypoint; within
     * ARRIVE_NEAR of it and slower than ARRIVE_SPEED px/s the ship has arrived and holds station.
     */
    static final float ARRIVE_SLOW = 120f, ARRIVE_NEAR = 40f, ARRIVE_SPEED = 20f;
    /** Manual mode's desired-velocity filter, s (the autopilot's is 0.4), and the extra braking lead it allows for, s. */
    static final float MANUAL_FILTER = 0.25f, MANUAL_LEAD = 0.3f;
    /**
     * Emergency avoidance: the ship dodges only a rock that will come within its hit radius plus DODGE_MARGIN (mockup
     * px) within DODGE_HORIZON seconds (DODGE_HORIZON_MANUAL in manual mode, so a tap is obeyed), burning sideways
     * (within DODGE_CONE of square to the rock's relative motion) for DODGE_SECONDS after the last such step, up to
     * DODGE_SPEED x the cruise speed on top of the velocity it has. A rock passing within DODGE_DEAD_ON of its hit
     * radius is dead on: the dodge then goes the way the nose is already turned.
     */
    static final float DODGE_HORIZON = 1f, DODGE_HORIZON_MANUAL = 0.5f, DODGE_MARGIN = 8f;
    static final float DODGE_SECONDS = 0.25f, DODGE_SPEED = 1f, DODGE_DEAD_ON = 0.25f;
    static final float DODGE_CONE = (float) Math.toRadians(45);
    /**
     * Defence: it considers rocks on a collision course within DEFEND_HORIZON s and shoots one when the shot's
     * estimate plus DEFEND_MARGIN_S s comes before contact (in manual mode only within DEFEND_MANUAL_TURN of the
     * heading); a defence bolt's rock stays engaged for its flight plus DEFEND_ENGAGED s, up to ENGAGEMENTS at once.
     */
    static final float DEFEND_HORIZON = 2f, DEFEND_MARGIN_S = 0.15f, DEFEND_ENGAGED = 0.1f;
    static final float DEFEND_MANUAL_TURN = (float) Math.toRadians(40);
    private static final int ENGAGEMENTS = 4;

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
        lastKillFirst = particles.size();
        killX = r.x;
        killY = r.y;
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

    /** Kills a rock by index without a shot (tests). */
    void killForTest(int i) { kill(i); }

    /**
     * A milestone: the debris of this step's kill (plus extras spawned at the kill point, never past the particle
     * cap) gathers into the shape (unit points, width 2) centred at (0.5w, 0.45h), holds, then releases.
     * Uses the fx stream, never the game's Rng.
     */
    public void formShape(float[] unitPts) {
        int n = unitPts.length / 2;
        float sx = killX, sy = killY;
        if (lastKillFirst < 0) { sx = w * 0.5f; sy = h * 0.45f; }
        int first = lastKillFirst < 0 ? particles.size() : lastKillFirst;
        while (particles.size() - first < n && particles.size() < cfg.particleCap) {
            Particle p = new Particle();
            p.x = sx;
            p.y = sy;
            p.life = p.life0 = 1f;
            p.alpha = 1f;
            particles.add(p);
        }
        float half = cfg.shapeWidth * 0.5f;
        for (int i = 0, m = Math.min(n, particles.size() - first); i < m; i++) {
            Particle p = particles.get(first + i);
            p.sx0 = p.x;
            p.sy0 = p.y;
            p.tx = w * 0.5f + unitPts[2 * i] * half;
            p.ty = h * 0.45f + unitPts[2 * i + 1] * half;
            p.formT = 0f;
            p.lit = true;
        }
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
