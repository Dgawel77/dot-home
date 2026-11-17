package dev.gawel.dothome;

import android.content.Context;

import java.util.List;
import java.util.TimeZone;

import dev.gawel.dothome.core.BearsGame;
import dev.gawel.dothome.core.BearsView;
import dev.gawel.dothome.core.EspnParser;

/**
 * The Bears schedule and the live scoreboard game, cached in prefs in BearsGame's compact form (not ESPN's JSON):
 * "bears.schedule" (the season), "bears.board" (the last scoreboard game) and "bears.at" (when the schedule was
 * last fetched with a 2xx). A failed or empty fetch keeps the cache.
 */
final class BearsRepo {
    private static final String K_SCHED = "bears.schedule", K_BOARD = "bears.board", K_AT = "bears.at";
    private static final long SCHED_MS = 6L * 3_600_000;
    /** After a failed schedule fetch, wait this long before the next try (the tick runs every minute). */
    private static final long SCHED_RETRY_MS = 15L * 60_000;

    /** Executor-only state: when the schedule was last tried and the scoreboard last fetched (0 after a restart). */
    private static long schedTriedMs, boardAtMs;

    private BearsRepo() {}

    /** The game to show now (see {@link BearsView#pick}), or null when the widget is blank. Reads the cache only. */
    static BearsGame current(Context ctx, long nowMs, TimeZone tz) {
        PrefsStore prefs = new PrefsStore(ctx);
        List<BearsGame> sched = BearsGame.decodeAll(prefs.get(K_SCHED, ""));
        BearsGame board = BearsGame.decode(prefs.get(K_BOARD, null));
        return BearsView.pick(sched, board, nowMs, tz);
    }

    /**
     * Blocking (network), on LiveFeeds' executor only. Fetches the schedule when it is 6 h old, then, on game day, the
     * scoreboard when {@link BearsView#pollNow} says so.
     */
    static void refresh(Context ctx, long nowMs) {
        PrefsStore prefs = new PrefsStore(ctx);
        long at;
        try {
            at = Long.parseLong(prefs.get(K_AT, "0"));
        } catch (NumberFormatException e) {
            at = 0;
        }
        if (nowMs - at >= SCHED_MS && nowMs - schedTriedMs >= SCHED_RETRY_MS) {
            schedTriedMs = nowMs;
            String json = LiveFeeds.get(EspnParser.SCHEDULE_URL, "espn schedule");
            List<BearsGame> games = json == null ? null : EspnParser.schedule(json);
            if (games != null && !games.isEmpty()) prefs.put(K_SCHED, BearsGame.encodeAll(games));
            // a 2xx counts as fetched even when it parses to nothing (off-season, or a format change): the next try
            // waits the normal 6 h, not the 15 min failure retry; the cached schedule is kept
            if (json != null) prefs.put(K_AT, String.valueOf(nowMs));
        }
        BearsGame g = current(ctx, nowMs, TimeZone.getDefault());
        if (g == null) return; // not game day: no scoreboard
        if (!BearsView.pollNow(BearsView.mode(g), g.kickoffMs, nowMs, boardAtMs)) return;
        boardAtMs = nowMs; // also on failure: the next try waits for the next poll slot
        String json = LiveFeeds.get(EspnParser.SCOREBOARD_URL, "espn scoreboard");
        BearsGame b = json == null ? null : EspnParser.scoreboard(json);
        if (b != null) prefs.put(K_BOARD, BearsGame.encode(b));
    }
}
