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

    public static void testToneIsRec709LumaKeepingCoverage() {
        int[] out = MonoIcon.tone(new int[] {
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF, 0x80FFFFFF, 0x00123456, 0x40808080});
        T.eq(0xFF363636, out[0], "red -> 0.2126 * 255 = 54");
        T.eq(0xFFB6B6B6, out[1], "green -> 0.7152 * 255 = 182");
        T.eq(0xFF121212, out[2], "blue -> 0.0722 * 255 = 18");
        T.eq(0xFFFFFFFF, out[3], "white stays white");
        T.eq(0x80FFFFFF, out[4], "alpha (coverage) kept");
        T.eq(0, out[5], "uncovered pixel is transparent");
        T.eq(0x40808080, out[6], "grey keeps its level and coverage");
    }

    public static void testOverBlackMatchesBlackBacking() {
        int[] out = MonoIcon.overBlack(new int[] {0xFF123456, 0x80FFFFFF, 0x00FFFFFF, 0x40FF8000});
        T.eq(0xFF123456, out[0], "opaque unchanged");
        T.eq(0xFF808080, out[1], "half white over black is mid grey");
        T.eq(0xFF000000, out[2], "transparent is black");
        T.eq(0xFF402000, out[3], "quarter orange");
    }

    public static void testExportStatusCounts() {
        String s = MonoIcon.exportStatus(122, 80, 1, 3);
        T.check(s.startsWith("Exported 202 icons (122 mono, 80 auto, 1 failed)"), s);
        T.check(s.contains("3 via loadIcon"), s);
        T.check(!s.contains(MonoIcon.THEME_WARNING), "no warning when mono icons exist: " + s);
        T.check(!MonoIcon.exportStatus(5, 80, 0, 0).contains("via loadIcon"), "no fallback line when 0");
    }

    public static void testExportStatusWarnsWhenAThemeHidesMonoLayers() {
        T.eq("icon theme may be overriding icons — set Theme Park icons to Default and re-run",
            MonoIcon.THEME_WARNING, "exact warning text");
        T.check(MonoIcon.exportStatus(0, 202, 0, 0).contains(MonoIcon.THEME_WARNING), "all auto warns");
        T.check(MonoIcon.exportStatus(0, 21, 0, 0).contains(MonoIcon.THEME_WARNING), "21 icons warns");
        T.check(!MonoIcon.exportStatus(0, 20, 5, 0).contains(MonoIcon.THEME_WARNING), "20 or fewer: no warning");
        T.check(!MonoIcon.exportStatus(1, 201, 0, 0).contains(MonoIcon.THEME_WARNING), "one mono: no warning");
    }
}
