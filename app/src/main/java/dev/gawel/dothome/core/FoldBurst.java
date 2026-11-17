package dev.gawel.dothome.core;

/**
 * The fold/unfold transition. On a cover/main switch the scene is drawn scaled about the centre and eases to
 * full size over SECONDS: an unfold bursts outward from a cover-sized region (with a small overshoot), a fold
 * collapses inward from outside the screen.
 */
public final class FoldBurst {
    public static final float SECONDS = 0.6f;
    private static final float OVERSHOOT = 1.2f;

    private FoldBurst() {}

    /** The per-axis start scale {sx, sy}: {1, 1} unless the cover/main class changes. */
    public static float[] startScale(float ow, float oh, float nw, float nh) {
        if (nw == oh && nh == ow) return new float[] {1f, 1f}; // a pure rotation
        boolean oldCover = Zones.isCoverShape(ow, oh);
        if (oldCover == Zones.isCoverShape(nw, nh)) return new float[] {1f, 1f};
        float rx = ow / nw, ry = oh / nh;
        if (oldCover) return new float[] {Math.min(1f, rx), Math.min(1f, ry)};
        return new float[] {Math.max(1f, rx), Math.max(1f, ry)};
    }

    /** Scale at time t: easeOutBack up from a start below 1, cubic ease-out down from a start above 1. */
    public static float scaleAt(float start, float t) {
        if (t >= SECONDS) return 1f;
        float u = t <= 0f ? 0f : t / SECONDS;
        if (start < 1f) {
            float c3 = OVERSHOOT + 1f, v = u - 1f;
            float e = 1f + c3 * v * v * v + OVERSHOOT * v * v;
            return start + (1f - start) * e;
        }
        float v = 1f - u;
        return 1f + (start - 1f) * v * v * v;
    }
}
