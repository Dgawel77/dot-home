package dev.gawel.dothome.core;

/** Pure maths for the music widget's tap-to-seek zones. */
public final class MusicSeek {
    private MusicSeek() {}

    /** Number of equal tap zones laid over the progress dot row. */
    public static final int ZONES = 20;
    /** PlaybackState.ACTION_SEEK_TO (1 << 8), duplicated so core stays free of android.*. */
    public static final long ACTION_SEEK_TO = 256L;

    /** Seek fraction of zone i: the zone's centre, (i + 0.5) / ZONES. */
    public static float zoneFraction(int i) {
        return (i + 0.5f) / ZONES;
    }

    /** Target position for a fraction of the duration, clamped to [0, durationMs]; 0 when the duration is unknown. */
    public static long positionFor(float fraction, long durationMs) {
        if (durationMs <= 0 || Float.isNaN(fraction)) return 0;
        float f = Math.max(0f, Math.min(1f, fraction));
        long pos = (long) (f * durationMs);
        return Math.max(0, Math.min(durationMs, pos));
    }

    /** Seeking is offered only when the session advertises ACTION_SEEK_TO and knows its duration. */
    public static boolean canSeek(long actions, long durationMs) {
        return (actions & ACTION_SEEK_TO) != 0 && durationMs > 0;
    }
}
