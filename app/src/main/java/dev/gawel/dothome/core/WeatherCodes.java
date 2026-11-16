package dev.gawel.dothome.core;

/** WMO weather-code labels and glyph families. */
public final class WeatherCodes {
    public enum Glyph { CLEAR, CLOUD, FOG, RAIN, SNOW, STORM }

    private WeatherCodes() {}

    public static String label(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95 && c <= 99) return "Thunderstorm";
        return "—";
    }

    public static Glyph glyph(int c) {
        if (c == 0 || c == 1) return Glyph.CLEAR;
        if (c == 45 || c == 48) return Glyph.FOG;
        if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82)) return Glyph.RAIN;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return Glyph.SNOW;
        if (c >= 95 && c <= 99) return Glyph.STORM;
        return Glyph.CLOUD;
    }
}
