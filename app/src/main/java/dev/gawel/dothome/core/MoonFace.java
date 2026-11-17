package dev.gawel.dothome.core;

/** What each cell of the moon's disc (and its halo ring) looks like. Pure; no allocation. */
public final class MoonFace {
    private MoonFace() {}

    public static final int OUTSIDE = 0, SHADOW = 1, LIT = 2, SEA = 3, CRATER = 4, HALO = 5;
    /** Tycho, Copernicus, Kepler. */
    public static final int CRATERS = 3;

    private static final float HALO_MIN = 1.25f, HALO_MAX = 1.35f, CRATER_R = 0.12f;
    private static final float TWO_PI = (float) (2 * Math.PI);

    // Seas, face fixed upright: cx, cy, rx, ry in units of R (y down, north up).
    private static final float[][] SEAS = {
        {-0.30f, -0.40f, 0.28f, 0.24f}, // Imbrium
        {0.22f, -0.38f, 0.18f, 0.18f},  // Serenitatis
        {0.40f, -0.05f, 0.22f, 0.20f},  // Tranquillitatis
        {-0.62f, 0.00f, 0.25f, 0.45f},  // Procellarum
        {-0.15f, 0.40f, 0.18f, 0.15f},  // Nubium
    };
    private static final float[] CRATER_X = {-0.10f, -0.32f, -0.58f};
    private static final float[] CRATER_Y = {0.75f, -0.08f, -0.05f};

    public static float craterX(int i) { return CRATER_X[i]; }

    public static float craterY(int i) { return CRATER_Y[i]; }

    /**
     * Cell of the disc at (ux, uy) in units of R from the centre (screen axes, y down).
     * limbAngle is the screen direction toward the sun; the lit side faces it.
     * Only r <= 1 is handled; beyond is OUTSIDE (the halo comes from haloCell).
     */
    public static int cell(float ux, float uy, float phase, float limbAngle) {
        if (ux * ux + uy * uy > 1f) return OUTSIDE;
        float c = (float) Math.cos(limbAngle), s = (float) Math.sin(limbAngle);
        float x = ux * c + uy * s;   // rotate by -limbAngle into the sun frame
        float y = -ux * s + uy * c;
        float p = phase < 0.5f ? phase : 1f - phase;
        if (p < 1e-4f) return SHADOW; // new moon: nothing lit, even on the rim
        float term = (float) (Math.sqrt(Math.max(0f, 1f - y * y)) * Math.cos(TWO_PI * p));
        if (!(x > term - 1e-5f)) return SHADOW;
        for (int k = 0; k < CRATERS; k++) {
            float dx = ux - CRATER_X[k], dy = uy - CRATER_Y[k];
            if (dx * dx + dy * dy <= CRATER_R * CRATER_R) return CRATER;
        }
        for (int k = 0; k < SEAS.length; k++) {
            float[] e = SEAS[k];
            float dx = (ux - e[0]) / e[2], dy = (uy - e[1]) / e[3];
            if (dx * dx + dy * dy <= 1f) return SEA;
        }
        return LIT;
    }

    /** HALO for a sparse selection of lattice offsets with r (in R) in the halo band, else OUTSIDE. */
    public static int haloCell(int i, int j, float r) {
        return r >= HALO_MIN && r <= HALO_MAX && haloDot(i, j) ? HALO : OUTSIDE;
    }

    /** Fixed hash on lattice indices picking about 1 in 3. */
    public static boolean haloDot(int i, int j) {
        return (hash(i, j, 0x1234567) >>> 8) % 3 == 0;
    }

    /** Twinkle period in seconds, 3..6. */
    public static float haloPeriod(int i, int j) {
        return 3f + 3f * ((hash(i, j, 0x2545F49) >>> 8) & 0xFFFF) / 65536f;
    }

    /** Twinkle phase offset, 0..2*pi. */
    public static float haloPhase(int i, int j) {
        return TWO_PI * ((hash(i, j, 0x6F4A7C1) >>> 8) & 0xFFFF) / 65536f;
    }

    private static int hash(int i, int j, int salt) {
        int h = salt;
        h ^= i * 0x27D4EB2D;
        h = (h << 13 | h >>> 19) * 0x165667B1;
        h ^= j * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }
}
