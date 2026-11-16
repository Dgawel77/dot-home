package dev.gawel.dothome.core;

/** Slowly re-centring baseline: output is value minus a baseline that tracks value with time constant tau. */
public final class Recenter {
    private final float tau;
    private float baseline;
    private boolean seeded;

    public Recenter(float tauSeconds) { tau = tauSeconds; }

    public void reset() { seeded = false; }

    /** Returns value - baseline clamped to [-1, 1], then moves the baseline toward value. The first sample seeds it. */
    public float update(float value, float dt) {
        if (!seeded) {
            baseline = value;
            seeded = true;
            return 0f;
        }
        float out = Math.max(-1f, Math.min(1f, value - baseline));
        if (dt > 0) baseline += (value - baseline) * (1f - (float) Math.exp(-dt / tau));
        return out;
    }
}
