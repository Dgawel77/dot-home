package dev.gawel.dothome.test;

import dev.gawel.dothome.core.TiltInput;

public final class TiltInputTest {
    public static void testCalibratedIsZero() {
        TiltInput t = new TiltInput();
        T.check(!t.calibrated(), "not calibrated initially");
        t.calibrate(1.5f, -2f);
        T.check(t.calibrated(), "calibrated");
        float[] v = t.tilt(1.5f, -2f);
        T.eq(0f, v[0], "tx");
        T.eq(0f, v[1], "ty");
    }

    public static void testFullScaleAndDeadzone() {
        TiltInput t = new TiltInput();
        t.calibrate(1f, 1f);
        float[] v = t.tilt(1f - 3.67f, 1f + 3.67f);
        T.near(1.0, v[0], 1e-5, "tx full");
        T.near(1.0, v[1], 1e-5, "ty full");
        v = t.tilt(1.2f, 1.2f);
        T.eq(0f, v[0], "inside deadzone tx");
        T.eq(0f, v[1], "inside deadzone ty");
        v = t.tilt(1f - 100f, 1f - 100f);
        T.eq(1f, v[0], "clamped +");
        T.eq(-1f, v[1], "clamped -");
    }

    public static void testRemapRotations() {
        float[] r = TiltInput.remap(1f, 2f, 0);
        T.check(r[0] == 1f && r[1] == 2f, "rot0");
        r = TiltInput.remap(1f, 2f, 1);
        T.check(r[0] == -2f && r[1] == 1f, "rot1");
        r = TiltInput.remap(1f, 2f, 2);
        T.check(r[0] == -1f && r[1] == -2f, "rot2");
        r = TiltInput.remap(1f, 2f, 3);
        T.check(r[0] == 2f && r[1] == -1f, "rot3");
    }
}
