package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class MonoIconTest {
    private MonoIconTest() {}

    public static void testMonoLayerKeepsAlpha() {
        int[] out = MonoIcon.fromMonochromeLayer(new int[] {0x80123456, 0x00FFFFFF, 0xFF000000});
        T.eq(0x80FFFFFF, out[0], "half alpha white");
        T.eq(0x00FFFFFF, out[1], "transparent stays transparent");
        T.eq(0xFFFFFFFF, out[2], "opaque black becomes white");
    }

    public static void testFullIconExtractsGlyph() {
        int w = 8, h = 8;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean centre = x >= 3 && x < 5 && y >= 3 && y < 5;
                px[y * w + x] = centre ? 0xFFF0F0F0 : 0xFF202020;
            }
        }
        int[] out = MonoIcon.fromFullIcon(px, w, h);
        T.eq(0xFFFFFFFF, out[3 * w + 3], "centre white");
        T.eq(0, out[0] >>> 24, "corner transparent");
        T.eq(0, out[1 * w + 1] >>> 24, "background transparent");
    }

    public static void testFullIconLightBackgroundDarkGlyph() {
        int w = 4, h = 4;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        px[1 * w + 1] = 0xFF000000;
        int[] out = MonoIcon.fromFullIcon(px, w, h);
        T.eq(0xFFFFFFFF, out[1 * w + 1], "dark glyph becomes white");
        T.eq(0, out[0] >>> 24, "light bg transparent");
    }

    public static void testSmallDifferenceIsTransparent() {
        int[] px = new int[16];
        java.util.Arrays.fill(px, 0xFF808080);
        px[5] = 0xFF8A8A8A;
        int[] out = MonoIcon.fromFullIcon(px, 4, 4);
        T.eq(0, out[5] >>> 24, "below threshold");
    }

    public static void testFillRatio() {
        int[] px = {0xFF000000, 0x80000000, 0x7F000000, 0x00FFFFFF};
        T.near(0.5, MonoIcon.fillRatio(px), 1e-9, "alpha >= 128 counts");
        T.near(0, MonoIcon.fillRatio(new int[0]), 1e-9, "empty");
    }

    public static void testFlattenRemovesShading() {
        int fg = 0xFFDDDDDD;
        int[] out = MonoIcon.flatten(new int[] {0x3C808080, 0x80123456, 0xC8FFFFFF, 0x5A000000, 0xA5000000}, fg);
        T.eq(0x00DDDDDD, out[0], "alpha 60 vanishes");
        T.eq(0x81DDDDDD, out[1], "alpha 128 ramps to 129");
        T.eq(0xFFDDDDDD, out[2], "alpha 200 solid");
        T.eq(0x00DDDDDD, out[3], "alpha 90 is 0");
        T.eq(0xFFDDDDDD, out[4], "alpha 165 is solid");
    }
}
