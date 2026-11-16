package dev.gawel.dothome.core;

/** Tunable constants, scaled by k = sqrt(w*h)/669 so the game feels the same at any size. */
public final class GameConfig {
    public final float k;
    public final float rockRadius, tiltAccel, maxSpeed, shotSpeed, hitKnock, edgeMargin;
    public final float rockSpeedMin, rockSpeedMax;
    public final int rocksTarget;
    public final float damping = 1.5f;
    public final float shotLife = 1.3f;
    public final float fireCooldown = 0.1f;
    public final float hitCooldown = 0.4f;
    public final float ringLife = 0.8f;
    public final int particleCap = 2000;
    public final float introSeconds = 1.2f;
    public final float autoFireEvery = 0.45f;
    public final float autopilotWanderMin = 2f, autopilotWanderMax = 4f;
    public final float shockCooldown = 60f, shockDuration = 0.9f;
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
        tiltAccel = 280f * k;
        gravityAccel = 160f * k;
        maxSpeed = 210f * k;
        shotSpeed = 560f * k;
        hitKnock = 150f * k;
        rockSpeedMin = 10f * k;
        rockSpeedMax = 28f * k;
        edgeMargin = 18f * k;
    }

    public static GameConfig forSize(float w, float h) {
        return new GameConfig(w, h);
    }
}
