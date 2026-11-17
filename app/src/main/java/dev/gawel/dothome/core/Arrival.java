package dev.gawel.dothome.core;

/** One predicted train arrival. */
public final class Arrival {
    public final String dest;
    public final int minutes;
    public final boolean due;

    public Arrival(String dest, int minutes, boolean due) {
        this.dest = dest;
        this.minutes = minutes;
        this.due = due;
    }
}
