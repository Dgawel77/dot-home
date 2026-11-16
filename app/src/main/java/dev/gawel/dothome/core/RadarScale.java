package dev.gawel.dothome.core;

/**
 * Maps IEM n0q (NEXRAD base reflectivity composite) tile colours back to a 0..1 storm intensity.
 * PALETTE is a coarse every-5-dBZ subset of the n0q colour ramp (palette index i is (i - 7) * 0.5 - 32 dBZ),
 * transcribed from https://mesonet.agron.iastate.edu/docs/nexrad_mosaic/n0q_ramp.png.
 */
public final class RadarScale {
    /** {rgb, dbz} pairs. */
    public static final int[][] PALETTE = {
        {0x46609F, 5}, {0x5598C2, 10}, {0x67D6D7, 15}, {0x10C815, 20}, {0x0C9811, 25}, {0x096A0B, 30},
        {0xACB105, 35}, {0xFFBF00, 40}, {0xFF8900, 45}, {0xC50000, 50}, {0x770000, 55}, {0xFF9CFF, 60},
        {0xE71FE9, 65}, {0x7100DD, 70}, {0x04BCBF, 75},
    };

    private RadarScale() {}

    /** 0 for transparent (or nearly so), else clamp((dbz - 10) / 50, 0, 1). */
    public static float intensity(int argb) {
        if ((argb >>> 24) < 16) return 0f;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        long best = Long.MAX_VALUE;
        int dbz = 0;
        for (int[] e : PALETTE) {
            long dr = r - ((e[0] >> 16) & 0xFF), dg = g - ((e[0] >> 8) & 0xFF), db = b - (e[0] & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < best) {
                best = d;
                dbz = e[1];
            }
        }
        return Math.max(0f, Math.min(1f, (dbz - 10) / 50f));
    }
}
