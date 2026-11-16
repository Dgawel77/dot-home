package dev.gawel.dothome.core;

/** The 4 dp lattice anchored at the screen origin; steps 4/8/12/16 dp, three dot weights. */
public final class Lattice {
    private Lattice() {}

    public static final float STEP_DP = 4;

    public enum Step {
        S4(4), S8(8), S12(12), S16(16);
        public final int dp;

        Step(int dp) {
            this.dp = dp;
        }
    }

    /** Dot diameter divided by step. */
    public enum Weight {
        LIGHT(0.30f), REGULAR(0.60f), HEAVY(0.85f);
        public final float ratio;

        Weight(float ratio) {
            this.ratio = ratio;
        }
    }

    public static float stepPx(Step s, float density) {
        return s.dp * density;
    }

    /** Dot radius: step x weight / 2. */
    public static float radiusPx(Step s, Weight w, float density) {
        return stepPx(s, density) * w.ratio / 2f;
    }

    /** Nearest multiple of 4 dp. */
    public static float snap(float px, float density) {
        float unit = STEP_DP * density;
        return Math.round(px / unit) * unit;
    }
}
