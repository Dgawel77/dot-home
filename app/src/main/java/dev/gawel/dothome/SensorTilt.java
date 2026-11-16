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
    interface Listener { void onTilt(float tx, float ty); }

    private static final float ALPHA = 0.15f;

    private final Context context;
    private final TiltInput input;
    private final Listener listener;
    private SensorManager manager;
    private boolean running, needCalibrate, needFilterSeed, useAccel;
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
    }

    /** Fully unregisters; safe to call repeatedly. */
    void stop() {
        if (manager != null) manager.unregisterListener(this);
        running = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.values.length < 2) return;
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

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
