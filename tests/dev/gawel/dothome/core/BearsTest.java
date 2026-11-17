package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

public final class BearsTest {
    private static final TimeZone CHI = TimeZone.getTimeZone("America/Chicago");

    private static String fixture(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read fixture " + name + ": " + e);
        }
    }

    private static long local(int y, int mo, int d, int h, int mi) {
        GregorianCalendar c = new GregorianCalendar(CHI);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static List<BearsGame> sched() { return EspnParser.schedule(fixture("espn-chi-schedule.json")); }

    public static void testScheduleParses() {
        List<BearsGame> g = sched();
        T.eq(3, g.size(), "count");
        T.eq(BearsGame.State.POST, g.get(0).state, "g0 state");
        T.eq(27, g.get(0).chi, "g0 chi");
        T.eq(24, g.get(0).oppScore, "g0 opp");
        T.eq("TB", g.get(0).opp, "g0 opp name");
        T.check(g.get(0).home, "g0 home");
        T.eq(BearsGame.State.PRE, g.get(1).state, "g1 state");
        T.eq("NO", g.get(1).opp, "g1 opp");
        T.check(g.get(1).home, "g1 home");
        T.eq(-1, g.get(1).chi, "g1 chi unknown");
        T.eq(-1, g.get(1).oppScore, "g1 opp unknown");
        T.eq(1795370400000L, g.get(1).kickoffMs, "g1 kickoff");
        T.eq("DET", g.get(2).opp, "g2 opp");
        T.check(!g.get(2).home, "g2 away");
    }

    public static void testEveningKickoffIsLocalDay() {
        List<BearsGame> g = sched();
        BearsGame a = BearsView.today(g, local(2026, 11, 8, 22, 0), CHI);
        T.check(a != null && "TB".equals(a.opp), "evening game found");
        T.eq(BearsView.Mode.FINAL, BearsView.mode(a), "final");
        BearsGame b = BearsView.today(g, local(2026, 11, 9, 10, 0), CHI);
        T.eq(null, b, "next morning none");
        T.eq(BearsView.Mode.BLANK, BearsView.mode(b), "blank");
    }

    public static void testPreText() {
        List<BearsGame> g = sched();
        BearsGame a = BearsView.today(g, local(2026, 11, 22, 9, 0), CHI);
        T.eq("SUN 12:00 · vs NO", BearsView.preText(a, CHI), "home");
        BearsGame b = BearsView.today(g, local(2026, 11, 26, 9, 0), CHI);
        T.eq("THU 12:00 · @ DET", BearsView.preText(b, CHI), "away");
    }

    public static void testScoreboardHalftime() {
        BearsGame g = EspnParser.scoreboard(fixture("espn-scoreboard-halftime.json"));
        T.check(g != null, "found");
        T.eq(10, g.chi, "chi");
        T.eq(17, g.oppScore, "opp");
        T.eq("LAC", g.opp, "opp abbr");
        T.eq(BearsGame.State.IN, g.state, "state");
        T.eq("HALF", BearsView.liveLabel(g), "label");
        T.eq(BearsView.Mode.LIVE, BearsView.mode(g), "mode");
    }

    public static void testScoreboardQ3() {
        BearsGame g = EspnParser.scoreboard(fixture("espn-scoreboard-q3.json"));
        T.check(g != null, "found");
        T.eq("Q3 8:42", BearsView.liveLabel(g), "label");
    }

    public static void testPollPolicy() {
        long k = 100_000_000L;
        T.check(BearsView.pollNow(BearsView.Mode.LIVE, k, k + 100_000, k + 100_000 - 56_000), "live 56s");
        T.check(!BearsView.pollNow(BearsView.Mode.LIVE, k, k + 100_000, k + 100_000 - 30_000), "live 30s");
        T.check(BearsView.pollNow(BearsView.Mode.PRE, k, k - 300_000, k - 360_000), "pre 5min");
        T.check(!BearsView.pollNow(BearsView.Mode.PRE, k, k - 7_200_000, k - 10_800_000), "pre 2h");
        long now = k;
        T.check(BearsView.pollNow(BearsView.Mode.BLANK, 0, now, now - 21_600_001L), "blank 6h");
        T.check(!BearsView.pollNow(BearsView.Mode.BLANK, 0, now, now - 21_600_000L + 1), "blank <6h");
    }

    public static void testGarbageIsNull() {
        T.eq(0, EspnParser.schedule("{}").size(), "{}");
        T.eq(0, EspnParser.schedule("<html>").size(), "html");
        T.eq(0, EspnParser.schedule(null).size(), "null");
        T.eq(null, EspnParser.scoreboard("<html>"), "scoreboard html");
        T.eq(null, EspnParser.scoreboard(null), "scoreboard null");
    }
    private static BearsGame game(BearsGame.State st, long kickoff, int chi, int opp) {
        return new BearsGame(st, kickoff, "GB", false, chi, opp, 3, "8:42", "3rd Quarter");
    }

    public static void testPickPrefersScoreboardForTodaysGame() {
        List<BearsGame> g = sched();
        long now = local(2026, 11, 22, 12, 30);
        BearsGame board = new BearsGame(BearsGame.State.IN, 1795370400000L, "NO", true, 7, 3, 1, "2:10", "1st Quarter");
        T.eq(board, BearsView.pick(g, board, now, CHI), "scoreboard wins on the same kickoff");
        T.eq(BearsView.today(g, now, CHI), BearsView.pick(g, null, now, CHI), "schedule without a board");
        BearsGame other = game(BearsGame.State.POST, local(2026, 11, 16, 19, 15), 21, 17);
        T.eq(BearsView.today(g, now, CHI), BearsView.pick(g, other, now, CHI), "an old board does not hide today's game");
        T.eq(null, BearsView.pick(g, other, local(2026, 11, 18, 9, 0), CHI), "nothing today: blank");
        T.eq(null, BearsView.pick(null, null, now, CHI), "no data");
    }

    public static void testPickKeepsLiveGamePastMidnight() {
        long kickoff = local(2026, 11, 16, 19, 15);
        BearsGame sched = game(BearsGame.State.PRE, kickoff, -1, -1);
        BearsGame live = game(BearsGame.State.IN, kickoff, 21, 17);
        List<BearsGame> s = java.util.Collections.singletonList(sched);
        long after = local(2026, 11, 17, 0, 20);
        T.eq(null, BearsView.today(s, after, CHI), "the schedule alone goes blank at midnight");
        T.eq(live, BearsView.pick(s, live, after, CHI), "a live board stays");
        T.eq(BearsView.Mode.LIVE, BearsView.mode(BearsView.pick(s, live, after, CHI)), "live");
        BearsGame fin = game(BearsGame.State.POST, kickoff, 24, 17);
        T.eq(null, BearsView.pick(s, fin, after, CHI), "a final board ends with the day");
        T.eq(null, BearsView.pick(s, live, kickoff + 13 * 3_600_000L, CHI), "a stale live board expires");
    }

    public static void testCodecRoundTrip() {
        BearsGame a = new BearsGame(BearsGame.State.IN, 1795370400000L, "GB", false, 21, 17, 3, "8:42", "3rd\t|Quarter");
        BearsGame b = new BearsGame(BearsGame.State.PRE, 1795370400001L, "NO", true, -1, -1, 0, "", "");
        List<BearsGame> back = BearsGame.decodeAll(BearsGame.encodeAll(java.util.Arrays.asList(a, b)));
        T.eq(2, back.size(), "count");
        BearsGame x = back.get(0);
        T.eq(BearsGame.State.IN, x.state, "state");
        T.eq(1795370400000L, x.kickoffMs, "kickoff");
        T.eq("GB", x.opp, "opp");
        T.check(!x.home, "away");
        T.eq(21, x.chi, "chi");
        T.eq(17, x.oppScore, "opp score");
        T.eq(3, x.period, "period");
        T.eq("8:42", x.clock, "clock");
        T.eq("3rd |Quarter", x.detail, "tab dropped");
        T.check(back.get(1).home, "home");
        T.eq(-1, back.get(1).chi, "unknown score");
        T.eq("", back.get(1).clock, "empty clock");
        BearsGame one = BearsGame.decode(BearsGame.encode(a));
        T.eq(21, one.chi, "single");
        T.eq(null, BearsGame.decode(null), "null");
        T.eq(null, BearsGame.decode("garbage"), "garbage");
        T.eq(0, BearsGame.decodeAll(null).size(), "null list");
        T.eq(1, BearsGame.decodeAll(BearsGame.encode(a) + "\nbad").size(), "bad line skipped");
    }
}
