package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.Calendar;
import java.util.TimeZone;

public final class MoonSkyTest {
    private static final long MIN = 60000L;
    private static final double LAT = 41.88, LON = -87.63; // Chicago
    private static final double HORIZON = -0.833;
    private static final int RISE = 0, SET = 1, TRANSIT = 2;

    /** Local clock time at a fixed UTC offset (hours) to epoch ms. */
    private static long local(int y, int mo, int d, int hh, int mm, int offsetHours) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, hh, mm, 0);
        return c.getTimeInMillis() - offsetHours * 3600000L;
    }

    private static boolean crosses(boolean moon, int kind, long t, double[] a, double[] b) {
        if (moon) {
            MoonSky.moon(t, LAT, LON, a);
            MoonSky.moon(t + MIN, LAT, LON, b);
        } else {
            MoonSky.sun(t, LAT, LON, a);
            MoonSky.sun(t + MIN, LAT, LON, b);
        }
        if (kind == RISE) return a[0] < HORIZON && b[0] >= HORIZON;
        if (kind == SET) return a[0] >= HORIZON && b[0] < HORIZON;
        return a[1] < 180 && b[1] >= 180 && a[0] > 0;
    }

    /** Scans minute by minute over +-60 min of the published time; asserts the found event is within tolMin. */
    private static void event(boolean moon, int kind, long published, int tolMin, String name) {
        double[] a = new double[2], b = new double[2];
        long best = Long.MIN_VALUE;
        for (long t = published - 60 * MIN; t <= published + 60 * MIN; t += MIN) {
            if (crosses(moon, kind, t, a, b)) {
                long found = t + MIN / 2;
                if (best == Long.MIN_VALUE || Math.abs(found - published) < Math.abs(best - published)) best = found;
            }
        }
        T.check(best != Long.MIN_VALUE, name + ": no event within 60 min");
        double offMin = (best - published) / (double) MIN;
        T.check(Math.abs(offMin) <= tolMin, name + ": off by " + offMin + " min");
    }

    public static void testMeeus47a() {
        // Meeus, Astronomical Algorithms (2nd ed.), example 47.a: 1992-04-12 0h TD
        double[] out = new double[3];
        MoonSky.moonEcliptic(2448724.5, out);
        T.near(133.162655, out[0], 0.1, "lambda");
        T.near(-3.229126, out[1], 0.1, "beta");
        T.near(368409.7, out[2], 300, "distance km");
    }

    public static void testChicagoMoonTimesUsno() {
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-11-17&coords=41.88,-87.63&tz=-6 (fetched 2026-11-17)
        event(true, RISE, local(2026, 11, 17, 12, 49, -6), 10, "11-17 rise");
        event(true, TRANSIT, local(2026, 11, 17, 18, 16, -6), 10, "11-17 transit");
        event(true, SET, local(2026, 11, 17, 23, 51, -6), 10, "11-17 set");
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-11-24&coords=41.88,-87.63&tz=-6 (fetched 2026-11-17)
        event(true, SET, local(2026, 11, 24, 7, 7, -6), 10, "11-24 set");
        event(true, RISE, local(2026, 11, 24, 16, 8, -6), 10, "11-24 rise");
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-12-01&coords=41.88,-87.63&tz=-6 (fetched 2026-11-17)
        event(true, TRANSIT, local(2026, 12, 1, 6, 2, -6), 10, "12-01 transit");
        event(true, SET, local(2026, 12, 1, 12, 26, -6), 10, "12-01 set");
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-06-15&coords=41.88,-87.63&tz=-5 (fetched 2026-11-17)
        event(true, RISE, local(2026, 6, 15, 5, 18, -5), 10, "06-15 rise");
        event(true, TRANSIT, local(2026, 6, 15, 13, 32, -5), 10, "06-15 transit");
        event(true, SET, local(2026, 6, 15, 21, 42, -5), 10, "06-15 set");
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-03-10&coords=41.88,-87.63&tz=-5 (fetched 2026-11-17)
        event(true, RISE, local(2026, 3, 10, 1, 46, -5), 10, "03-10 rise");
        event(true, TRANSIT, local(2026, 3, 10, 6, 6, -5), 10, "03-10 transit");
        event(true, SET, local(2026, 3, 10, 10, 24, -5), 10, "03-10 set");
    }

    public static void testChicagoSunTimesUsno() {
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-11-17&coords=41.88,-87.63&tz=-6 (fetched 2026-11-17)
        event(false, RISE, local(2026, 11, 17, 6, 43, -6), 5, "11-17 sunrise");
        event(false, SET, local(2026, 11, 17, 16, 28, -6), 5, "11-17 sunset");
        // https://aa.usno.navy.mil/api/rstt/oneday?date=2026-06-15&coords=41.88,-87.63&tz=-5 (fetched 2026-11-17)
        event(false, RISE, local(2026, 6, 15, 5, 15, -5), 5, "06-15 sunrise");
        event(false, SET, local(2026, 6, 15, 20, 27, -5), 5, "06-15 sunset");
    }

    private static float limbAt(long t) {
        double[] m = new double[2], s = new double[2];
        MoonSky.moon(t, LAT, LON, m);
        MoonSky.sun(t, LAT, LON, s);
        return MoonSky.limbAngle(m[1], m[0], s[1], s[0]);
    }

    public static void testLimbPointsTowardTheSun() {
        float waxing = limbAt(local(2026, 11, 17, 18, 16, -6));
        T.check(Math.cos(waxing) > 0.5, "waxing limb points right, got " + waxing);
        float waning = limbAt(local(2026, 12, 1, 6, 2, -6));
        T.check(Math.cos(waning) < -0.5, "waning limb points left, got " + waning);
        T.near(-Math.PI / 2, MoonSky.limbAngle(180, 30, 180, 60), 1e-3, "sun straight above");
    }

    public static void testScreenMappingClamps() {
        T.near(80, MoonSky.screenX(60, 1000), 1e-3, "az 60");
        T.near(920, MoonSky.screenX(300, 1000), 1e-3, "az 300");
        T.near(80, MoonSky.screenX(10, 1000), 1e-3, "az 10 clamps");
        T.near(920, MoonSky.screenX(350, 1000), 1e-3, "az 350 clamps");
        T.near(500, MoonSky.screenX(180, 1000), 1e-3, "south centred");

        float w = 1000, h = 1000, clear = 40;
        // the arc sits high: its top just under the top widget zone (bottom 0.266 h) plus the halo's extent
        T.near(266 + 40, MoonSky.bandTop(w, h, clear), 0.5, "main top: under the top zone");
        T.near(430, MoonSky.bandBottom(w, h), 1e-3, "main bottom: rise/set at 43%");
        T.near(MoonSky.bandBottom(w, h), MoonSky.screenY(0, w, h, clear), 1e-3, "alt 0");
        T.near(MoonSky.bandTop(w, h, clear), MoonSky.screenY(75, w, h, clear), 1e-3, "alt 75");
        T.near(MoonSky.bandTop(w, h, clear), MoonSky.screenY(89, w, h, clear), 1e-3, "alt 89 clamps");
        T.near(MoonSky.bandBottom(w, h), MoonSky.screenY(-5, w, h, clear), 1e-3, "alt -5 clamps");
        T.check(MoonSky.bandBottom(w, h) <= 0.45f * h, "never in the middle of the screen");
        T.check(MoonSky.bandBottom(w, h) + clear < 0.693f * h, "clear of the lower widget zone");

        float cw = 450, ch = 1000; // w/h = 0.45, cover: top zone bottom 0.267 h
        T.near(267 + 40, MoonSky.bandTop(cw, ch, clear), 0.5, "cover top");
        T.near(430, MoonSky.bandBottom(cw, ch), 1e-3, "cover bottom");
        T.near(430, MoonSky.screenY(0, cw, ch, clear), 1e-3, "cover alt 0");
        T.near(MoonSky.bandTop(cw, ch, clear), MoonSky.screenY(75, cw, ch, clear), 1e-3, "cover alt 75");

        // a huge moon on a short screen: the band collapses to its bottom, never inverts
        T.near(430, MoonSky.bandTop(w, h, 400), 1e-3, "top never below bottom");
    }

    /** On a lock still the moon is pushed whole below the clear top band, or not drawn when it no longer fits. */
    public static void testStillMoonClearsTheTopBandWhole() {
        float top = 420, bottom = 1000, ext = 30;
        T.near(600, MoonSky.stillY(600, ext, top, bottom), 1e-3, "already clear: unchanged");
        T.near(450, MoonSky.stillY(450, ext, top, bottom), 1e-3, "just clear: unchanged");
        T.near(450, MoonSky.stillY(440, ext, top, bottom), 1e-3, "straddling the band's edge: pushed down whole");
        T.near(450, MoonSky.stillY(300, ext, top, bottom), 1e-3, "inside the band: pushed down whole");
        T.near(970, MoonSky.stillY(970, ext, top, bottom), 1e-3, "touching the bottom: drawn");
        T.check(Float.isNaN(MoonSky.stillY(980, ext, top, bottom)), "past the open band's bottom: not drawn");
        T.check(Float.isNaN(MoonSky.stillY(300, ext, top, 470)), "no room below the band: not drawn");
        T.near(300, MoonSky.stillY(300, ext, 0, Float.MAX_VALUE), 1e-3, "no top band: unchanged");
    }
}
