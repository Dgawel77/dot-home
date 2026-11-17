package dev.gawel.dothome;

import android.media.audiofx.Visualizer;
import android.util.Log;

import dev.gawel.dothome.core.BeatDetector;

/**
 * Visualizer glue for the beat pulse: FFT of the output mix (session 0) into a BeatDetector. Callbacks arrive on a
 * binder thread, so the detector is guarded by a lock. Any failure releases the Visualizer, logs once and latches
 * until {@link #clearFailure()} (the Setup switch toggled).
 */
final class BeatPulse {
    private static final String TAG = "DotHome";
    private static volatile boolean failed;

    private final BeatDetector detector = new BeatDetector();
    private Visualizer visualizer;

    /** Lets start() try again; called when the Setup switch is toggled. */
    static void clearFailure() { failed = false; }

    boolean running() { return visualizer != null; }

    /** Starts capture; false on any failure (or a latched earlier one). Idempotent while running. */
    boolean start() {
        if (visualizer != null) return true;
        if (failed) return false;
        Visualizer v = null;
        try {
            v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.max(range[0], Math.min(range[1], 1024));
            if (v.setEnabled(false) != Visualizer.SUCCESS
                    || v.setCaptureSize(size) != Visualizer.SUCCESS
                    || v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate() / 2, false, true)
                        != Visualizer.SUCCESS
                    || v.setEnabled(true) != Visualizer.SUCCESS) {
                return fail(v, "Visualizer returned an error code");
            }
            visualizer = v;
            return true;
        } catch (RuntimeException ex) {
            return fail(v, "Visualizer failed: " + ex);
        }
    }

    private boolean fail(Visualizer v, String why) {
        failed = true;
        release(v);
        visualizer = null;
        Log.w(TAG, "beat pulse disabled: " + why);
        return false;
    }

    private static void release(Visualizer v) {
        if (v == null) return;
        try {
            v.setEnabled(false);
        } catch (RuntimeException ignored) {
            // releasing anyway
        }
        try {
            v.release();
        } catch (RuntimeException ignored) {
            // nothing more to do
        }
    }

    void stop() {
        Visualizer v = visualizer;
        visualizer = null;
        release(v);
    }

    /** The current pulse, 0..1; allocation-free. */
    float level(long nowMs) {
        synchronized (detector) {
            return detector.level(nowMs);
        }
    }

    private final Visualizer.OnDataCaptureListener listener = new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {}

        @Override
        public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (detector) {
                detector.onFft(fft, samplingRate, now);
            }
        }
    };
}
