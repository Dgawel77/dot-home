package dev.gawel.dothome.test;

import java.util.ArrayList;
import java.util.List;

import dev.gawel.dothome.core.NowPlaying;
import dev.gawel.dothome.core.TextFit;

public final class NowPlayingTest {
    private static NowPlaying np(String key, String title, boolean playing, long changedAtMs) {
        NowPlaying n = new NowPlaying();
        n.sessionKey = key;
        n.title = title;
        n.playing = playing;
        n.changedAtMs = changedAtMs;
        return n;
    }

    public static void testChoosePrefersPlaying() {
        List<NowPlaying> l = new ArrayList<>();
        l.add(np("a", "A", false, 900));
        l.add(np("b", "B", true, 100));
        l.add(np("c", "C", true, 50));
        T.eq("b", NowPlaying.choose(l).sessionKey, "first playing item wins over newer paused");
    }

    public static void testChooseLatestWhenNonePlaying() {
        List<NowPlaying> l = new ArrayList<>();
        l.add(np("a", "A", false, 100));
        l.add(np("b", "B", false, 900));
        l.add(np("c", "C", false, 500));
        T.eq("b", NowPlaying.choose(l).sessionKey, "latest changedAtMs");
    }

    public static void testChooseEmptyNull() {
        T.check(NowPlaying.choose(new ArrayList<NowPlaying>()) == null, "empty list");
        T.check(NowPlaying.choose(null) == null, "null list");
    }

    public static void testNullTitleSafe() {
        List<NowPlaying> l = new ArrayList<>();
        l.add(np("a", null, true, 1));
        NowPlaying r = NowPlaying.choose(l);
        T.check(r != null && r.sessionKey.equals("a"), "item with null title returned");
        T.eq("", TextFit.fit(r.title, 100, s -> s.length() * 10f), "null title fits to empty");
    }

    public static void testPositionExtrapolatesWhilePlaying() {
        NowPlaying n = np("a", "A", true, 0);
        n.positionMs = 10_000;
        n.durationMs = 300_000;
        n.positionAtElapsedMs = 1_000;
        n.speed = 1f;
        T.eq(10_000L, n.positionAt(1_000), "no time elapsed");
        T.eq(10_000L + 180_000L, n.positionAt(1_000 + 180_000), "3 minutes of ticks");
        n.speed = 2f;
        T.eq(10_000L + 20_000L, n.positionAt(1_000 + 10_000), "speed applied");
    }

    public static void testPositionClamped() {
        NowPlaying n = np("a", "A", true, 0);
        n.positionMs = 290_000;
        n.durationMs = 300_000;
        n.positionAtElapsedMs = 1_000;
        T.eq(300_000L, n.positionAt(1_000 + 600_000), "clamped to duration");
        T.eq(290_000L, n.positionAt(0), "clock going backwards does not rewind");
        n.positionMs = -5;
        T.eq(0L, n.positionAt(1_000), "never negative");
    }

    public static void testPausedPositionFrozen() {
        NowPlaying n = np("a", "A", false, 0);
        n.positionMs = 42_000;
        n.durationMs = 300_000;
        n.positionAtElapsedMs = 1_000;
        T.eq(42_000L, n.positionAt(1_000 + 90_000), "paused does not advance");
    }

    public static void testEligibleDropsStoppedKeepsPaused() {
        List<NowPlaying> l = new ArrayList<>();
        NowPlaying stopped = np("s", "S", false, 900);
        stopped.stopped = true;
        l.add(stopped);
        l.add(np("p", "P", false, 100));
        List<NowPlaying> e = NowPlaying.eligible(l);
        T.eq(1, e.size(), "stopped dropped");
        T.eq("p", NowPlaying.choose(e).sessionKey, "paused still shown");
        l.remove(1);
        T.check(NowPlaying.choose(NowPlaying.eligible(l)) == null, "only stopped -> hidden");
        T.eq(0, NowPlaying.eligible(null).size(), "null safe");
    }

    public static void testPackageDenylist() {
        T.eq(10, NowPlaying.BLOCKED_PACKAGES.size(), "denylist size");
        String[] blocked = {"com.google.android.gm", "com.microsoft.office.outlook", "com.microsoft.office.officehubrow",
            "com.microsoft.skydrive", "com.Slack", "com.okta.android.auth", "com.azure.authenticator",
            "com.pagerduty.android", "com.tripactions.alpha", "com.adpmobile.android"};
        for (String p : blocked) T.check(!NowPlaying.packageAllowed(p), "blocked " + p);
        T.check(NowPlaying.packageAllowed("com.spotify.music"), "music app allowed");
        T.check(!NowPlaying.packageAllowed(null), "null package not allowed");
    }

    public static void testSameDisplayedIgnoresPosition() {
        NowPlaying a = np("a", "T", true, 1);
        a.artist = "X";
        a.durationMs = 1000;
        NowPlaying b = np("a", "T", true, 2);
        b.artist = "X";
        b.durationMs = 1000;
        b.positionMs = 500;
        T.check(NowPlaying.sameDisplayed(a, b), "position and changedAt do not count");
        b.playing = false;
        T.check(!NowPlaying.sameDisplayed(a, b), "playing counts");
        b.playing = true;
        b.title = "U";
        T.check(!NowPlaying.sameDisplayed(a, b), "title counts");
        T.check(NowPlaying.sameDisplayed(null, null), "both null");
        T.check(!NowPlaying.sameDisplayed(a, null), "one null");
    }
}
