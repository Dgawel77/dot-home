package dev.gawel.dothome.core;

/** Dot sizing for the storm halftone (the mockup's rule). */
public final class Halftone {
    private Halftone() {}

    public static float radius(float intensity, float pitch) {
        if (intensity < 0.08f) return 0f;
        return (float) (pitch * 0.46 * Math.pow(Math.min(1f, intensity), 0.7));
    }
}
