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
    /** The two nose retros' deceleration (they push opposite the heading) at full level, px/s²: 0.45 x thrust. */
    public final float retroThrust;
    /** The retros fire while the needed acceleration is within this of straight behind the ship (30 degrees). */
    public static final float RETRO_CONE = (float) Math.toRadians(30);
    /**
     * The braking planner's lead, s: braking on the retros needs no turn, only the engine and filter lag; the
     * planner allows for this before the retros bite.
     */
    public final float brakeLead = 0.5f;
    /** Ship hit radius against rocks, px. */
    public final float shipRadius;

    /** Laser bolts: seconds of flight, and how long a bolt lingers as a flare where it hit. */
    public final float shotLife = 1.1f, shotHitFlare = 0.06f;
    /** Bolts are dropped this far past the screen edge, px. */
    public final float shotCull;
    /** Cooldown between bolts. */
    public final float fireCooldown = 0.12f;
    /** Auto-fire's facing target is held until the heading is within this of it (3 degrees) for faceHold seconds. */
    public final float faceCone = (float) Math.toRadians(3), faceHold = 0.4f;
    /** A facing target is dropped after this many seconds at most. */
    public final float faceTimeout = 1.5f;
    /** Manual mode (after a tap) lasts this long after the last tap, s; the ship flies to the tap point. */
    public final float manualHoldSeconds = 4f;
    /** Manual mode also lasts this long after the ship arrives at the waypoint, s, and ends this long after the tap at most. */
    public final float manualArrivedSeconds = 2f, manualMaxSeconds = 12f;
    /**
     * A bolt flies along the heading, toed in toward the tap only when the tap is within this of it, radians
     * (3 degrees): the streams then converge on it.
     */
    public final float gunCone = (float) Math.toRadians(3);
    /** The red muzzle flash at the firing point, seconds. */
    public final float muzzleFlash = 0.08f;
    public final float hitCooldown = 0.4f;
    public final float ringLife = 0.8f;
    public final int particleCap = 2000;
    /** The opening dot (the intro) lasts at most this long. */
    public static final float INTRO_SECONDS = 1.2f;
    public final float introSeconds = INTRO_SECONDS;
    public final float autoFireEvery = 0.45f;
    /** A new wander target every 5-8 s: long enough to fly there and brake. */
    public final float autopilotWanderMin = 5f, autopilotWanderMax = 8f;
    /**
     * The spin barrage (a shake): its cooldown and length, s; the spin-up (cubic ease-in) and spin-down (cubic
     * ease-out) at its ends, s; the top spin (3 rev/s) and the spin above which the guns fire (1 rev/s), rad/s; the
     * seconds between bolts (the guns take turns); and the linear drag while it spins, per second.
     */
    public final float barrageCooldown = 30f, barrageSeconds = 1.6f, barrageSpinUp = 0.3f, barrageSpinDown = 0.4f;
    public final float barrageSpin = (float) (6 * Math.PI), barrageFireSpin = (float) (2 * Math.PI);
    public final float barrageFireEvery = 0.045f, barrageDrag = 2f;
    public final float gravityAccel;

    /** Weather: the most drops alive, snow's fall and sideways drift, rain's fall and slant, a rock's lean (all at wind 1), and how much fog dims the stars. */
    public final int precipMax;
    public final float snowFall, snowDrift, rainFall, rainSlant, windRockBias;
    public final float fogStarDim = 0.5f;

    /** Charging: dots spawned per second, their cruise speed toward the ship (px/s), and the battery percent below which the ship dims. */
    public final float chargeRate = 15f;
    public final float chargeSpeed;
    public final int lowBattery = 15;

    /** A milestone shape: seconds to gather, seconds before release (total), and its width, px. */
    public final float shapeGather = 0.6f, shapeHold = 3.0f;
    public final float shapeWidth;

    /** By day the stars thin to this fraction of their count and dim to this fraction of their alpha. */
    public final float dayStarCount = 0.6f, dayStarBright = 0.75f;
    /** Beat pulse: star radius grows by up to this fraction, and alpha rises by up to this, at full pulse. */
    public final float beatScale = 0.3f, beatAlpha = 0.15f;
    /** The night moon's radius, dp (a 40 dp disc). */
    public final float moonRadiusDp = 20f;

    /** The moon's centre in px: clear of the widget zones, a little further in on the cover screen. */
    public static float[] moonCentre(float w, float h) {
        return new float[] {moonCentreX(w, h), moonCentreY(h)};
    }

    /** The two coordinates alone, so the frame loop need not allocate the pair. */
    public static float moonCentreX(float w, float h) { return (Zones.isCover(w, h) ? 0.80f : 0.84f) * w; }
    public static float moonCentreY(float h) { return 0.33f * h; }

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
        precipMax = Math.min(Precip.CAP, starCount(w, h));
        snowFall = 40f * k;
        snowDrift = 30f * k;
        rainFall = 520f * k;
        rainSlant = 160f * k;
        windRockBias = 6f * k;
        chargeSpeed = 260f * k;
        shapeWidth = 0.6f * Math.min(w, h);
        thrust = 160f * k;
        maxSpeed = 140f * k;
        shipRadius = ShipShape.RADIUS * k;
        retroThrust = 0.45f * thrust;
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
