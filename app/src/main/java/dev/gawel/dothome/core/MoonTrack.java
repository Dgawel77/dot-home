package dev.gawel.dothome.core;

/** Smooths the moon's az/alt/limb between two sampled endpoints. No allocation. */
public final class MoonTrack {
    private long t0, t1;
    private float az0, alt0, limb0, az1, alt1, limb1;
    private boolean valid;

    public void set(long t0Ms, float az0, float alt0, float limb0, long t1Ms, float az1, float alt1, float limb1) {
        this.t0 = t0Ms; this.az0 = az0; this.alt0 = alt0; this.limb0 = limb0;
        this.t1 = t1Ms; this.az1 = az1; this.alt1 = alt1; this.limb1 = limb1;
        valid = true;
    }

    public boolean valid() { return valid; }

    public void clear() { valid = false; }

    /** Azimuth in degrees, the short way across 0/360. */
    public float az(long nowMs) {
        float a = az0 + wrap(az1 - az0, 360f) * frac(nowMs);
        return a < 0 ? a + 360f : a >= 360f ? a - 360f : a;
    }

    public float alt(long nowMs) { return alt0 + (alt1 - alt0) * frac(nowMs); }

    /** Limb angle in radians, the short way across +-pi. */
    public float limb(long nowMs) {
        return limb0 + wrap(limb1 - limb0, (float) (2 * Math.PI)) * frac(nowMs);
    }

    private float frac(long nowMs) {
        if (nowMs <= t0 || t1 <= t0) return 0f;
        if (nowMs >= t1) return 1f;
        return (float) (nowMs - t0) / (float) (t1 - t0);
    }

    /** d wrapped into [-period/2, period/2]. */
    private static float wrap(float d, float period) {
        while (d > period / 2) d -= period;
        while (d < -period / 2) d += period;
        return d;
    }
}
