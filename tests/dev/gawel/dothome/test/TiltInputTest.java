package dev.gawel.dothome.test;

import dev.gawel.dothome.core.Recenter;
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

    public static void testAbsoluteGravityIsPlainScreenPlaneComponent() {
        float[] up = TiltInput.absoluteGravity(0f, 9.81f); // held upright: debris pours down the screen
        T.near(0.0, up[0], 1e-5, "upright gx");
        T.near(1.0, up[1], 1e-5, "upright gy");
        float[] flat = TiltInput.absoluteGravity(0.1f, -0.2f); // flat on a table: about zero
        T.check(Math.abs(flat[0]) < 0.03f && Math.abs(flat[1]) < 0.03f, "flat is about zero");
        float[] left = TiltInput.absoluteGravity(9.81f, 0f); // right edge up: debris falls toward the left edge
        T.near(-1.0, left[0], 1e-5, "gx sign");
        float[] big = TiltInput.absoluteGravity(-50f, 50f);
        T.eq(1f, big[0], "clamped x");
        T.eq(1f, big[1], "clamped y");
    }

    public static void testRecenterReturnsToZeroAndClamps() {
        Recenter r = new Recenter(4f);
        T.eq(0f, r.update(0.8f, 0.02f), "first sample seeds the baseline");
        T.near(0.7, r.update(1.5f, 0.0f), 1e-3, "a step shows as a delta before it re-centres");
        T.eq(1f, r.update(9f, 0.0f), "clamped to 1");
        float v = 1f;
        for (int i = 0; i < 100; i++) v = r.update(0.8f, 0.1f); // hold the seed pose
        T.near(0.0, v, 0.02, "holding the pose is about zero");
        for (int i = 0; i < 20; i++) v = r.update(-0.2f, 0.1f); // a new pose, 2 s in
        T.check(Math.abs(v) < 0.8f && Math.abs(v) > 0.05f, "still partly offset after 2 s " + v);
        for (int i = 0; i < 400; i++) v = r.update(-0.2f, 0.1f);
        T.near(0.0, v, 0.01, "new pose settles to zero after 40 s");
    }
}
