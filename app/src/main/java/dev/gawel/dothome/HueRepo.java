package dev.gawel.dothome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.gawel.dothome.core.HueGroup;
import dev.gawel.dothome.core.HueParser;
import dev.gawel.dothome.core.HueRows;
import dev.gawel.dothome.core.HueState;

/**
 * Dot Hue's state and the bridge calls behind it. Prefs (private, never logged):
 * <ul>
 *   <li>"hue.key": the app key, a secret.</li>
 *   <li>"hue.host": the bridge's IPv4 address.</li>
 *   <li>"hue.id": its bridge id.</li>
 *   <li>"hue.pin": the pinned certificate.</li>
 *   <li>"hue.rooms": the picked room/zone ids.</li>
 *   <li>"hue.rooms.json" and "hue.zones.json": the cached group lists.</li>
 *   <li>"hue.states.json": the last grouped_light response.</li>
 * </ul>
 * What the widget shows is an in-memory state map: the cache, then refreshes, with taps applied at once
 * (optimistic) and guarded by {@link HueRows.Seq}. Network work runs on this class's own executor (a tap never
 * waits behind a CTA fetch). Refreshes happen at most every 55 s; the group lists are re-read every 6 h. After a
 * connect failure the bridge is re-found by its id over mDNS, at most every 10 min.
 */
