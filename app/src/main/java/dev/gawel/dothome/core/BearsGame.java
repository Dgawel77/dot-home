package dev.gawel.dothome.core;

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
}
