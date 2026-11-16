package dev.gawel.dothome.core;

/** A coordinate and where it came from. */
public final class Place {
    public enum Source { FRESH, CACHED, DEFAULT }

    public final double lat, lon;
    public final Source source;

    public Place(double lat, double lon, Source source) {
        this.lat = lat;
        this.lon = lon;
        this.source = source;
    }
}
