package dev.gawel.dothome.core;

/** The moon's phase from the clock, and which cell of its disc is lit. */
public final class MoonPhase {
    private MoonPhase() {}

    public static final double SYNODIC_DAYS = 29.530588853;
    /** A known new moon: 2000-01-06T18:14Z. */
    public static final long EPOCH_MS = 947182440000L;

    /** 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    public static float phase(long nowMs) {
        double days = (nowMs - EPOCH_MS) / 86400000.0;
        double p = days / SYNODIC_DAYS;
        return (float) (p - Math.floor(p));
    }

    /** 0 outside the unit disc, 1 unlit, 2 lit; x, y in -1..1, screen y down. Waxing lights the right side. */
    public static int cell(float x, float y, float phase) {
        float r2 = x * x + y * y;
        if (r2 > 1f + 1e-6f) return 0;
        float xt = (float) (Math.sqrt(Math.max(0f, 1f - y * y)) * Math.cos(2 * Math.PI * phase));
        boolean lit = phase < 0.5f ? x > xt + 1e-5f : x < -xt + 1e-5f;
        return lit ? 2 : 1;
    }
}
