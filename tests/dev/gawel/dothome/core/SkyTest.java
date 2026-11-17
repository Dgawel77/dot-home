package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.List;

public final class SkyTest {
    private static final long MIN = 60000L, DAY = 24 * 60 * MIN;
    private static final long MIDNIGHT = 1794700800000L; // 2026-11-15T00:00Z
    private static final long RISE = MIDNIGHT + (12 * 60 + 40) * MIN, SET = MIDNIGHT + (22 * 60 + 29) * MIN;

    private static float d(long now) { return Sky.dayness(now, RISE, SET); }

    public static void testNoonIsDayMidnightIsNight() {
        T.near(1, d(MIDNIGHT + 18 * 60 * MIN), 1e-4, "18:00Z day");
        T.near(0, d(MIDNIGHT + 6 * 60 * MIN), 1e-4, "06:00Z night");
    }

    public static void testRampsAroundSunset() {
        T.near(0.5, d(SET), 0.01, "at sunset");
        T.near(0, d(SET + 45 * MIN), 0.01, "after");
        T.near(1, d(SET - 45 * MIN), 0.01, "before");
        T.near(0.25, d(SET + 22 * MIN + 30000), 0.01, "quarter");
    }

    public static void testRampsAroundSunrise() {
        T.near(0.5, d(RISE), 0.01, "at sunrise");
        T.near(0, d(RISE - 45 * MIN), 0.01, "before sunrise");
    }

    public static void testOldSunTimesShiftByDays() {
        T.near(1, d(MIDNIGHT + 3 * DAY + 18 * 60 * MIN), 1e-4, "3 days later 18:00");
        T.near(0.5, d(RISE + 3 * DAY), 0.01, "3 days later at sunrise");
    }

    public static void testStaleSunTimesGiveNaN() {
        T.check(!Float.isNaN(Sky.dayness(RISE + 7 * DAY, RISE, SET)), "exactly 7 days still known");
        T.check(Float.isNaN(Sky.dayness(RISE + 7 * DAY + 1, RISE, SET)), "just over 7 days unknown");
        T.check(Float.isNaN(Sky.dayness(SET + 30 * DAY, RISE, SET)), "a month old unknown");
    }

    public static void testUnknownSunGivesNaN() {
        T.check(Float.isNaN(Sky.dayness(RISE, 0, SET)), "sunrise 0");
        T.check(Float.isNaN(Sky.dayness(RISE, RISE, 0)), "sunset 0");
    }

    public static void testMoonPhaseKnownDates() {
        T.near(0, MoonPhase.phase(MoonPhase.EPOCH_MS), 0.001, "epoch");
        long half = MoonPhase.EPOCH_MS + Math.round(14.765 * DAY);
        T.near(0.5, MoonPhase.phase(half), 0.001, "half");
        long full = MIDNIGHT + 9 * DAY + (14 * 60 + 53) * MIN; // 2026-11-24T14:53Z
        T.near(0.5, MoonPhase.phase(full), 0.03, "2026 full moon");
    }

    public static void testMoonCells() {
        int litNew = 0;
        boolean allFull = true;
        for (int i = 0; i < 21; i++) {
            for (int j = 0; j < 21; j++) {
                float x = -1 + i * 0.1f, y = -1 + j * 0.1f;
                if (MoonPhase.cell(x, y, 0f) == 2) litNew++;
                int full = MoonPhase.cell(x, y, 0.5f);
                int base = MoonPhase.cell(x, y, 0.25f) == 0 ? 0 : 1;
                if (base == 1 && full != 2) allFull = false;
            }
        }
        T.eq(0, litNew, "new has no lit cells");
        T.check(allFull, "full lights every in-disc cell");
        T.eq(2, MoonPhase.cell(0.5f, 0, 0.25f), "first quarter right lit");
        T.eq(1, MoonPhase.cell(-0.5f, 0, 0.25f), "first quarter left unlit");
        T.eq(2, MoonPhase.cell(-0.5f, 0, 0.75f), "last quarter left lit");
        T.eq(1, MoonPhase.cell(0.5f, 0, 0.75f), "last quarter right unlit");
        T.eq(0, MoonPhase.cell(1, 1, 0.5f), "corner outside");
    }

    public static void testMoonClearOfWidgetZones() {
        float[][] sizes = {{1856, 2160}, {968, 2376}};
        for (float[] s : sizes) {
            float[] c = GameConfig.moonCentre(s[0], s[1]);
            float m = 0.06f * s[0];
            List<float[]> zs = Zones.defaults(s[0], s[1]);
            for (float[] z : zs) {
                boolean hit = c[0] - m < z[0] + z[2] && c[0] + m > z[0] && c[1] - m < z[1] + z[3] && c[1] + m > z[1];
                T.check(!hit, "moon box clear of zone at " + s[0]);
            }
        }
    }
}
