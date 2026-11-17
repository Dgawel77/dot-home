package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class WeatherFxTest {
    private static final long T0 = 10_000_000_000L;

    private static Weather w(int code, double precip, double windMph, double dir, double vis, long fetched) {
        return new Weather(40, 50, 30, windMph, dir, code, "x", null, 0, 0, fetched, null, precip, vis);
    }

    private static WeatherFx fx(int code, double precip) {
        return WeatherFx.from(w(code, precip, 0, 0, Double.NaN, T0), T0);
    }

    public static void testSnowCodes() {
        T.eq(WeatherFx.Kind.SNOW, fx(73, 0).kind, "73 kind");
        T.near(0.6, fx(73, 0).intensity, 1e-6, "73");
        T.eq(WeatherFx.Kind.SNOW, fx(71, 0).kind, "71 kind");
        T.near(0.3, fx(71, 0).intensity, 1e-6, "71");
        T.eq(WeatherFx.Kind.SNOW, fx(86, 0).kind, "86 kind");
        T.near(1.0, fx(86, 0).intensity, 1e-6, "86");
    }

    public static void testRainCodesAndPrecip() {
        T.eq(WeatherFx.Kind.RAIN, fx(61, 0).kind, "61 kind");
        T.near(0.3, fx(61, 0).intensity, 1e-6, "61 no precip");
        T.near(1.0, fx(61, 3.0).intensity, 1e-6, "61 heavy precip");
        T.near(0.4, fx(61, 1.0).intensity, 1e-6, "61 1mm = 0.4");
        T.near(0.5, fx(71, 0.5).intensity, 1e-6, "snow 0.5mm = 0.5");
        T.eq(WeatherFx.Kind.RAIN, fx(95, 0).kind, "95 kind");
        T.near(1.0, fx(95, 0).intensity, 1e-6, "95");
    }

    public static void testClearIgnoresPrecip() {
        T.eq(WeatherFx.Kind.NONE, fx(0, 1.0).kind, "clear");
        T.near(0, fx(0, 1.0).intensity, 0, "clear intensity");
    }

    public static void testWindVector() {
        WeatherFx a = WeatherFx.from(w(0, 0, 25, 270, Double.NaN, T0), T0);
        T.near(1.0, a.windX, 1e-5, "west wind blows east");
        T.near(0.0, a.windY, 1e-5, "west wind y");
        WeatherFx b = WeatherFx.from(w(0, 0, 12.5, 0, Double.NaN, T0), T0);
        T.near(0.5, b.windY, 1e-5, "north wind blows south");
        T.near(0.0, b.windX, 1e-5, "north wind x");
        WeatherFx c = WeatherFx.from(w(0, 0, 50, 270, Double.NaN, T0), T0);
        T.near(1.0, c.windX, 1e-5, "capped");
        WeatherFx d = WeatherFx.from(w(0, 0, 50, 225, Double.NaN, T0), T0);
        T.near(1.0, Math.hypot(d.windX, d.windY), 1e-5, "diagonal magnitude capped");
    }

    public static void testFog() {
        T.check(fx(45, 0).fog, "45");
        T.check(fx(48, 0).fog, "48");
        T.check(WeatherFx.from(w(0, 0, 0, 0, 800, T0), T0).fog, "800 m");
        T.check(!WeatherFx.from(w(0, 0, 0, 0, Double.NaN, T0), T0).fog, "NaN");
        T.check(!WeatherFx.from(w(0, 0, 0, 0, 24140, T0), T0).fog, "clear vis");
    }

    public static void testNullAndStaleGiveNone() {
        T.check(WeatherFx.from(null, T0) == WeatherFx.NONE, "null");
        WeatherFx s = WeatherFx.from(w(73, 1, 25, 270, 100, T0 - WeatherFx.STALE_MS - 1), T0);
        T.check(s == WeatherFx.NONE, "stale");
        T.near(0, s.windX, 0, "stale wind");
        T.check(!s.fog, "stale fog");
        WeatherFx live = WeatherFx.from(w(73, 0, 0, 0, Double.NaN, T0 - WeatherFx.STALE_MS), T0);
        T.eq(WeatherFx.Kind.SNOW, live.kind, "exactly STALE_MS is live");
    }
}
