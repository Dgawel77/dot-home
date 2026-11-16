package dev.gawel.dothome.core;

import java.util.SplittableRandom;

/** Deterministic random source: the same seed always yields the same sequence. */
public final class Rng {
    private final SplittableRandom r;

    public Rng(long seed) {
        r = new SplittableRandom(seed);
    }

    /** Uniform in [0, 1). */
    public double next() {
        return r.nextDouble();
    }

    /** Uniform in [lo, hi). */
    public double range(double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    /** Uniform integer in [0, n). */
    public int nextInt(int n) {
        return r.nextInt(n);
    }
}
