package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/** What a media session is playing right now. Plain data; pure Java. */
public final class NowPlaying {
    public String title, artist;
    public boolean playing;
    public long positionMs, durationMs, changedAtMs;
    public String sessionKey;
    /** STATE_STOPPED / STATE_NONE: nothing worth showing. Paused sessions stay eligible. */
    public boolean stopped;
    /** Playback speed and the SystemClock.elapsedRealtime() at which positionMs was valid. */
    public float speed = 1f;
    public long positionAtElapsedMs;

    /** Position now: extrapolated while playing, clamped to [0, durationMs] (just >= 0 if duration unknown). */
    public long positionAt(long nowElapsedMs) {
        long pos = positionMs;
        if (playing && positionAtElapsedMs > 0) {
            pos += (long) (Math.max(0, nowElapsedMs - positionAtElapsedMs) * speed);
        }
        if (pos < 0) pos = 0;
        if (durationMs > 0 && pos > durationMs) pos = durationMs;
        return pos;
    }

    /** The items that may be shown: everything except stopped/none sessions. */
    public static List<NowPlaying> eligible(List<NowPlaying> all) {
        List<NowPlaying> out = new ArrayList<>();
        if (all == null) return out;
        for (NowPlaying n : all) if (n != null && !n.stopped) out.add(n);
        return out;
    }

    /** The first playing item; else the one changed most recently; null for an empty list. */
    public static NowPlaying choose(List<NowPlaying> all) {
        if (all == null || all.isEmpty()) return null;
        NowPlaying latest = null;
        for (NowPlaying n : all) {
            if (n == null) continue;
            if (n.playing) return n;
            if (latest == null || n.changedAtMs > latest.changedAtMs) latest = n;
        }
        return latest;
    }
}
