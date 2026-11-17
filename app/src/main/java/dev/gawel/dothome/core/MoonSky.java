package dev.gawel.dothome.core;

/**
 * Where the moon and sun stand in the sky, and where that lands on a south-facing screen.
 * Moon: Meeus, Astronomical Algorithms ch. 47 (main terms of tables 47.A/47.B), nutation ignored.
 * Sun: Meeus ch. 25 low precision. All methods allocation-free; callers pass out arrays.
 */
public final class MoonSky {
    private MoonSky() {}

    private static final double RAD = Math.PI / 180;
    private static final double DELTA_T_DAYS = 69.0 / 86400;

    // Table 47.A: D, M, M', F multipliers; sigma-l (1e-6 deg); sigma-r (1e-3 km).
    private static final int[] LR_ARGS = {
        0, 0, 1, 0,   2, 0, -1, 0,   2, 0, 0, 0,   0, 0, 2, 0,   0, 1, 0, 0,
        0, 0, 0, 2,   2, 0, -2, 0,   2, -1, -1, 0, 2, 0, 1, 0,   2, -1, 0, 0,
        0, 1, -1, 0,  1, 0, 0, 0,    0, 1, 1, 0,   2, 0, 0, -2,  0, 0, 1, 2,
        0, 0, 1, -2,  4, 0, -1, 0,   0, 0, 3, 0,   4, 0, -2, 0,  2, 1, -1, 0,
        2, 1, 0, 0,   1, 0, -1, 0,   1, 1, 0, 0,   2, -1, 1, 0,  2, 0, 2, 0,
        4, 0, 0, 0,   2, 0, -3, 0,   0, 1, -2, 0,  2, 0, -1, 2,  2, -1, -2, 0,
        1, 0, 1, 0,   2, -2, 0, 0,   0, 1, 2, 0,   0, 2, 0, 0,   2, -2, -1, 0,
        2, 0, 1, -2,  2, 0, 0, 2,    4, -1, -1, 0, 0, 0, 2, 2,   3, 0, -1, 0,
        2, 1, 1, 0,   4, -1, -2, 0,  0, 2, -1, 0,  2, 2, -1, 0,  2, 1, -2, 0,
        2, -1, 0, -2, 4, 0, 1, 0,    0, 0, 4, 0,   4, -1, 0, 0,  1, 0, -2, 0,
        2, 1, 0, -2,  0, 0, 2, -2,   1, 1, 1, 0,   3, 0, -2, 0,  4, 0, -3, 0,
        2, -1, 2, 0,  0, 2, 1, 0,    1, 1, -1, 0,  2, 0, 3, 0,   2, 0, -1, -2,
    };
    private static final int[] L_COEF = {
        6288774, 1274027, 658314, 213618, -185116, -114332, 58793, 57066, 53322, 45758,
        -40923, -34720, -30383, 15327, -12528, 10980, 10675, 10034, 8548, -7888,
        -6766, -5163, 4987, 4036, 3994, 3861, 3665, -2689, -2602, 2390,
        -2348, 2236, -2120, -2069, 2048, -1773, -1595, 1215, -1110, -892,
        -810, 759, -713, -700, 691, 596, 549, 537, 520, -487,
        -399, -381, 351, -340, 330, 327, -323, 299, 294, 0,
    };
    private static final int[] R_COEF = {
        -20905355, -3699111, -2955968, -569925, 48888, -3149, 246158, -152138, -170733, -204586,
        -129620, 108743, 104755, 10321, 0, 79661, -34782, -23210, -21636, 24208,
        30824, -8379, -16675, -12831, -10445, -11650, 14403, -7003, 0, 10056,
        6322, -9884, 5751, 0, -4950, 4130, 0, -3958, 0, 3258,
        2616, -1897, -2117, 2354, 0, 0, -1423, -1117, -1571, -1739,
        0, -4421, 0, 0, 0, 0, 1165, 0, 0, 8752,
    };
    // Table 47.B: D, M, M', F multipliers; sigma-b (1e-6 deg).
    private static final int[] B_ARGS = {
        0, 0, 0, 1,   0, 0, 1, 1,   0, 0, 1, -1,  2, 0, 0, -1,  2, 0, -1, 1,
        2, 0, -1, -1, 2, 0, 0, 1,   0, 0, 2, 1,   2, 0, 1, -1,  0, 0, 2, -1,
        2, -1, 0, -1, 2, 0, -2, -1, 2, 0, 1, 1,   2, 1, 0, -1,  2, -1, -1, 1,
        2, -1, 0, 1,  2, -1, -1, -1, 0, 1, -1, -1, 4, 0, -1, -1, 0, 1, 0, 1,
        0, 0, 0, 3,   0, 1, -1, 1,  1, 0, 0, 1,   0, 1, 1, 1,   0, 1, 1, -1,
        0, 1, 0, -1,  1, 0, 0, -1,  0, 0, 3, 1,   4, 0, 0, -1,  4, 0, -1, 1,
    };
    private static final int[] B_COEF = {
        5128122, 280602, 277693, 173237, 55413, 46271, 32573, 17198, 9266, 8822,
        8216, 4324, 4200, -3359, 2463, 2211, 2065, -1870, 1828, -1794,
        -1749, -1565, -1491, -1475, -1410, -1344, -1335, 1107, 1021, 833,
    };

