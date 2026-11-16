package dev.gawel.dothome;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.WindowManager;

import dev.gawel.dothome.core.Recenter;
import dev.gawel.dothome.core.ShakeDetector;
import dev.gawel.dothome.core.TiltInput;

/**
 * Turns the gravity vector into effect inputs: absolute screen-plane gravity (debris) and a slowly re-centring
 * relative tilt (star parallax). Also detects shakes. Registered only between start() and stop().
 * Calibrates on the first sample after every start(), so parallax is neutral in the pose held right then.
 */
final class SensorTilt implements SensorEventListener {
    interface Listener { void onTilt(float gravX, float gravY, float parX, float parY); void onShake(); }

    private static final float ALPHA = 0.15f;
    private static final float G_ALPHA = 0.9f; // gravity low-pass when deriving linear acceleration

    private final Context context;
    private final TiltInput input;
    private final Listener listener;
    private SensorManager manager;
    private boolean running, needCalibrate, needFilterSeed, useAccel, haveLinear;
    private float gx, gy, gz;
    private boolean gSeeded;
    private int rotation;
    private float fx, fy;
    private long lastNs;
    private final Recenter recX = new Recenter(4f), recY = new Recenter(4f);

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
        haveLinear = running && lin != null && manager.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME);
        gSeeded = false;
        lastNs = 0;
        recX.reset();
        recY.reset();
        shake.reset();
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
        float dt = lastNs == 0 ? 0f : Math.max(0f, Math.min(0.1f, (e.timestamp - lastNs) * 1e-9f));
        lastNs = e.timestamp;
        float[] t = input.tilt(g[0], g[1]);
        float[] ab = TiltInput.absoluteGravity(g[0], g[1]);
        listener.onTilt(ab[0], ab[1], recX.update(t[0], dt), recY.update(t[1], dt));
    }

    private final float[] lx = new float[3];
    private final ShakeDetector shake = new ShakeDetector();

    private void detectShake(float[] v, long ns) {
        if (v.length >= 3 && shake.onSample(v[0], v[1], v[2], ns)) listener.onShake();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
