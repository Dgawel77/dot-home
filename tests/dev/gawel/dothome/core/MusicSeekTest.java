package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class MusicSeekTest {
    private MusicSeekTest() {}

    public static void testZoneFractions() {
        T.eq(20, MusicSeek.ZONES, "zones");
        T.near(0.025, MusicSeek.zoneFraction(0), 1e-6, "first");
        T.near(0.475, MusicSeek.zoneFraction(9), 1e-6, "ninth");
        T.near(0.975, MusicSeek.zoneFraction(19), 1e-6, "last");
    }

    public static void testPositionMathClamps() {
        T.eq(100000L, MusicSeek.positionFor(0.5f, 200000L), "half");
        T.eq(0L, MusicSeek.positionFor(-1f, 200000L), "below");
        T.eq(200000L, MusicSeek.positionFor(2f, 200000L), "above");
        T.eq(0L, MusicSeek.positionFor(0.5f, 0L), "unknown duration");
        T.eq(0L, MusicSeek.positionFor(Float.NaN, 1000L), "nan");
    }

    public static void testCanSeek() {
        T.eq(true, MusicSeek.canSeek(256L | 4L, 1000L), "seek action + duration");
        T.eq(false, MusicSeek.canSeek(4L, 1000L), "no seek action");
        T.eq(false, MusicSeek.canSeek(256L, 0L), "no duration");
        T.eq(false, MusicSeek.canSeek(256L, -1L), "negative duration");
    }
}
