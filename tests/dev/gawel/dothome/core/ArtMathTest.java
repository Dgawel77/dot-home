package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class ArtMathTest {
    private ArtMathTest() {}

    public static void testCropSquare() {
        T.eq("[100, 0, 200]", java.util.Arrays.toString(ArtMath.cropSquare(400, 200)), "landscape");
        T.eq("[0, 50, 100]", java.util.Arrays.toString(ArtMath.cropSquare(100, 200)), "portrait");
        T.eq("[0, 0, 64]", java.util.Arrays.toString(ArtMath.cropSquare(64, 64)), "square");
        T.eq("[0, 0, 0]", java.util.Arrays.toString(ArtMath.cropSquare(0, 10)), "empty");
    }

    public static void testSampleSize() {
        T.eq(1, ArtMath.sampleSize(200, 200, 256), "small");
        T.eq(2, ArtMath.sampleSize(600, 600, 256), "600");
        T.eq(2, ArtMath.sampleSize(1200, 1000, 256), "1200x1000");
        T.eq(4, ArtMath.sampleSize(2400, 2000, 256), "2400x2000");
        T.eq(1, ArtMath.sampleSize(0, 0, 256), "empty");
    }

    public static void testKeyDistinguishesFields() {
        T.eq(ArtMath.key("p", "t", "a", "b"), ArtMath.key("p", "t", "a", "b"), "same");
        T.check(!ArtMath.key("p", "t", "a", "b").equals(ArtMath.key("p", "t", "a", "c")), "album");
        T.check(!ArtMath.key("p", "ta", "", "b").equals(ArtMath.key("p", "t", "a", "b")), "no concat clash");
        T.eq(ArtMath.key("p", null, null, null), ArtMath.key("p", "", "", ""), "null as empty");
    }

    public static void testResampleAverages() {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        int[] out = ArtMath.resample(src, 2, 2, 1, 1);
        T.eq(0xFF7F7F7F, out[0], "mean grey");
        T.eq(true, ArtMath.resample(src, 2, 2, 2, 2) == src, "same size untouched");
    }

    public static void testLruKeepsFour() {
        ArtMath.Lru<String, Integer> m = new ArtMath.Lru<>(ArtMath.MAX_TRACKS);
        for (int i = 0; i < 4; i++) m.put("k" + i, i);
        m.get("k0"); // k1 is now the eldest
        m.put("k4", 4);
        T.eq(4, m.size(), "size capped");
        T.eq(false, m.containsKey("k1"), "eldest dropped");
        T.eq(true, m.containsKey("k0"), "recently used kept");
    }
}