    private static double julianDay(long epochMs) { return epochMs / 86400000.0 + 2440587.5; }

    /** Geocentric lambda (deg) and beta (deg) into out[0], out[1]; returns the distance in km. */
    private static double lunarSeries(double jde, double[] out) {
        double t = (jde - 2451545.0) / 36525;
        double t2 = t * t, t3 = t2 * t, t4 = t3 * t;
        double lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841 - t4 / 65194000;
        double d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868 - t4 / 113065000;
        double m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000;
        double mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699 - t4 / 14712000;
        double f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000 + t4 / 863310000;
        double a1 = 119.75 + 131.849 * t, a2 = 53.09 + 479264.290 * t, a3 = 313.45 + 481266.484 * t;
        double e = 1 - 0.002516 * t - 0.0000074 * t2;

        double sl = 0, sr = 0, sb = 0;
        for (int i = 0; i < L_COEF.length; i++) {
            int k = i * 4, cm = LR_ARGS[k + 1];
            double arg = (LR_ARGS[k] * d + cm * m + LR_ARGS[k + 2] * mp + LR_ARGS[k + 3] * f) * RAD;
            double ef = cm == 0 ? 1 : (cm == 1 || cm == -1 ? e : e * e);
            sl += L_COEF[i] * ef * Math.sin(arg);
            sr += R_COEF[i] * ef * Math.cos(arg);
        }
        for (int i = 0; i < B_COEF.length; i++) {
            int k = i * 4, cm = B_ARGS[k + 1];
            double arg = (B_ARGS[k] * d + cm * m + B_ARGS[k + 2] * mp + B_ARGS[k + 3] * f) * RAD;
            double ef = cm == 0 ? 1 : (cm == 1 || cm == -1 ? e : e * e);
            sb += B_COEF[i] * ef * Math.sin(arg);
        }
        sl += 3958 * Math.sin(a1 * RAD) + 1962 * Math.sin((lp - f) * RAD) + 318 * Math.sin(a2 * RAD);
        sb += -2235 * Math.sin(lp * RAD) + 382 * Math.sin(a3 * RAD) + 175 * Math.sin((a1 - f) * RAD)
                + 175 * Math.sin((a1 + f) * RAD) + 127 * Math.sin((lp - mp) * RAD) - 115 * Math.sin((lp + mp) * RAD);

        out[0] = norm360(lp + sl / 1e6);
        out[1] = sb / 1e6;
        return 385000.56 + sr / 1000;
    }

    /** Geocentric ecliptic longitude (deg), latitude (deg) and distance (km) of the moon at a Julian Ephemeris Day. */
    public static void moonEcliptic(double jde, double[] out) {
        out[2] = lunarSeries(jde, out);
    }

