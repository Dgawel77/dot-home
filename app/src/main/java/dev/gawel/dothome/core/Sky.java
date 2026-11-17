package dev.gawel.dothome.core;

/** Day and night from the real sunrise and sunset: 0 (night) .. 1 (day), ramping over 45 min either side. */
public final class Sky {
    private Sky() {}

    public static final long RAMP_MS = 45L * 60 * 1000;
    private static final long DAY = 24L * 60 * 60 * 1000;

    /** Sun times older than this are treated as unknown. */
    public static final long MAX_AGE_MS = 7 * DAY;

    /** NaN when either sun time is unknown (<= 0) or over 7 days old. Old sun times are shifted by whole days. Allocation-free. */
    public static float dayness(long nowMs, long sunriseMs, long sunsetMs) {
        if (sunriseMs <= 0 || sunsetMs <= 0) return Float.NaN;
        if (nowMs - sunriseMs > MAX_AGE_MS || nowMs - sunsetMs > MAX_AGE_MS) return Float.NaN;
        long k = Math.floorDiv(nowMs - sunriseMs, DAY);
        long sr = sunriseMs + k * DAY, ss = sunsetMs + k * DAY;
        if (ss < sr) ss += DAY;
        float up = clamp((float) (nowMs - (sr - RAMP_MS)) / (2f * RAMP_MS));
        float down = clamp((float) ((ss + RAMP_MS) - nowMs) / (2f * RAMP_MS));
        float next = clamp((float) (nowMs - (sr + DAY - RAMP_MS)) / (2f * RAMP_MS));
        return Math.max(Math.min(up, down), next);
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }
}
