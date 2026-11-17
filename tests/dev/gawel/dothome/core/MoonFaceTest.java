package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class MoonFaceTest {
    private static final float PI = (float) Math.PI;

    public static void testFullMoonShowsSeasAndCraters() {
        int lit = 0, sea = 0, crater = 0, shadow = 0;
        for (int a = 0; a < 41; a++) {
            for (int b = 0; b < 41; b++) {
                float x = -1f + a * 0.05f, y = -1f + b * 0.05f;
                int c = MoonFace.cell(x, y, 0.5f, 0f);
                if (c == MoonFace.LIT) lit++;
                else if (c == MoonFace.SEA) sea++;
                else if (c == MoonFace.CRATER) crater++;
                else if (c == MoonFace.SHADOW) shadow++;
            }
        }
        T.check(lit > 0 && sea > 0 && crater > 0, "full moon has lit, sea, crater");
        T.eq(0, shadow, "eq");
        T.eq(MoonFace.CRATER, MoonFace.cell(MoonFace.craterX(0), MoonFace.craterY(0), 0.5f, 0f), "eq");
        T.eq(MoonFace.SEA, MoonFace.cell(-0.30f, -0.40f, 0.5f, 0f), "eq");
        // rim points of a full moon are lit too
        T.eq(MoonFace.LIT, MoonFace.cell(-1f, 0f, 0.5f, 0f), "eq");
    }

    public static void testNewMoonIsAllShadow() {
        for (int a = 0; a < 41; a++) {
            for (int b = 0; b < 41; b++) {
                int c = MoonFace.cell(-1f + a * 0.05f, -1f + b * 0.05f, 0f, 0f);
                T.check(c == MoonFace.OUTSIDE || c == MoonFace.SHADOW, "new moon cell " + c);
            }
        }
    }

    public static void testLitSideFacesLimbAngle() {
        T.check(lit(MoonFace.cell(0.6f, 0f, 0.25f, 0f)), "right lit");
        T.eq(MoonFace.SHADOW, MoonFace.cell(-0.6f, 0f, 0.25f, 0f), "eq");
        T.check(lit(MoonFace.cell(0f, 0.6f, 0.25f, PI / 2)), "down lit");
        T.eq(MoonFace.SHADOW, MoonFace.cell(0f, -0.6f, 0.25f, PI / 2), "eq");
        T.check(lit(MoonFace.cell(-0.6f, 0f, 0.75f, PI)), "waning left lit");
    }

    public static void testOutsideDisc() {
        T.eq(MoonFace.OUTSIDE, MoonFace.cell(1.1f, 0f, 0.5f, 0f), "eq");
        T.eq(MoonFace.OUTSIDE, MoonFace.cell(1.3f, 0f, 0.5f, 0f), "eq");
    }

    public static void testHaloSparseAndTimed() {
        int in = 0, halo = 0;
        for (int i = -8; i <= 8; i++) {
            for (int j = -8; j <= 8; j++) {
                float r = (float) Math.hypot(i, j) / 5f;
                int c = MoonFace.haloCell(i, j, r);
                if (r >= 1.25f && r <= 1.35f) {
                    in++;
                    if (c == MoonFace.HALO) {
                        halo++;
                        float p = MoonFace.haloPeriod(i, j), ph = MoonFace.haloPhase(i, j);
                        T.check(p >= 3f && p <= 6f, "period " + p);
                        T.check(ph >= 0f && ph < 2 * PI, "phase " + ph);
                    }
                } else {
                    T.eq(MoonFace.OUTSIDE, c, "eq");
                }
            }
        }
        T.check(in > 0 && halo * 5 >= in && halo * 2 <= in, "halo fraction " + halo + "/" + in);
        for (int i = -8; i <= 8; i++) {
            float p = MoonFace.haloPeriod(i, 3);
            T.check(p >= 3f && p <= 6f, "period " + p);
        }
    }

    public static void testTrackInterpolatesAnglesTheShortWay() {
        MoonTrack t = new MoonTrack();
        T.check(!t.valid(), "starts invalid");
        t.set(1000L, 350f, 10f, 3.0f, 61000L, 10f, 30f, -3.0f);
        T.check(t.valid(), "valid after set");
        float az = t.az(31000L);
        T.check(Math.abs(az) < 1e-3f || Math.abs(az - 360f) < 1e-3f, "az mid " + az);
        T.check(Math.abs(t.alt(31000L) - 20f) < 1e-3f, "alt mid");
        T.check(Math.abs(Math.abs(t.limb(31000L)) - PI) < 1e-3f, "limb mid " + t.limb(31000L));
        T.check(Math.abs(t.az(0L) - 350f) < 1e-3f && Math.abs(t.limb(0L) - 3.0f) < 1e-6f, "clamped before");
        T.check(Math.abs(t.az(99000L) - 10f) < 1e-3f && Math.abs(t.alt(99000L) - 30f) < 1e-6f, "clamped after");
        t.clear();
        T.check(!t.valid(), "cleared");
    }

    private static boolean lit(int c) { return c == MoonFace.LIT || c == MoonFace.SEA || c == MoonFace.CRATER; }
}
