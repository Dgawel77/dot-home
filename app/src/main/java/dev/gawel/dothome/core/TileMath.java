package dev.gawel.dothome.core;

/** Web Mercator (XYZ) tile maths. */
public final class TileMath {
    private TileMath() {}

    public static double tileXf(double lon, int z) { return (lon + 180.0) / 360.0 * (1 << z); }

    public static double tileYf(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << z);
    }

    public static int tileX(double lon, int z) { return (int) Math.floor(tileXf(lon, z)); }

    public static int tileY(double lat, int z) { return (int) Math.floor(tileYf(lat, z)); }

    public static double lonOf(double tileX, int z) { return tileX / (1 << z) * 360.0 - 180.0; }

    public static double latOf(double tileY, int z) {
        double n = Math.PI - 2.0 * Math.PI * tileY / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