final class HueRepo {
    private static final String TAG = "DotHome";
    static final String K_KEY = "hue.key", K_HOST = "hue.host", K_ID = "hue.id", K_PIN = "hue.pin";
    static final String K_SEL = "hue.rooms", K_ROOMS = "hue.rooms.json", K_ZONES = "hue.zones.json";
    static final String K_STATES = "hue.states.json";
    private static final long MINUTE_MS = 55_000, GROUPS_MS = 6L * 3_600_000, REDISCOVER_MS = 10L * 60_000;
    private static final long DISCOVER_WAIT_MS = 6_000;
    private static final String LIGHTS = "/clip/v2/resource/grouped_light";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dothome-hue");
        t.setDaemon(true);
        return t;
    });
    private static final HueRows.Seq SEQ = new HueRows.Seq();
    private static final Object LOCK = new Object();
    /** Guarded by LOCK: what the rows show, by grouped_light id; null until loaded from the cache. */
    private static Map<String, HueState> shown;
    private static volatile boolean reachable = true;
    private static volatile long refreshedMs, groupsMs, rediscoveredMs;
    private static final AtomicBoolean MINUTE_PENDING = new AtomicBoolean(false);

    enum Mode { UNPAIRED, NO_ROOMS, ROWS }

    static final class Shown {
        final Mode mode;
        final List<HueRows.Row> rows;
        final boolean reachable;

        Shown(Mode mode, List<HueRows.Row> rows, boolean reachable) {
            this.mode = mode;
            this.rows = rows;
            this.reachable = reachable;
        }
    }

    private HueRepo() {}

    static boolean paired(Context ctx) {
        PrefsStore p = new PrefsStore(ctx);
        return !blank(p.get(K_KEY, null)) && !blank(p.get(K_HOST, null)) && !blank(p.get(K_PIN, null));
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Stores a new pairing. A different bridge drops the picks and caches of the old one. */
    static void savePairing(Context ctx, String host, String bridgeId, String key, String pin) {
        PrefsStore p = new PrefsStore(ctx);
        String old = p.get(K_ID, null);
        if (old == null || !old.equals(bridgeId)) clearCaches(p);
        p.put(K_HOST, host);
        p.put(K_ID, bridgeId == null ? "" : bridgeId);
        p.put(K_KEY, key);
        p.put(K_PIN, pin);
        resetMemory();
    }

    /** Forgets the bridge: key, pin, picks and caches. */
    static void forget(Context ctx) {
        PrefsStore p = new PrefsStore(ctx);
        p.remove(K_KEY);
        p.remove(K_HOST);
        p.remove(K_ID);
        p.remove(K_PIN);
        clearCaches(p);
        resetMemory();
    }

    private static void clearCaches(PrefsStore p) {
        p.remove(K_SEL);
        p.remove(K_ROOMS);
        p.remove(K_ZONES);
        p.remove(K_STATES);
    }

    private static void resetMemory() {
        synchronized (LOCK) {
            shown = null;
        }
        reachable = true;
        refreshedMs = groupsMs = rediscoveredMs = 0;
    }

    /** The bridge's rooms then zones, from the cache. */
    static List<HueGroup> groups(Context ctx) {
        PrefsStore p = new PrefsStore(ctx);
        List<HueGroup> out = new ArrayList<>(HueParser.groups(p.get(K_ROOMS, null), false));
        out.addAll(HueParser.groups(p.get(K_ZONES, null), true));
        return out;
    }

    static List<String> selection(Context ctx) {
        return HueRows.decode(new PrefsStore(ctx).get(K_SEL, null));
    }

    static void setSelection(Context ctx, List<String> sel) {
        new PrefsStore(ctx).put(K_SEL, HueRows.encode(sel));
    }

    /** What the widget shows now, from memory and the cache only. */
    static Shown current(Context ctx) {
        if (!paired(ctx)) return new Shown(Mode.UNPAIRED, Collections.<HueRows.Row>emptyList(), true);
        List<HueRows.Row> rows = HueRows.rows(selection(ctx), groups(ctx), states(ctx));
        return new Shown(rows.isEmpty() ? Mode.NO_ROOMS : Mode.ROWS, rows, reachable);
    }

    private static Map<String, HueState> states(Context ctx) {
        synchronized (LOCK) {
            if (shown == null) shown = HueParser.states(new PrefsStore(ctx).get(K_STATES, null));
            return shown;
        }
    }

    /** Main thread, once a minute while the screen is on: a refresh when 55 s have passed. */
    static void onMinute(Context ctx, Runnable redraw) {
        if (!paired(ctx)) return;
        long now = System.currentTimeMillis();
        if (now - refreshedMs < MINUTE_MS && now >= refreshedMs) return;
        if (!MINUTE_PENDING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        final boolean groupsToo = now - groupsMs >= GROUPS_MS || now < groupsMs;
        if (!submit(() -> {
            try {
                refresh(app, groupsToo);
                redraw.run();
            } finally {
                MINUTE_PENDING.set(false);
            }
        })) MINUTE_PENDING.set(false);
    }

    /** Refreshes now (and the group lists too when asked), then runs redraw on the executor. Any thread. */
    static void kick(Context ctx, boolean groupsToo, Runnable redraw) {
        final Context app = ctx.getApplicationContext();
        submit(() -> {
            refresh(app, groupsToo);
            redraw.run();
        });
    }

    private static boolean submit(Runnable r) {
        try {
            EXEC.execute(() -> {
                try {
                    r.run();
                } catch (Throwable e) { // an Error here would otherwise kill the shared wallpaper process
                    Log.w(TAG, "hue error: " + e.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "hue busy: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Blocking (network), on the executor only. */
    private static void refresh(Context ctx, boolean groupsToo) {
        PrefsStore p = new PrefsStore(ctx);
        if (!paired(ctx)) return;
        String key = p.get(K_KEY, ""), host = p.get(K_HOST, ""), pin = p.get(K_PIN, ""), id = p.get(K_ID, "");
        long now = System.currentTimeMillis();
        refreshedMs = now; // also on failure: the next try waits its 55 s
        long mark = SEQ.mark();
        HueBridge b = new HueBridge(host, pin);
        HueBridge.Reply r = b.get(LIGHTS, key, "states");
        if (r.code == HueBridge.UNREACHABLE && !blank(id) && now - rediscoveredMs >= REDISCOVER_MS) {
            rediscoveredMs = now;
            String moved = rediscover(ctx, id);
            if (moved != null && !moved.equals(host)) {
                p.put(K_HOST, moved);
                b = new HueBridge(moved, pin);
                r = b.get(LIGHTS, key, "states");
            }
        }
        if (r.code == 401 || r.code == 403) { // the key was removed in the Hue app: pair again
            Log.w(TAG, "hue key rejected, unpaired");
            forget(ctx);
            return;
        }
        if (!r.ok()) {
            reachable = false;
            return;
        }
        reachable = true;
        Map<String, HueState> fetched = HueParser.states(r.body);
        synchronized (LOCK) {
            shown = HueRows.apply(states(ctx), fetched, SEQ, mark);
        }
        p.put(K_STATES, r.body);
        if (groupsToo || groups(ctx).isEmpty()) {
            HueBridge.Reply rooms = b.get("/clip/v2/resource/room", key, "rooms");
            HueBridge.Reply zones = rooms.ok() ? b.get("/clip/v2/resource/zone", key, "zones") : rooms;
            if (rooms.ok() && zones.ok()) {
                p.put(K_ROOMS, rooms.body);
                p.put(K_ZONES, zones.body);
                groupsMs = now;
                List<String> sel = selection(ctx), kept = HueRows.prune(sel, groups(ctx));
                if (!kept.equals(sel)) setSelection(ctx, kept);
            }
        }
    }

    /** Executor only: re-finds the bridge by id over mDNS (waits up to 6 s); its IPv4 host, or null. */
    private static String rediscover(Context ctx, String bridgeId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> found = new AtomicReference<>();
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> HueDiscovery.find(app, bridgeId, DISCOVER_WAIT_MS - 500,
            new HueDiscovery.Callback() {
                @Override
                public void onFound(String host, String id) {
                    found.set(host);
                    latch.countDown();
                }

                @Override
                public void onNone() {
                    latch.countDown();
                }
            }));
        try {
            latch.await(DISCOVER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    /**
     * A tap on a row (dot -1 = the name: toggle; 1..5 = brightness): shows it at once (redraw runs on the calling
     * thread), then sends it; a failure reverts the row unless a newer tap came, and redraw runs again. Finishes pr.
     * A grouped_light that is not one of the shown rows is ignored.
     */
    static void tap(Context ctx, String light, int dot, Runnable redraw, BroadcastReceiver.PendingResult pr) {
        final Context app = ctx.getApplicationContext();
        if (light == null || dot < -1 || dot == 0 || dot > HueRows.DOTS || !shownLight(app, light)) {
            if (pr != null) pr.finish();
            return;
        }
        final HueState before;
        final long seq;
        final String body;
        synchronized (LOCK) {
            Map<String, HueState> m = states(app);
            before = m.get(light);
            seq = SEQ.tap(light);
            body = dot < 0 ? HueRows.toggleBody(before) : HueRows.dimBody(before, dot);
            shown = HueRows.with(m, light, dot < 0 ? HueRows.afterToggle(before) : HueRows.afterDim(before, dot));
        }
        redraw.run();
        boolean queued = submit(() -> {
            try {
                send(app, light, body, before, seq);
                redraw.run();
            } finally {
                if (pr != null) pr.finish();
            }
        });
        if (!queued && pr != null) pr.finish();
    }

    private static boolean shownLight(Context ctx, String light) {
        for (HueRows.Row r : current(ctx).rows) if (r.groupedLightId.equals(light)) return true;
        return false;
    }

    /** Executor only: the PUT for one tap. */
    private static void send(Context ctx, String light, String body, HueState before, long seq) {
        PrefsStore p = new PrefsStore(ctx);
        if (!paired(ctx)) return;
        HueBridge b = new HueBridge(p.get(K_HOST, ""), p.get(K_PIN, ""));
        HueBridge.Reply r = b.put(LIGHTS + "/" + light, p.get(K_KEY, ""), body, "set");
        if (r.ok()) {
            reachable = true;
            return;
        }
        synchronized (LOCK) {
            shown = HueRows.revert(states(ctx), light, before, SEQ, seq);
        }
        if (r.code == HueBridge.UNREACHABLE) reachable = false;
    }
}
