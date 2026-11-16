package dev.gawel.dothome.core;

/** Equirectangular view projection with latitude stretched by 1/cos(latC), as the mockup's _proj. */
public final class GeoProjection {
    public final double lon0, lon1, latC;
    public final float w, h;
    private final double kx, ky;

    public GeoProjection(double lon0, double lon1, double latC, float w, float h) {
        this.lon0 = lon0;
        this.lon1 = lon1;
        this.latC = latC;
        this.w = w;
        this.h = h;
        this.kx = w / (lon1 - lon0);
        this.ky = kx / Math.cos(Math.toRadians(latC));
    }

    public static GeoProjection area(double lat, double lon, float w, float h) {
        return new GeoProjection(lon - 0.77, lon + 0.53, lat + 0.02, w, h);
    }

    /** About 250 miles of view to the west. */
    public static GeoProjection region(double lat, double lon, float w, float h) {
        return new GeoProjection(lon - 4.57, lon + 2.43, lat + 0.32, w, h);
    }

    public float x(double lon) { return (float) ((lon - lon0) * kx); }

    public float y(double lat) { return (float) (h / 2.0 - (lat - latC) * ky); }

    public double lon(float x) { return lon0 + x / kx; }

    public double lat(float y) { return latC - (y - h / 2.0) / ky; }
}
