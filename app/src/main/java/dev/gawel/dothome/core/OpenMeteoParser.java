package dev.gawel.dothome.core;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Parses an Open-Meteo forecast response into a Weather, or null if unusable. */
public final class OpenMeteoParser {
    private OpenMeteoParser() {}

    public static Weather parse(String json, long nowMs) {
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            if (root == null) return null;
            Map<String, Object> cur = MiniJson.obj(root.get("current"));
            if (cur == null) return null;
            int offset = (int) MiniJson.num(root.get("utc_offset_seconds"), 0);
            Map<String, Object> daily = MiniJson.obj(root.get("daily"));
            if (!(cur.get("temperature_2m") instanceof Double)) return null;
            double temp = (Double) cur.get("temperature_2m");
            int code = (int) MiniJson.num(cur.get("weather_code"), -1);
            return new Weather(
                temp,
                MiniJson.num(first(daily, "temperature_2m_max"), temp),
                MiniJson.num(first(daily, "temperature_2m_min"), temp),
                MiniJson.num(cur.get("wind_speed_10m"), 0),
                MiniJson.num(cur.get("wind_direction_10m"), 0),
                code, WeatherCodes.label(code), null,
                epochMs(MiniJson.str(first(daily, "sunrise"), null), offset),
                epochMs(MiniJson.str(first(daily, "sunset"), null), offset),
                nowMs, null);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static Object first(Map<String, Object> daily, String key) {
        if (daily == null) return null;
        List<Object> l = MiniJson.arr(daily.get(key));
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Local ISO time ("2026-11-15T06:41") to epoch ms: local - offset = UTC. */
    private static long epochMs(String iso, int offsetSeconds) {
        if (iso == null) return 0;
        return LocalDateTime.parse(iso).toEpochSecond(ZoneOffset.UTC) * 1000L - offsetSeconds * 1000L;
    }
}
