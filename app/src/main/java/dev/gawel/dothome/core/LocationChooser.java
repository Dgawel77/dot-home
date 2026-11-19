package dev.gawel.dothome.core;

/** Picks fresh, then cached, then default (Chicago) coordinates, rounded to 0.01 degrees for the feeds. */
public final class LocationChooser {
    public static final double DEFAULT_LAT = 41.88;
    public static final double DEFAULT_LON = -87.63;

    private LocationChooser() {}

    public static Place choose(Double freshLat, Double freshLon, Double cachedLat, Double cachedLon) {
        if (freshLat != null && freshLon != null) {
            return new Place(round2(freshLat), round2(freshLon), Place.Source.FRESH);
        }
        if (cachedLat != null && cachedLon != null) {
            return new Place(round2(cachedLat), round2(cachedLon), Place.Source.CACHED);
        }
        return new Place(DEFAULT_LAT, DEFAULT_LON, Place.Source.DEFAULT);
    }

    public static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /**
     * Where to look for the nearest Blue Line station: the unrounded precise fix when there is one (it never leaves
     * the phone; the feeds get the rounded place), else the cached rounded place, else null.
     */
    public static Place forStation(Double preciseLat, Double preciseLon, Place cached) {
        if (preciseLat != null && preciseLon != null) return new Place(preciseLat, preciseLon, Place.Source.CACHED);
        return cached;
    }
}