    /** out[0] topocentric altitude deg (no refraction), out[1] azimuth deg from north, clockwise. */
    public static void moon(long epochMs, double latDeg, double lonDeg, double[] out) {
        double jd = julianDay(epochMs);
        double dist = lunarSeries(jd + DELTA_T_DAYS, out);
        horizontal(jd, out[0], out[1], latDeg, lonDeg, out);
        double alt = out[0] * RAD;
        out[0] -= Math.asin(6378.14 / dist) * Math.cos(alt) / RAD;
    }

    /** out[0] geocentric altitude deg (no refraction), out[1] azimuth deg from north, clockwise. */
    public static void sun(long epochMs, double latDeg, double lonDeg, double[] out) {
        double jd = julianDay(epochMs);
        double t = (jd + DELTA_T_DAYS - 2451545.0) / 36525;
        double l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
        double m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * RAD;
        double c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(m)
                + (0.019993 - 0.000101 * t) * Math.sin(2 * m) + 0.000289 * Math.sin(3 * m);
        double omega = (125.04 - 1934.136 * t) * RAD;
        double lambda = l0 + c - 0.00569 - 0.00478 * Math.sin(omega);
        horizontal(jd, lambda, 0, latDeg, lonDeg, out);
    }

    /** Ecliptic (lambda, beta) at Julian Day jd (UT) to out[0] altitude, out[1] azimuth from north. */
    private static void horizontal(double jd, double lambdaDeg, double betaDeg, double latDeg, double lonDeg, double[] out) {
        double t = (jd + DELTA_T_DAYS - 2451545.0) / 36525;
        double eps = (23.4392911111 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600) * RAD;
        double lam = lambdaDeg * RAD, bet = betaDeg * RAD;
        double ra = Math.atan2(Math.sin(lam) * Math.cos(eps) - Math.tan(bet) * Math.sin(eps), Math.cos(lam));
        double dec = Math.asin(Math.sin(bet) * Math.cos(eps) + Math.cos(bet) * Math.sin(eps) * Math.sin(lam));

        double tu = (jd - 2451545.0) / 36525;
        double gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * tu * tu - tu * tu * tu / 38710000;
        double h = (gmst + lonDeg) * RAD - ra;
        double phi = latDeg * RAD;
        double az = Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi));
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h));
        out[0] = alt / RAD;
        out[1] = norm360(az / RAD + 180);
    }

    private static double norm360(double deg) {
        double r = deg % 360;
        return r < 0 ? r + 360 : r;
    }

    /**
     * Screen radians (x right, y down) of the direction from the moon toward the sun along the great circle,
     * with screen +x = increasing azimuth and screen up = increasing altitude.
     */
    public static float limbAngle(double moonAz, double moonAlt, double sunAz, double sunAlt) {
        double h1 = moonAlt * RAD, h2 = sunAlt * RAD, da = (sunAz - moonAz) * RAD;
        double dx = Math.cos(h2) * Math.sin(da);
        double dUp = Math.cos(h1) * Math.sin(h2) - Math.sin(h1) * Math.cos(h2) * Math.cos(da);
        return (float) Math.atan2(-dUp, dx);
    }

    /** Azimuth 60..300 (east through south to west) across the screen; clamped outside. */
    public static float screenX(double azDeg, float w) {
        double az = Math.max(60, Math.min(300, azDeg));
        return (float) (w * (0.08 + 0.84 * (az - 60) / 240));
    }

    public static float bandTop(float w, float h) { return 0.30f * h; }

    public static float bandBottom(float w, float h) { return (Zones.isCover(w, h) ? 0.67f : 0.64f) * h; }

    /** Altitude 0..75 deg from the band's bottom to its top; clamped outside. */
    public static float screenY(double altDeg, float w, float h) {
        float top = bandTop(w, h), bottom = bandBottom(w, h);
        double k = Math.max(0, Math.min(1, altDeg / 75));
        return (float) (bottom + (top - bottom) * k);
    }
}
