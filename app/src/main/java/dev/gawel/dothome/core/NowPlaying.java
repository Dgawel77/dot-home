package dev.gawel.dothome.core;

import java.util.List;

/** What a media session is playing right now. Plain data; pure Java. */
public final class NowPlaying {
    public String title, artist;
    public boolean playing;
    public long positionMs, durationMs, changedAtMs;
    public String sessionKey;

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
