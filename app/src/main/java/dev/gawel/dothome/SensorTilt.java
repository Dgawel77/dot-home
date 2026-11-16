package dev.gawel.dothome;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.WindowManager;

import dev.gawel.dothome.core.TiltInput;

/**
 * Turns the gravity vector into ship steering. Registered only between start() and stop().
 * Calibrates on the first sample after every start(), so "level" is how the phone is held right then.
 */
final class SensorTilt implements SensorEventListener {
    interface Listener { void onTilt(float tx, float ty); void onShake(); }

    private static final float ALPHA = 0.15f;
    private static final float G_ALPHA = 0.9f; // gravity low-pass when deriving linear acceleration
    private static final float SHAKE_PEAK = 14f;
    private static final long SHAKE_WINDOW_NS = 600_000_000L, SHAKE_QUIET_NS = 1_500_000_000L;

    private final Context context;
    private final TiltInput input;
    private final Listener listener;
    private SensorManager manager;
    private boolean running, needCalibrate, needFilterSeed, useAccel, haveLinear;
    private float gx, gy, gz;
    private boolean gSeeded, peakAbove;
    private long firstPeakNs, quietUntilNs;
    private int rotation;
    private float fx, fy;

    SensorTilt(Context context, TiltInput input, Listener listener) {
        this.context = context;
        this.input = input;
        this.listener = listener;
    }

    /** Starts using the default display's rotation. */
    @SuppressWarnings("deprecation")
    void start() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm == null ? null : wm.getDefaultDisplay();
        start(d == null ? 0 : d.getRotation());
    }

    /** Starts (or restarts, recalibrating) with the given Surface.ROTATION_* value. */
    void start(int displayRotation) {
        stop();
        if (manager == null) manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) return;
        Sensor s = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        useAccel = false;
        if (s == null) {
            s = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            useAccel = true;
        }
        if (s == null) return;
        rotation = displayRotation;
        needCalibrate = true;
        needFilterSeed = true;
        running = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        Sensor lin = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        haveLinear = lin != null && manager.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME);
        gSeeded = false;
        peakAbove = false;
        firstPeakNs = 0;
        quietUntilNs = 0;
    }

    /** Fully unregisters; safe to call repeatedly. */
    void stop() {
        if (manager != null) manager.unregisterListener(this);
        running = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.values.length < 2) return;
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            detectShake(e.values, e.timestamp);
            return;
        }
        if (useAccel && !haveLinear && e.values.length >= 3 && type == Sensor.TYPE_ACCELEROMETER) {
            float ax = e.values[0], ay = e.values[1], az = e.values[2];
            if (!gSeeded) {
                gx = ax; gy = ay; gz = az;
                gSeeded = true;
            } else {
                gx = G_ALPHA * gx + (1 - G_ALPHA) * ax;
                gy = G_ALPHA * gy + (1 - G_ALPHA) * ay;
                gz = G_ALPHA * gz + (1 - G_ALPHA) * az;
            }
            lx[0] = ax - gx; lx[1] = ay - gy; lx[2] = az - gz;
            detectShake(lx, e.timestamp);
        }
        float x = e.values[0], y = e.values[1];
        if (useAccel) {
            if (needFilterSeed) {
                fx = x;
                fy = y;
                needFilterSeed = false;
            } else {
                fx += ALPHA * (x - fx);
                fy += ALPHA * (y - fy);
            }
            x = fx;
            y = fy;
        }
        float[] g = TiltInput.remap(x, y, rotation);
        if (needCalibrate) {
            input.calibrate(g[0], g[1]);
            needCalibrate = false;
        }
        float[] t = input.tilt(g[0], g[1]);
        listener.onTilt(t[0], t[1]);
    }

    private final float[] lx = new float[3];

    /** Two separate peaks above SHAKE_PEAK within 600 ms fire onShake, then a 1.5 s quiet period. */
    private void detectShake(float[] v, long ns) {
        if (v.length < 3 || ns < quietUntilNs) return;
        float m = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (m <= SHAKE_PEAK) {
            peakAbove = false;
            return;
        }
        if (peakAbove) return; // still inside the same peak
        peakAbove = true;
        if (firstPeakNs != 0 && ns - firstPeakNs <= SHAKE_WINDOW_NS) {
            firstPeakNs = 0;
            quietUntilNs = ns + SHAKE_QUIET_NS;
            listener.onShake();
        } else {
            firstPeakNs = ns;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
