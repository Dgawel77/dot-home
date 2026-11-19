package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Dot Hue widget's model: up to MAX picked rooms/zones, shown one at a time (flip with ‹ ›), each with a row of
 * STEPS brightness dots. A tap on the name toggles the room; a tap on dot n turns it on at n x 5%; ALL OFF switches
 * every light in the home off. Taps show at once (optimistic) and a refresh that
 * began before a tap never overwrites it (see {@link Seq}); a failed tap reverts its row unless a newer tap came.
 */
public final class HueRows {
    private HueRows() {}

    public static final int MAX = 8;
    /** Brightness steps (dots): 5% each. */
    public static final int STEPS = 20;
    private static final int STEP_PCT = 100 / STEPS;
    /** The PUT body for off: ALL OFF (sent to the bridge_home grouped_light) and the red off dot. */
    public static final String ALL_OFF_BODY = "{\"on\":{\"on\":false}}";
    public static final String UNPAIRED = "PAIR HUE IN DOT HOME";
    public static final String NO_ROOMS = "PICK ROOMS IN DOT HOME";
    public static final String NO_BRIDGE = "NO BRIDGE";

    /** A row: the picked room or zone, its grouped_light and its state (OFF when not known yet). */
    public static final class Row {
        public final String groupId, groupedLightId, name;
        public final HueState state;

        Row(String groupId, String groupedLightId, String name, HueState state) {
            this.groupId = groupId;
            this.groupedLightId = groupedLightId;
            this.name = name;
            this.state = state;
        }
    }

    /** Lit dots: 0 when off or unknown, all when on without dimming, else brightness / 5 rounded, at least 1. */
    public static int litDots(HueState s) {
        if (s == null || !s.on) return 0;
        if (!s.dimmable) return STEPS;
        return (int) Math.max(1, Math.min(STEPS, Math.round(s.brightness / STEP_PCT)));
    }

    /** The room index after stepping delta (wrapping) through n rooms; 0 when there are none. */
    public static int flip(int idx, int delta, int n) {
        if (n <= 0) return 0;
        return ((index(idx, n) + delta) % n + n) % n;
    }

    /** idx brought into 0..n-1 (wrapping; negative is 0); 0 when there are no rooms. */
    public static int index(int idx, int n) {
        if (n <= 0 || idx < 0) return 0;
        return idx % n;
    }

    /** Every state switched off (brightness kept, so the next on comes back at the same level). A new map. */
    public static Map<String, HueState> allOff(Map<String, HueState> m) {
        Map<String, HueState> out = new HashMap<>();
        for (Map.Entry<String, HueState> e : m.entrySet()) {
            HueState s = e.getValue();
            out.put(e.getKey(), s == null ? HueState.OFF : new HueState(false, s.dimmable, s.brightness));
        }
        return out;
    }

    /** A new selection: groupId removed if picked, else appended while fewer than MAX are picked. */
    public static List<String> toggleSelection(List<String> sel, String groupId) {
        List<String> out = new ArrayList<>(sel);
        if (!out.remove(groupId) && out.size() < MAX) out.add(groupId);
        return out;
    }

    public static String encode(List<String> sel) {
        StringBuilder b = new StringBuilder();
        for (String s : sel) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }

    /** The stored selection: blanks dropped, at most MAX. */
    public static List<String> decode(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && out.size() < MAX && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** The rows for the picks, in pick order; a pick whose room is gone has no row. */
    public static List<Row> rows(List<String> sel, List<HueGroup> groups, Map<String, HueState> states) {
        List<Row> out = new ArrayList<>();
        for (String id : sel) {
            HueGroup g = find(groups, id);
            if (g == null) continue;
            HueState s = states.get(g.groupedLightId);
            out.add(new Row(g.id, g.groupedLightId, g.name.toUpperCase(Locale.ROOT), s == null ? HueState.OFF : s));
        }
        return out;
    }

    /** The picks whose rooms still exist. */
    public static List<String> prune(List<String> sel, List<HueGroup> groups) {
        List<String> out = new ArrayList<>();
        for (String id : sel) if (find(groups, id) != null) out.add(id);
        return out;
    }

    private static HueGroup find(List<HueGroup> groups, String id) {
        for (HueGroup g : groups) if (g.id.equals(id)) return g;
        return null;
    }

    /** The PUT body for a name tap: the opposite of the current state (on when unknown). */
    public static String toggleBody(HueState current) {
        return "{\"on\":{\"on\":" + !(current != null && current.on) + "}}";
    }

    /**
     * The PUT body for step 1..STEPS: on at step x 5%, or only on when the room has no dimmable lights. Step 0 is
     * the red off dot: off.
     */
    public static String dimBody(HueState current, int dot) {
        if (dot == 0) return ALL_OFF_BODY;
        if (current != null && !current.dimmable) return "{\"on\":{\"on\":true}}";
        return "{\"on\":{\"on\":true},\"dimming\":{\"brightness\":" + level(dot) + "}}";
    }

    public static HueState afterToggle(HueState s) {
        if (s == null) return new HueState(true, true, 100);
        return new HueState(!s.on, s.dimmable, s.brightness);
    }

    /** The state after step dot (0 = off, keeping the brightness for the next on). */
    public static HueState afterDim(HueState s, int dot) {
        if (dot == 0) return s == null ? HueState.OFF : new HueState(false, s.dimmable, s.brightness);
        if (s != null && !s.dimmable) return new HueState(true, false, 100);
        return new HueState(true, true, level(dot));
    }

    private static int level(int step) {
        return Math.max(1, Math.min(STEPS, step)) * STEP_PCT;
    }

    /** True for the bridge's rate-limit (429) and busy (503) answers, which are worth one retry a second later. */
    public static boolean retryable(int httpCode) {
        return httpCode == 429 || httpCode == 503;
    }

    /** A copy of m with key set to s. */
    public static Map<String, HueState> with(Map<String, HueState> m, String key, HueState s) {
        Map<String, HueState> out = new HashMap<>(m);
        out.put(key, s);
        return out;
    }

    /** A refresh's states, except rows tapped since it began (mark), which keep what they show. */
    public static Map<String, HueState> apply(Map<String, HueState> shown, Map<String, HueState> fetched, Seq seq,
                                              long mark) {
        Map<String, HueState> out = new HashMap<>(fetched);
        for (Map.Entry<String, HueState> e : shown.entrySet()) {
            if (!seq.fresh(e.getKey(), mark)) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** After a failed tap (tapSeq): the row's state before it, unless a newer tap on the row has come since. */
    public static Map<String, HueState> revert(Map<String, HueState> shown, String key, HueState before, Seq seq,
                                               long tapSeq) {
        if (seq.last(key) != tapSeq) return shown;
        return before == null ? without(shown, key) : with(shown, key, before);
    }

    private static Map<String, HueState> without(Map<String, HueState> m, String key) {
        Map<String, HueState> out = new HashMap<>(m);
        out.remove(key);
        return out;
    }

    /**
     * Tap ordering, by grouped_light id: every tap takes the next number. A refresh takes mark() when it starts and
     * applies only to rows not tapped since. Thread-safe.
     */
    public static final class Seq {
        private long n;
        private final Map<String, Long> last = new HashMap<>();

        public synchronized long tap(String key) {
            last.put(key, ++n);
            return n;
        }

        public synchronized long mark() {
            return n;
        }

        public synchronized long last(String key) {
            Long v = last.get(key);
            return v == null ? 0 : v;
        }

        /** True when key has not been tapped since mark. */
        public synchronized boolean fresh(String key, long mark) {
            return last(key) <= mark;
        }
    }
}
