package dev.gawel.dothome.test;

import dev.gawel.dothome.core.LocationChooser;
import dev.gawel.dothome.core.NwsParser;
import dev.gawel.dothome.core.OpenMeteoParser;
import dev.gawel.dothome.core.Place;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherCodes;
import dev.gawel.dothome.core.WeatherUrls;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class WeatherTest {
    private static String fixture(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read fixture " + name + ": " + e);
        }
    }

    public static void testParseMalformedReturnsNull() {
        T.eq(null, OpenMeteoParser.parse("{\"current\":", 0), "truncated");
        T.eq(null, OpenMeteoParser.parse("<html>", 0), "html");
        T.eq(null, OpenMeteoParser.parse("{\"daily\":{}}", 0), "missing current");
        T.eq(null, NwsParser.headline("<html>"), "nws html");
    }

    public static void testMissingTemperatureReturnsNull() {
        T.eq(null, OpenMeteoParser.parse("{\"current\":{}}", 0), "empty current");
        T.eq(null, OpenMeteoParser.parse("{\"current\":{\"temperature_2m\":\"hot\"}}", 0), "non-numeric temperature");
        T.eq(null, OpenMeteoParser.parse("{\"current\":{\"temperature_2m\":null}}", 0), "null temperature");
    }

    public static void testOpenMeteoFixture() {
        Weather w = OpenMeteoParser.parse(fixture("open-meteo.json"), 12345L);
        T.check(w != null, "parsed");
        T.near(33.3, w.tempF, 1e-9, "temp");
        T.near(48.5, w.highF, 1e-9, "high");
        T.near(30.4, w.lowF, 1e-9, "low");
        T.near(5.0, w.windMph, 1e-9, "wind");
        T.near(218, w.windDirDeg, 1e-9, "wind dir");
        T.eq(0, w.code, "code");
        T.eq(WeatherCodes.label(w.code), w.condition, "condition");
        T.eq("Clear", w.condition, "clear");
        T.eq(null, w.alert, "no alert");
        T.eq(12345L, w.fetchedAtMs, "fetchedAt");
        T.check(w.sunsetMs > w.sunriseMs, "sunset after sunrise");
        // 2026-11-15T06:40 at UTC-6 is 12:40 UTC
        T.eq(1794746400000L, w.sunriseMs, "sunrise epoch");
        T.eq(1794781740000L, w.sunsetMs, "sunset epoch");
    }

    public static void testWithAlertAndPlace() {
        Weather w = OpenMeteoParser.parse(fixture("open-meteo.json"), 1L);
        Weather x = w.withAlert("Wind Advisory").withPlace("Chicago");
        T.eq("Wind Advisory", x.alert, "alert");
        T.eq("Chicago", x.place, "place");
        T.eq(w.tempF, x.tempF, "temp kept");
        T.eq(null, w.alert, "original unchanged");
    }

    public static void testParsesVisibilityAndPrecip() {
        Weather w = OpenMeteoParser.parse(fixture("open-meteo.json"), 1L);
        T.near(24140, w.visibilityM, 1e-9, "visibility");
        T.near(0, w.precipMm, 1e-9, "precip");
        T.near(2.5, OpenMeteoParser.parse("{\"current\":{\"temperature_2m\":1,\"precipitation\":2.5}}", 1L).precipMm,
            1e-9, "precip value");
    }

    public static void testMissingVisibilityIsNaN() {
        Weather w = OpenMeteoParser.parse("{\"current\":{\"temperature_2m\":1}}", 1L);
        T.check(Double.isNaN(w.visibilityM), "NaN");
        T.near(0, w.precipMm, 0, "precip default");
    }

    public static void testUrlRequestsVisibility() {
        String u = WeatherUrls.openMeteo(41.8781, -87.6298);
        T.check(u.contains("visibility"), "has visibility");
        T.eq(1, u.split("api.open-meteo.com", -1).length - 1, "one request");
    }

    public static void testWithAlertAndPlaceKeepFx() {
        Weather w = new Weather(1, 2, 0, 3, 4, 61, "x", null, 0, 0, 5, null, 1.5, 900);
        Weather x = w.withAlert("a").withPlace("p");
        T.near(1.5, x.precipMm, 0, "precip kept");
        T.near(900, x.visibilityM, 0, "visibility kept");
    }

    public static void testWeatherCodes() {
        T.eq("Clear", WeatherCodes.label(0), "0");
        T.eq("Mostly clear", WeatherCodes.label(1), "1");
        T.eq("Partly cloudy", WeatherCodes.label(2), "2");
        T.eq("Cloudy", WeatherCodes.label(3), "3");
        T.eq("Fog", WeatherCodes.label(45), "45");
        T.eq("Drizzle", WeatherCodes.label(55), "55");
        T.eq("Rain", WeatherCodes.label(65), "65");
        T.eq("Snow", WeatherCodes.label(73), "73");
        T.eq("Showers", WeatherCodes.label(81), "81");
        T.eq("Snow showers", WeatherCodes.label(86), "86");
        T.eq("Thunderstorm", WeatherCodes.label(96), "96");
        T.eq("\u2014", WeatherCodes.label(42), "unknown");
        T.eq(WeatherCodes.Glyph.CLEAR, WeatherCodes.glyph(0), "g clear");
        T.eq(WeatherCodes.Glyph.CLOUD, WeatherCodes.glyph(2), "g cloud");
        T.eq(WeatherCodes.Glyph.FOG, WeatherCodes.glyph(48), "g fog");
        T.eq(WeatherCodes.Glyph.RAIN, WeatherCodes.glyph(53), "g drizzle");
        T.eq(WeatherCodes.Glyph.RAIN, WeatherCodes.glyph(82), "g showers");
        T.eq(WeatherCodes.Glyph.SNOW, WeatherCodes.glyph(75), "g snow");
        T.eq(WeatherCodes.Glyph.SNOW, WeatherCodes.glyph(85), "g snow showers");
        T.eq(WeatherCodes.Glyph.STORM, WeatherCodes.glyph(99), "g storm");
    }

    public static void testNwsPicksMostSevere() {
        T.eq("Winter Storm Warning", NwsParser.headline(fixture("nws-alerts.json")), "severe wins over minor");
    }

    public static void testNwsEmptyFeaturesNull() {
        T.eq(null, NwsParser.headline("{\"type\":\"FeatureCollection\",\"features\":[]}"), "empty");
        T.eq(null, NwsParser.headline("{}"), "no features");
    }

    public static void testUrlsExact() {
        T.eq("https://api.open-meteo.com/v1/forecast?latitude=41.88&longitude=-87.63"
            + "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,precipitation,visibility"
            + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=1",
            WeatherUrls.openMeteo(41.8781, -87.6298), "open-meteo");
        T.eq("https://api.weather.gov/alerts/active?point=41.88,-87.63",
            WeatherUrls.nwsAlerts(41.8781, -87.6298), "nws");
        T.eq("DotHome/1.0 (me@example.com)", WeatherUrls.userAgent("me@example.com"), "ua");
    }

    public static void testDeniedFallsBackToDefault() {
        Place d = LocationChooser.choose(null, null, null, null);
        T.eq(Place.Source.DEFAULT, d.source, "default source");
        T.near(41.88, d.lat, 0, "default lat");
        T.near(-87.63, d.lon, 0, "default lon");
        Place c = LocationChooser.choose(null, null, 41.9, -87.7);
        T.eq(Place.Source.CACHED, c.source, "cached source");
        T.near(41.9, c.lat, 0, "cached lat");
        Place f = LocationChooser.choose(41.8781, -87.6298, 10.0, 10.0);
        T.eq(Place.Source.FRESH, f.source, "fresh source");
        T.near(41.88, f.lat, 0, "fresh rounded lat");
        T.near(-87.63, f.lon, 0, "fresh rounded lon");
        T.eq(Place.Source.CACHED, LocationChooser.choose(41.8, null, 41.9, -87.7).source, "half fresh ignored");
    }
    /** The nearest Blue Line station uses the unrounded precise fix when there is one, else the rounded place. */
    public static void testStationBasisPrefersPrecise() {
        Place cached = new Place(41.89, -87.63, Place.Source.CACHED);
        Place p = LocationChooser.forStation(41.88581, -87.63102, cached);
        T.near(41.88581, p.lat, 0, "precise lat, not rounded");
        T.near(-87.63102, p.lon, 0, "precise lon, not rounded");
        T.eq(cached, LocationChooser.forStation(null, null, cached), "no precise fix: the rounded place");
        T.eq(cached, LocationChooser.forStation(41.9, null, cached), "half a fix is ignored");
        T.eq(null, LocationChooser.forStation(null, null, null), "nothing yet");
    }
}
