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
}
