package dev.gawel.dothome.core;

/** Sizing of the clock half of the clock widget: "hh:mm" is 25 matrix columns, so the width follows the step. */
public final class ClockLayout {
    private ClockLayout() {}

    public static final int CLOCK_COLS = 25;
    private static final int PAD_DP = 16, MIN_WEATHER_DP = 116, VERT_MARGIN_DP = 28;

    /** Width of the clock half: 16 dp left pad plus 25 columns. 316 at S12, 216 at S8. */
    public static int clockWidthDp(Lattice.Step step) {
        return PAD_DP + CLOCK_COLS * step.dp;
    }

    /**
     * The largest of S12 and S8 that leaves the weather half at least 116 dp and fits 7 rows plus 28 dp vertically;
     * S4 only when neither does (not expected on the target device).
     */
    public static Lattice.Step clockStep(float wDp, float hDp) {
        Lattice.Step[] order = {Lattice.Step.S12, Lattice.Step.S8};
        for (Lattice.Step s : order) {
            if (wDp - clockWidthDp(s) >= MIN_WEATHER_DP && hDp >= 7 * s.dp + VERT_MARGIN_DP) return s;
        }
        return Lattice.Step.S4;
    }
}
