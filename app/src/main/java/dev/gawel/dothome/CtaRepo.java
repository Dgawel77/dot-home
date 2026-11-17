package dev.gawel.dothome;

import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.List;

import dev.gawel.dothome.core.Arrival;
import dev.gawel.dothome.core.BlueLineStations;
import dev.gawel.dothome.core.CtaBoard;
import dev.gawel.dothome.core.CtaParser;
import dev.gawel.dothome.core.CtaUrls;
import dev.gawel.dothome.core.Place;
import dev.gawel.dothome.core.PlatformBoard;

/**
 * CTA Train Tracker arrivals for the Dot Blue Line widget. Prefs: "cta.key" (the user's key, a secret: never logged,
 * never in a message), "cta.station" (a pinned mapid, -1 = the nearest station to the cached place), "cta.board"
 * (the last good response, as "mapid\njson") and "cta.at" (when it was fetched). A response with an errCd other
 * than 0, or a failed fetch, keeps the last good board; a board 10 min old or more is not shown.
 */
final class CtaRepo {
    private static final String TAG = "DotHome";
    static final String K_KEY = "cta.key", K_STATION = "cta.station", K_BOARD = "cta.board", K_AT = "cta.at";
    private static final long FETCH_MS = 55_000, KEEP_MS = 10L * 60_000;
    private static final double MAX_KM = 10;

    /** When arrivals were last fetched (0 after a restart or a key/station change). Written by Setup's reset too. */
    private static volatile long fetchedMs;
    /** Executor-only: the last errCd logged, so a bad key logs once rather than every minute. */
    private static int loggedErr;

    enum Mode { NO_KEY, BLANK, BOARD }

    /** What the widget shows now. For BOARD, the station and both directions with minutes aged since the fetch. */
    static final class Shown {
        final Mode mode;
        final int mapid;
        final String label;
        final List<Arrival> ohare, forestPark;

        Shown(Mode mode, int mapid, String label, List<Arrival> ohare, List<Arrival> forestPark) {
            this.mode = mode;
            this.mapid = mapid;
            this.label = label;
            this.ohare = ohare;
            this.forestPark = forestPark;
        }
    }

    private static final Shown NO_KEY = new Shown(Mode.NO_KEY, -1, "", Collections.<Arrival>emptyList(),
        Collections.<Arrival>emptyList());
    private static final Shown BLANK = new Shown(Mode.BLANK, -1, "", Collections.<Arrival>emptyList(),
        Collections.<Arrival>emptyList());

    private CtaRepo() {}

    static boolean hasKey(Context ctx) {
        return !key(new PrefsStore(ctx)).isEmpty();
    }

    private static String key(PrefsStore prefs) {
        String k = prefs.get(K_KEY, "");
        return k == null ? "" : k.trim();
    }

    /** Stores the trimmed key (an empty one removes it) and drops the board fetched with the old key. Main thread. */
    static void saveKey(Context ctx, String key) {
        PrefsStore prefs = new PrefsStore(ctx);
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) prefs.remove(K_KEY);
        else prefs.put(K_KEY, k);
        reset(prefs);
    }

    /** The pinned mapid, or -1 for the nearest station. */
    static int pinned(Context ctx) {
        try {
            int id = Integer.parseInt(new PrefsStore(ctx).get(K_STATION, "-1"));
            return BlueLineStations.index(id) >= 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Pins mapid (-1 = nearest) and drops the old station's board. */
    static void pin(Context ctx, int mapid) {
        PrefsStore prefs = new PrefsStore(ctx);
        prefs.put(K_STATION, String.valueOf(BlueLineStations.index(mapid) >= 0 ? mapid : -1));
        reset(prefs);
    }

    private static void reset(PrefsStore prefs) {
        prefs.remove(K_BOARD);
        prefs.remove(K_AT);
        fetchedMs = 0;
    }

    /** The pinned station, else the nearest within 10 km of the cached place (no location request), else -1. */
    static int station(Context ctx) {
        int pin = pinned(ctx);
        if (pin != -1) return pin;
        Place p = LocationSource.cached(ctx);
        return p == null ? -1 : BlueLineStations.nearest(p.lat, p.lon, MAX_KM);
    }

    /** What to show now, from the cache only. */
    static Shown current(Context ctx, long nowMs) {
        PrefsStore prefs = new PrefsStore(ctx);
        if (key(prefs).isEmpty()) return NO_KEY;
        int mapid = station(ctx);
        if (mapid == -1) return BLANK;
        String cached = prefs.get(K_BOARD, null);
        long at;
        try {
            at = Long.parseLong(prefs.get(K_AT, "0"));
        } catch (NumberFormatException e) {
            at = 0;
        }
        long age = nowMs - at;
        if (cached == null || age < 0 || age >= KEEP_MS) return BLANK;
        int nl = cached.indexOf('\n');
        if (nl < 0 || !String.valueOf(mapid).equals(cached.substring(0, nl))) return BLANK;
        CtaBoard b = CtaParser.parse(cached.substring(nl + 1));
        if (b.errCd != 0) return BLANK;
        int ageMin = (int) (age / 60_000);
        return new Shown(Mode.BOARD, mapid, BlueLineStations.label(mapid), PlatformBoard.aged(b.ohare, ageMin),
            PlatformBoard.aged(b.forestPark, ageMin));
    }

    /** Blocking (network), on LiveFeeds' executor only: fetches the arrivals when a key is set and 55 s have passed. */
    static void refresh(Context ctx, long nowMs) {
        PrefsStore prefs = new PrefsStore(ctx);
        String key = key(prefs);
        if (key.isEmpty()) return;
        int mapid = station(ctx);
        if (mapid == -1) return;
        if (nowMs - fetchedMs < FETCH_MS && nowMs >= fetchedMs) return;
        fetchedMs = nowMs; // also on failure: the next try waits its 55 s
        String json = LiveFeeds.get(CtaUrls.arrivals(key, mapid), "cta arrivals"); // logs the HTTP status only
        if (json == null) return;
        CtaBoard b = CtaParser.parse(json);
        if (b.errCd != 0) {
            if (b.errCd != loggedErr) Log.w(TAG, "cta errCd " + b.errCd);
            loggedErr = b.errCd;
            return;
        }
        loggedErr = 0;
        // the key changed during the fetch: Setup's reset wins and the next tick fetches with the new key
        if (!key.equals(key(prefs))) return;
        prefs.put(K_BOARD, mapid + "\n" + json);
        prefs.put(K_AT, String.valueOf(nowMs));
    }
}
