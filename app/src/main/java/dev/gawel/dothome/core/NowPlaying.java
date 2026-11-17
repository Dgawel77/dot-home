package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a media session is playing right now. Plain data; pure Java. */
public final class NowPlaying {
    public String title, artist;
    public boolean playing;
    public long positionMs, durationMs, changedAtMs;
    public String sessionKey;
    /** STATE_STOPPED / STATE_NONE / STATE_ERROR: nothing worth showing. Paused sessions stay eligible. */
    public boolean stopped;
    /** Playback speed and the SystemClock.elapsedRealtime() at which positionMs was valid. */
    public float speed = 1f;
    public long positionAtElapsedMs;
    /** The player's package, and the session's own "open the player" activity (an opaque PendingIntent), or null. */
    public String pkg;
    public Object sessionActivity;
    /** The session supports ACTION_SEEK_TO and knows its duration: the widget attaches its seek zones. */
    public boolean canSeek;

    /** What a tap on the album art opens. */
    public enum ArtTarget { NONE, SESSION, LAUNCH }

    /**
     * Prefers the session's own activity, else the package's launch intent, else nothing. A package that is not
     * allowed (null, or on the work-app denylist) never gets a target.
     */
    public static ArtTarget artTarget(String pkg, boolean hasSessionActivity, boolean hasLaunchIntent) {
        if (!packageAllowed(pkg)) return ArtTarget.NONE;
        if (hasSessionActivity) return ArtTarget.SESSION;
        return hasLaunchIntent ? ArtTarget.LAUNCH : ArtTarget.NONE;
    }

    /** True when a tap on the art would open the same thing: same package and the same session activity. */
    public static boolean sameClickTarget(NowPlaying a, NowPlaying b) {
        if (a == null || b == null) return a == b;
        return eq(a.pkg, b.pkg) && (a.sessionActivity == null ? b.sessionActivity == null : a.sessionActivity.equals(b.sessionActivity));
    }

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

    /** Work apps: their media sessions are never shown or controlled. */
    public static final Set<String> BLOCKED_PACKAGES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.microsoft.office.officehubrow",
        "com.microsoft.skydrive",
        "com.Slack",
        "com.okta.android.auth",
        "com.azure.authenticator",
        "com.pagerduty.android",
        "com.tripactions.alpha",
        "com.adpmobile.android")));

    /** False for a null package and for any package on the work-app denylist. */
    public static boolean packageAllowed(String pkg) {
        return pkg != null && !BLOCKED_PACKAGES.contains(pkg);
    }

    /** True when both show the same thing on the widget: title, artist, playing, stopped, duration (not position). */
    public static boolean sameDisplayed(NowPlaying a, NowPlaying b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return sameClickTarget(a, b) && a.playing == b.playing && a.stopped == b.stopped && a.durationMs == b.durationMs && a.canSeek == b.canSeek
            && eq(a.title, b.title) && eq(a.artist, b.artist);
    }

    /** A copy with the position re-anchored (a seek just moved it), so the widget redraws at once. */
    public NowPlaying withPosition(long pos, long nowElapsedMs) {
        NowPlaying n = new NowPlaying();
        n.title = title; n.artist = artist; n.playing = playing; n.durationMs = durationMs;
        n.changedAtMs = changedAtMs; n.sessionKey = sessionKey; n.stopped = stopped; n.speed = speed;
        n.pkg = pkg; n.sessionActivity = sessionActivity; n.canSeek = canSeek;
        n.positionMs = pos; n.positionAtElapsedMs = nowElapsedMs;
        return n;
    }

    private static boolean eq(String x, String y) { return x == null ? y == null : x.equals(y); }

    /** True when the session names what it plays: a title or an artist that is not blank. */
    public boolean hasMetadata() {
        return !blank(title) || !blank(artist);
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Whether the music widget shows n at all. A stopped session, or one without a title or artist (an idle player
     * that keeps an empty session, even one claiming to play), counts as nothing playing and leaves the widget blank.
     * A paused track with metadata still shows, so it can be resumed.
     */
    public static boolean showable(NowPlaying n) {
        return n != null && !n.stopped && n.hasMetadata();
    }

    /** The items that may be shown: see {@link #showable}. */
    public static List<NowPlaying> eligible(List<NowPlaying> all) {
        List<NowPlaying> out = new ArrayList<>();
        if (all == null) return out;
        for (NowPlaying n : all) if (showable(n)) out.add(n);
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
