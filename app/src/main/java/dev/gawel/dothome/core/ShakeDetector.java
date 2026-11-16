package dev.gawel.dothome.core;

/** Fires when two separate linear-acceleration peaks above a threshold arrive within a window. */
public final class ShakeDetector {
    public static final float PEAK = 14f;
    public static final long MIN_GAP_NS = 100_000_000L, WINDOW_NS = 600_000_000L, QUIET_NS = 1_500_000_000L;

    private boolean above;
    private long firstPeakNs, quietUntilNs;
    private boolean havePeak;

    public void reset() {
        above = false;
        havePeak = false;
        firstPeakNs = 0;
        quietUntilNs = 0;
    }

    /** Feeds one linear-acceleration sample (m/s^2); true when a shake fires. */
    public boolean onSample(float x, float y, float z, long ns) {
        if (ns < quietUntilNs) return false;
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(m > PEAK)) {
            above = false;
            return false;
        }
        if (above) return false; // still inside the same excursion
        above = true;
        long gap = ns - firstPeakNs;
        if (havePeak && gap < MIN_GAP_NS && gap >= 0) return false; // ringing lobe of the same impulse
        if (havePeak && gap <= WINDOW_NS && gap >= 0) {
            havePeak = false;
            quietUntilNs = ns + QUIET_NS;
            return true;
        }
        havePeak = true;
        firstPeakNs = ns;
        return false;
    }
}
