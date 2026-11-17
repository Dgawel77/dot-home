package dev.gawel.dothome.core;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Parses ESPN's public NFL schedule and scoreboard JSON into {@link BearsGame}s. */
public final class EspnParser {
    public static final String SCHEDULE_URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams/chi/schedule";
    public static final String SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard";

    private EspnParser() {}

    public static List<BearsGame> schedule(String json) {
        List<BearsGame> out = new ArrayList<>();
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            List<Object> events = root == null ? null : MiniJson.arr(root.get("events"));
            if (events == null) return out;
            for (Object e : events) {
                BearsGame g = event(MiniJson.obj(e));
                if (g != null) out.add(g);
            }
        } catch (RuntimeException ex) {
            return Collections.emptyList();
        }
        return out;
    }

    public static BearsGame scoreboard(String json) {
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            List<Object> events = root == null ? null : MiniJson.arr(root.get("events"));
            if (events == null) return null;
            for (Object e : events) {
                BearsGame g = event(MiniJson.obj(e));
                if (g != null) return g;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    /** The game for one event, or null when CHI is not a competitor or the data is unusable. */
    private static BearsGame event(Map<String, Object> ev) {
        if (ev == null) return null;
        List<Object> comps = MiniJson.arr(ev.get("competitions"));
        Map<String, Object> comp = comps == null || comps.isEmpty() ? null : MiniJson.obj(comps.get(0));
        if (comp == null) return null;
        List<Object> teams = MiniJson.arr(comp.get("competitors"));
        if (teams == null) return null;
        Map<String, Object> chi = null, opp = null;
        for (Object t : teams) {
            Map<String, Object> c = MiniJson.obj(t);
            if (c == null) continue;
            Map<String, Object> team = MiniJson.obj(c.get("team"));
            String abbr = team == null ? null : MiniJson.str(team.get("abbreviation"), null);
            if ("CHI".equals(abbr)) chi = c; else opp = c;
        }
        if (chi == null || opp == null) return null;
        Map<String, Object> oppTeam = MiniJson.obj(opp.get("team"));
        String oppAbbr = oppTeam == null ? "" : MiniJson.str(oppTeam.get("abbreviation"), "");

        // the competition's status is the live one; the event-level status can lag behind
        Map<String, Object> st = MiniJson.obj(comp.get("status"));
        if (st == null) st = MiniJson.obj(ev.get("status"));
        if (st == null) return null;
        Map<String, Object> type = MiniJson.obj(st.get("type"));
        String state = type == null ? "" : MiniJson.str(type.get("state"), "");
        BearsGame.State s;
        if ("pre".equals(state)) s = BearsGame.State.PRE;
        else if ("in".equals(state)) s = BearsGame.State.IN;
        else if ("post".equals(state)) s = BearsGame.State.POST;
        else return null;

        String date = MiniJson.str(comp.get("date"), MiniJson.str(ev.get("date"), null));
        long kickoff = parseDate(date);
        if (kickoff < 0) return null;
        return new BearsGame(s, kickoff, oppAbbr, "home".equals(MiniJson.str(chi.get("homeAway"), "")),
                score(chi.get("score")), score(opp.get("score")),
                (int) MiniJson.num(st.get("period"), 0),
                MiniJson.str(st.get("displayClock"), ""),
                type == null ? "" : MiniJson.str(type.get("shortDetail"), ""));
    }

    /** Plain string (scoreboard) or {displayValue} object (schedule); -1 when absent. */
    private static int score(Object o) {
        String s = o instanceof Map ? MiniJson.str(MiniJson.obj(o).get("displayValue"), null) : MiniJson.str(o, null);
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "2026-11-22T18:00Z", with or without seconds; -1 on failure. */
    static long parseDate(String s) {
        if (s == null) return -1;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String f : fmts) {
            SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            sdf.setLenient(false);
            try {
                return sdf.parse(s).getTime();
            } catch (ParseException e) {
                // try next
            }
        }
        return -1;
    }
}
