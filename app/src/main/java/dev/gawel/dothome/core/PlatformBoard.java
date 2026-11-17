package dev.gawel.dothome.core;

/** Text model for the platform board rows. */
public final class PlatformBoard {
    private PlatformBoard() {}

    public static final int ROWS = 3;

    public static String text(Arrival a) {
        return a.due ? "DUE" : Integer.toString(a.minutes);
    }
}
