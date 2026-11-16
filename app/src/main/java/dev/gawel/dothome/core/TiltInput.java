package dev.gawel.dothome.core;

/** Gravity-sensor helpers: tilt relative to a calibration pose (parallax) and absolute screen-plane gravity (debris). */
public final class TiltInput {
    private static final float FULL_SCALE = 3.67f; // m/s^2, about 22 degrees
    private static final float DEADZONE = 0.08f;

    private float g0x, g0y;
    private boolean calibrated;

    public void calibrate(float gx, float gy) {
        g0x = gx;
        g0y = gy;
        calibrated = true;
    }

    public boolean calibrated() { return calibrated; }

    /** Returns {tx, ty} in [-1, 1]: positive x/y is the edge now lower than at calibration (drives star parallax). */
    public float[] tilt(float gx, float gy) {
        float tx = -(gx - g0x) / FULL_SCALE;
        float ty = (gy - g0y) / FULL_SCALE;
        return new float[] {dz(tx), dz(ty)};
    }

    /**
     * Absolute gravity in the screen plane from display-oriented sensor components (m/s^2), each in [-1, 1].
     * Upright gives gy = 1 (debris pours down the screen); flat on a table is about zero.
     */
    public static float[] absoluteGravity(float gx, float gy) {
        return new float[] {clamp1(-gx / 9.81f), clamp1(gy / 9.81f)};
    }

    private static float clamp1(float v) { return Math.max(-1f, Math.min(1f, v)); }

    private static float dz(float v) {
        float r = Math.abs(v) < DEADZONE ? 0f : (v - Math.signum(v) * DEADZONE) / (1f - DEADZONE);
        return Math.max(-1f, Math.min(1f, r));
    }

    /** Rotates a device-frame vector into display orientation (Surface.ROTATION_*). */
    public static float[] remap(float x, float y, int rotation) {
        switch (rotation) {
            case 1: return new float[] {-y, x};
            case 2: return new float[] {-x, -y};
            case 3: return new float[] {y, -x};
            default: return new float[] {x, y};
        }
    }
}
