package dev.gawel.dothome.core;

import java.util.Locale;

/** Request URLs for the weather providers. */
public final class WeatherUrls {
    private WeatherUrls() {}

    private static String f2(double v) { return String.format(Locale.US, "%.2f", v); }

    public static String openMeteo(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + f2(lat) + "&longitude=" + f2(lon)
            + "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,precipitation,visibility"
            + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=1";
    }

    public static String nwsAlerts(double lat, double lon) {
        return "https://api.weather.gov/alerts/active?point=" + f2(lat) + "," + f2(lon);
    }

    public static String userAgent(String contact) { return "DotHome/1.0 (" + contact + ")"; }
}
