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

    private GameConfig(float w, float h) {
        k = (float) Math.sqrt((double) w * h) / 669f;
        rockRadius = 33.5f * k;
        rocksTarget = Math.max(4, Math.min(8, Math.round(w * h / 64000f)));
        tiltAccel = 280f * k;
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
