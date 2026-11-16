package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class ClockLayoutTest {
    private ClockLayoutTest() {}

    public static void testWidths() {
        T.eq(316, ClockLayout.clockWidthDp(Lattice.Step.S12), "S12");
        T.eq(216, ClockLayout.clockWidthDp(Lattice.Step.S8), "S8");
    }

    public static void testStepByWidth() {
        T.eq(Lattice.Step.S4, ClockLayout.clockStep(250, 200), "250");
        T.eq(Lattice.Step.S8, ClockLayout.clockStep(340, 200), "340");
        T.eq(Lattice.Step.S12, ClockLayout.clockStep(470, 200), "470");
        T.eq(Lattice.Step.S12, ClockLayout.clockStep(680, 200), "680");
        T.eq(Lattice.Step.S4, ClockLayout.clockStep(331, 200), "331");
        T.eq(Lattice.Step.S8, ClockLayout.clockStep(332, 200), "332 exact");
        T.eq(Lattice.Step.S12, ClockLayout.clockStep(432, 112), "432 exact");
    }

    public static void testHeightLimited() {
        T.eq(Lattice.Step.S8, ClockLayout.clockStep(680, 100), "tall step does not fit");
        T.eq(Lattice.Step.S4, ClockLayout.clockStep(680, 80), "nothing fits");
    }
}
