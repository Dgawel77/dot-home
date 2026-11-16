package dev.gawel.dothome.core;

/** Immutable weather snapshot for the clock/weather widget. */
public final class Weather {
    public final double tempF, highF, lowF, windMph, windDirDeg;
    public final int code;
    public final String condition;
    public final String alert; // nullable
    public final long sunriseMs, sunsetMs, fetchedAtMs;
    public final String place;

    public Weather(double tempF, double highF, double lowF, double windMph, double windDirDeg, int code,
                   String condition, String alert, long sunriseMs, long sunsetMs, long fetchedAtMs, String place) {
        this.tempF = tempF;
        this.highF = highF;
        this.lowF = lowF;
        this.windMph = windMph;
        this.windDirDeg = windDirDeg;
        this.code = code;
        this.condition = condition;
        this.alert = alert;
        this.sunriseMs = sunriseMs;
        this.sunsetMs = sunsetMs;
        this.fetchedAtMs = fetchedAtMs;
        this.place = place;
    }

    public Weather withAlert(String a) {
        return new Weather(tempF, highF, lowF, windMph, windDirDeg, code, condition, a, sunriseMs, sunsetMs,
            fetchedAtMs, place);
    }

    public Weather withPlace(String p) {
        return new Weather(tempF, highF, lowF, windMph, windDirDeg, code, condition, alert, sunriseMs, sunsetMs,
            fetchedAtMs, p);
    }
}
