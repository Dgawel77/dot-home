package dev.gawel.dothome.core;

/** What the game should show for the cached weather: precipitation, wind and fog. Immutable. */
public final class WeatherFx {
    public enum Kind { NONE, RAIN, SNOW }

    public static final long STALE_MS = 3L * 60 * 60 * 1000;
    public static final WeatherFx NONE = new WeatherFx(Kind.NONE, 0f, 0f, 0f, false);

    private static final double FOG_VISIBILITY_M = 1000;

    public final Kind kind;
    public final float intensity;
    /** Direction the wind blows toward on a portrait screen (east +x, north -y), magnitude min(1, mph/25). */
    public final float windX, windY;
    public final boolean fog;

    private WeatherFx(Kind kind, float intensity, float windX, float windY, boolean fog) {
        this.kind = kind;
        this.intensity = intensity;
        this.windX = windX;
        this.windY = windY;
        this.fog = fog;
    }

    public static WeatherFx from(Weather w, long nowMs) {
        if (w == null || nowMs - w.fetchedAtMs > STALE_MS) return NONE;
        boolean snow = isSnow(w.code);
        double base = codeBase(w.code);
        Kind kind = Kind.NONE;
        float intensity = 0f;
        if (base > 0) {
            kind = snow ? Kind.SNOW : Kind.RAIN;
            double precip = clamp(w.precipMm / (snow ? 1.0 : 2.5));
            intensity = (float) Math.max(base, precip);
        }
        double mag = Math.min(1.0, Math.max(0, w.windMph) / 25.0);
        double rad = Math.toRadians(w.windDirDeg);
        float wx = (float) (-Math.sin(rad) * mag);
        float wy = (float) (Math.cos(rad) * mag);
        boolean fog = w.code == 45 || w.code == 48 || w.visibilityM < FOG_VISIBILITY_M;
        return new WeatherFx(kind, intensity, wx, wy, fog);
    }

    private static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static boolean isSnow(int c) {
        return c == 71 || c == 73 || c == 75 || c == 77 || c == 85 || c == 86;
    }

    private static double codeBase(int c) {
        switch (c) {
            case 51: case 56: case 61: case 66: case 71: case 77: case 80: case 85: return 0.3;
            case 53: case 63: case 73: case 81: return 0.6;
            case 55: case 57: case 65: case 67: case 75: case 82: case 86: case 95: case 96: case 99: return 1.0;
            default: return 0;
        }
    }
}
