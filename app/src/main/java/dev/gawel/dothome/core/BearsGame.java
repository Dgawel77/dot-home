package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/** One Bears game as parsed from ESPN. Scores are -1 when unknown. */
public final class BearsGame {
    public enum State { PRE, IN, POST }

    public final State state;
    public final long kickoffMs;
    public final String opp;
    public final boolean home;
    public final int chi, oppScore;
    public final int period;
    public final String clock;
    public final String detail;

    public BearsGame(State state, long kickoffMs, String opp, boolean home, int chi, int oppScore,
                     int period, String clock, String detail) {
        this.state = state;
        this.kickoffMs = kickoffMs;
        this.opp = opp;
        this.home = home;
        this.chi = chi;
        this.oppScore = oppScore;
        this.period = period;
        this.clock = clock;
        this.detail = detail;
    }

    /** One line, tab-separated: the compact form the widget caches instead of ESPN's large JSON. */
    public static String encode(BearsGame g) {
        return g.state + "\t" + g.kickoffMs + "\t" + clean(g.opp) + "\t" + (g.home ? 1 : 0) + "\t" + g.chi + "\t"
                + g.oppScore + "\t" + g.period + "\t" + clean(g.clock) + "\t" + clean(g.detail);
    }

    /** The game from {@link #encode}, or null when the line is missing or malformed. */
    public static BearsGame decode(String line) {
        if (line == null) return null;
        String[] f = line.split("\t", -1);
        if (f.length != 9) return null;
        try {
            return new BearsGame(State.valueOf(f[0]), Long.parseLong(f[1]), f[2], "1".equals(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]), f[7], f[8]);
        } catch (IllegalArgumentException e) { // includes NumberFormatException
            return null;
        }
    }

    public static String encodeAll(List<BearsGame> games) {
        StringBuilder sb = new StringBuilder();
        for (BearsGame g : games) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(encode(g));
        }
        return sb.toString();
    }

    /** The games from {@link #encodeAll}; malformed lines are skipped. Never null. */
    public static List<BearsGame> decodeAll(String text) {
        List<BearsGame> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            BearsGame g = decode(line);
            if (g != null) out.add(g);
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
