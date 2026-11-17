package dev.gawel.dothome.core;

/**
 * Bass onset detector over Visualizer FFT frames: the spectral flux of the bass band against its own slow average,
 * normalised by a decaying peak, with an instant attack and an exponential release. Not thread-safe by itself.
 */
public final class BeatDetector {
    public static final float BASS_HZ = 150f;
    private static final float AVG_TAU = 0.5f, PEAK_HALF_LIFE = 4f, RELEASE_TAU = 0.15f, PEAK_FLOOR = 1f;

    private boolean started;
    private long lastMs;
    private float avg, peak = PEAK_FLOOR, pulse;
    private long pulseMs;

    /** The count of bins k >= 1 with k * rate / captureSize <= BASS_HZ. */
    public static int bassBins(int captureSize, int samplingRateMilliHz) {
        if (captureSize <= 0 || samplingRateMilliHz <= 0) return 0;
        float binHz = samplingRateMilliHz / 1000f / captureSize;
        return (int) Math.floor(BASS_HZ / binHz);
    }

    /** One frame in Visualizer.getFft layout: [0] DC re, [1] Nyquist re, then (re, im) pairs for k = 1..n/2-1. */
    public void onFft(byte[] fft, int samplingRateMilliHz, long nowMs) {
        int bins = Math.min(bassBins(fft.length, samplingRateMilliHz), fft.length / 2 - 1);
        float e = 0f;
        for (int k = 1; k <= bins; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            e += re * re + im * im;
        }
        float dt = started ? Math.max(0, nowMs - lastMs) / 1000f : 0f;
        if (!started) { avg = e; started = true; pulseMs = nowMs; }
        lastMs = nowMs;
        pulse = decayed(nowMs);
        pulseMs = nowMs;
        float flux = Math.max(0f, e - avg);
        avg += (e - avg) * (1f - (float) Math.exp(-dt / AVG_TAU));
        peak = Math.max(PEAK_FLOOR, Math.max(flux, peak * (float) Math.pow(0.5, dt / PEAK_HALF_LIFE)));
        float v = flux >= PEAK_FLOOR ? Math.min(1f, flux / peak) : 0f;
        if (v > pulse) pulse = v;
    }

    private float decayed(long nowMs) {
        if (pulse <= 0f) return 0f;
        long d = nowMs - pulseMs;
        return d <= 0 ? pulse : pulse * (float) Math.exp(-(d / 1000f) / RELEASE_TAU);
    }

    /** The pulse, 0..1, released up to nowMs. */
    public float level(long nowMs) { return decayed(nowMs); }
}
