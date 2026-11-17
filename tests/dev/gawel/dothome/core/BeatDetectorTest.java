package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class BeatDetectorTest {
    private static final int N = 1024, RATE = 44100000, DT = 20;

    /** A frame with the given amplitude (real part) in each of the listed bins. */
    private static byte[] frame(int amp, int... bins) {
        byte[] f = new byte[N];
        for (int b : bins) f[2 * b] = (byte) amp;
        return f;
    }

    public static void testBassBins() {
        T.eq(3, BeatDetector.bassBins(1024, 44100000), "1024 @ 44.1k");
        T.eq(1, BeatDetector.bassBins(512, 48000000), "512 @ 48k");
    }

    public static void testSilenceIsZero() {
        BeatDetector d = new BeatDetector();
        byte[] f = frame(0);
        for (long t = 0; t < 3000; t += DT) d.onFft(f, RATE, t);
        T.near(0, d.level(3000), 1e-6, "silence");
    }

    public static void testConstantToneFades() {
        BeatDetector d = new BeatDetector();
        byte[] f = frame(100, 1, 2);
        for (long t = 0; t < 3000; t += DT) d.onFft(f, RATE, t);
        T.check(d.level(3000) < 0.1f, "constant tone fades: " + d.level(3000));
    }

    public static void testKicksPulse() {
        BeatDetector d = new BeatDetector();
        byte[] on = frame(100, 1, 2), off = frame(0);
        float max = 0, min = 1;
        for (long t = 0; t < 4000; t += DT) {
            d.onFft(t % 500 < 100 ? on : off, RATE, t);
            if (t >= 2000) {
                float l = d.level(t);
                max = Math.max(max, l);
                min = Math.min(min, l);
            }
        }
        T.check(max > 0.7f, "max " + max);
        T.check(min < 0.2f, "min " + min);
    }

    public static void testTrebleIgnored() {
        BeatDetector d = new BeatDetector();
        byte[] on = frame(100, 40), off = frame(0);
        float max = 0;
        for (long t = 0; t < 4000; t += DT) {
            d.onFft(t % 500 < 100 ? on : off, RATE, t);
            max = Math.max(max, d.level(t));
        }
        T.near(0, max, 1e-6, "treble");
    }

    public static void testLevelDecaysBetweenFrames() {
        BeatDetector d = new BeatDetector();
        byte[] on = frame(100, 1, 2), off = frame(0);
        long t = 0;
        for (; t < 1000; t += DT) d.onFft(off, RATE, t);
        d.onFft(on, RATE, t);
        float a = d.level(t), b = d.level(t + 300);
        T.check(a > 0.5f, "burst level " + a);
        T.check(b < a / 2, "decayed " + b + " vs " + a);
    }
}
