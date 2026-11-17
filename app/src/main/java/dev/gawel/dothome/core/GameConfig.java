package dev.gawel.dothome.core;

/** Tunable constants, scaled by k = sqrt(w*h)/669 so the game feels the same at any size. */
public final class GameConfig {
    public final float k;
    public final float rockRadius, shotSpeed, hitKnock, edgeMargin;
    public final float rockSpeedMin, rockSpeedMax;
    public final int rocksTarget;

    // Ship v2 flight (design/mockups/ShipV2.dc.html defaults: thrust 160 px/s², rcsTorque 540 °/s², drag 0.02/s)
    /** Main-engine acceleration along the heading at full thrust, px/s². */
    public final float thrust;
    /** RCS angular acceleration, rad/s² (540 °/s²). */
    public final float rcsAccel = (float) Math.toRadians(540);
    /** The autopilot's turn-rate cap, rad/s (180 °/s); a rock knock can spin the ship past it, up to SPIN_CAP x. */
    public final float maxSpin = (float) Math.PI;
    /** Linear and angular drag, per second: near zero, the ship coasts. */
    public final float drag = 0.02f;
    /** The autopilot's top speed, px/s; a rock knock can push the ship past it, up to SPEED_CAP x. */
    public final float maxSpeed;
    public static final float SPEED_CAP = 1.6f, SPIN_CAP = 1.6f;
    /** Seconds for a 180-degree flip from rest to rest under the turn-rate cap, plus 15%: the braking planner's lead. */
    public final float flipTime;
    /** Ship hit radius against rocks, px. */
    public final float shipRadius;

    /** Laser bolts: seconds of flight, and how long a bolt lingers as a flare where it hit. */
    public final float shotLife = 1.1f, shotHitFlare = 0.06f;
    /** Bolts are dropped this far past the screen edge, px. */
    public final float shotCull;
    /** Cooldown between bolts. */
    public final float fireCooldown = 0.12f;
    /** A bolt fires once the heading is within this of the bearing to the aim target, radians (5 degrees). */
    public final float aimCone = (float) Math.toRadians(5);
    /** An aim target the ship cannot line up within this many seconds is dropped. */
    public final float aimTimeout = 1.2f;
    /** The red muzzle flash at the firing point, seconds. */
    public final float muzzleFlash = 0.08f;
    public final float hitCooldown = 0.4f;
    public final float ringLife = 0.8f;
    public final int particleCap = 2000;
    /** The opening dot (the intro) lasts at most this long. */
    public static final float INTRO_SECONDS = 1.2f;
    public final float introSeconds = INTRO_SECONDS;
    public final float autoFireEvery = 0.45f;
    /** A new wander target every 5-8 s: long enough to fly there, flip and brake. */
    public final float autopilotWanderMin = 5f, autopilotWanderMax = 8f;
    public final float shockCooldown = 60f, shockDuration = 0.8f; // event tempo (0.6-0.8 s)
    public final float gravityAccel;

    /**
     * Device pixels per "mockup unit". The mockup surfaces (620x722 main, 330x810 cover) map to the
     * Fold6's 1856x2160 / 968x2376 at about 2.95 px per unit, so counts defined per mockup area stay at
     * mockup density whatever the pixel size.
     */
    static final float PX_PER_UNIT = 2.95f;

    /** Surface area in mockup units squared. */
    static float mockupArea(float w, float h) {
        return w * h / (PX_PER_UNIT * PX_PER_UNIT);
    }

    /** Background star count for a surface of the given pixel size (mockup density: about 116-194). */
    public static int starCount(float w, float h) {
        return Math.round(mockupArea(w, h) / 2300f);
    }

    private GameConfig(float w, float h) {
        k = (float) Math.sqrt((double) w * h) / 669f;
        rockRadius = 33.5f * k;
        rocksTarget = Math.max(4, Math.min(8, Math.round(mockupArea(w, h) / 64000f)));
        gravityAccel = 160f * k;
        thrust = 160f * k;
        maxSpeed = 140f * k;
        shipRadius = ShipShape.RADIUS * k;
        float flip = rcsAccel * Math.PI >= maxSpin * maxSpin
            ? (float) Math.PI / maxSpin + maxSpin / rcsAccel
            : 2f * (float) Math.sqrt(Math.PI / rcsAccel);
        flipTime = flip * 1.15f;
        shotSpeed = 900f * k;
        shotCull = 40f * k;
        hitKnock = 150f * k;
        rockSpeedMin = 10f * k;
        rockSpeedMax = 28f * k;
        edgeMargin = 18f * k;
    }

    public static GameConfig forSize(float w, float h) {
        return new GameConfig(w, h);
    }
}
