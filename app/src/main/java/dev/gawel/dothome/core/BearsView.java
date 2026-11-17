package dev.gawel.dothome.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Game-day rules for the Bears widget: which game, what mode, what text, when to poll. */
public final class BearsView {
    public enum Mode { BLANK, PRE, LIVE, FINAL }

    private static final long PRE_LEAD_MS = 10 * 60_000L;
    private static final long LIVE_POLL_MS = 55_000L;
    private static final long IDLE_POLL_MS = 6 * 3_600_000L;

    private BearsView() {}

    private static int dayKey(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    public static BearsGame today(List<BearsGame> games, long nowMs, TimeZone tz) {
        if (games == null) return null;
        int now = dayKey(nowMs, tz);
        for (BearsGame g : games) if (dayKey(g.kickoffMs, tz) == now) return g;
        return null;
    }

    public static Mode mode(BearsGame g) {
        if (g == null) return Mode.BLANK;
        switch (g.state) {
            case PRE: return Mode.PRE;
            case IN: return Mode.LIVE;
            default: return Mode.FINAL;
        }
    }

    public static String preText(BearsGame g, TimeZone tz) {
        SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.US);
        SimpleDateFormat time = new SimpleDateFormat("h:mm", Locale.US);
        day.setTimeZone(tz);
        time.setTimeZone(tz);
        Date d = new Date(g.kickoffMs);
        return day.format(d).toUpperCase(Locale.US) + " " + time.format(d)
                + " · " + (g.home ? "vs " : "@ ") + g.opp;
    }

    public static String liveLabel(BearsGame g) {
        if ("Halftime".equals(g.detail)) return "HALF";
        return (g.period >= 5 ? "OT" : "Q" + g.period) + " " + g.clock;
    }

    public static boolean pollNow(Mode m, long kickoffMs, long nowMs, long lastFetchMs) {
        long since = nowMs - lastFetchMs;
        boolean active = m == Mode.LIVE || (m == Mode.PRE && nowMs >= kickoffMs - PRE_LEAD_MS);
        return active ? since >= LIVE_POLL_MS : since >= IDLE_POLL_MS;
    }
}
