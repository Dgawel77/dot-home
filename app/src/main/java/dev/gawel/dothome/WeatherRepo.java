package dev.gawel.dothome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import dev.gawel.dothome.core.NwsParser;
import dev.gawel.dothome.core.OpenMeteoParser;
import dev.gawel.dothome.core.Place;
import dev.gawel.dothome.core.Weather;
import dev.gawel.dothome.core.WeatherUrls;

/** Fetches and caches the forecast and alert headline. */
final class WeatherRepo {
    private static final String TAG = "DotHome";
    private static final int TIMEOUT_MS = 10_000;
    private static final String K_OM = "weather.om", K_ALERT = "weather.alert",
        K_PLACE = "weather.place", K_AT = "weather.fetchedAtMs";

    private WeatherRepo() {}

    /** The last good fetch, or null if there is none. */
    static Weather cached(Context ctx) {
        try {
            PrefsStore prefs = new PrefsStore(ctx);
            String om = prefs.get(K_OM, null);
            String at = prefs.get(K_AT, null);
            if (om == null || at == null) return null;
            Weather w = OpenMeteoParser.parse(om, Long.parseLong(at));
            if (w == null) return null;
            String alert = prefs.get(K_ALERT, "");
            if (alert != null && !alert.isEmpty()) w = w.withAlert(alert);
            return w.withPlace(prefs.get(K_PLACE, ""));
        } catch (RuntimeException e) {
            Log.w(TAG, "weather cache unreadable: " + e);
            return null;
        }
    }

    /** Blocking. Returns fresh weather, or the existing cache (possibly null) when the fetch fails. */
    static Weather refresh(Context ctx) {
        try {
            PrefsStore prefs = new PrefsStore(ctx);
            Place place = LocationSource.current(ctx);
            String name = LocationSource.placeName(ctx, place);
            long now = System.currentTimeMillis();
            String om = null;
            Weather w = null;
            try {
                om = get(WeatherUrls.openMeteo(place.lat, place.lon), null);
                w = OpenMeteoParser.parse(om, now);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "open-meteo failed: " + e);
            }
            if (w == null) return failed(ctx);
            String alert = null;
            try {
                String ua = WeatherUrls.userAgent(ctx.getString(R.string.nws_contact));
                alert = NwsParser.headline(get(WeatherUrls.nwsAlerts(place.lat, place.lon), ua));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "nws failed, dropping alert: " + e);
            }
            prefs.put(K_OM, om);
            prefs.put(K_ALERT, alert == null ? "" : alert);
            prefs.put(K_PLACE, name);
            prefs.put(K_AT, String.valueOf(now));
            Weather out = w.withPlace(name);
            if (alert != null) out = out.withAlert(alert);
            Log.i(TAG, "weather ok " + Math.round(out.tempF) + " " + name);
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "refresh error: " + e);
            return failed(ctx);
        }
    }

    private static Weather failed(Context ctx) {
        Weather c = cached(ctx);
        long age = c == null ? -1 : (System.currentTimeMillis() - c.fetchedAtMs) / 60000;
        Log.w(TAG, "weather failed, keeping cache age=" + age + "m");
        return c;
    }

    private static String get(String url, String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
