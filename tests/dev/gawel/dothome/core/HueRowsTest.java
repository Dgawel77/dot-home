package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HueRowsTest {
    private HueRowsTest() {}

    private static HueState on(double b) { return new HueState(true, true, b); }

    private static List<HueGroup> groups() {
        return Arrays.asList(new HueGroup("r1", "Living room", "g1", false), new HueGroup("r2", "Bedroom", "g2", false),
            new HueGroup("z1", "Upstairs", "g3", true));
    }

    public static void testLitDots() {
        T.eq(20, HueRows.STEPS, "20 brightness steps of 5%");
        T.eq(0, HueRows.litDots(null), "unknown");
        T.eq(0, HueRows.litDots(new HueState(false, true, 80)), "off");
        T.eq(1, HueRows.litDots(on(1)), "1% still shows one dot");
        T.eq(2, HueRows.litDots(on(10)), "10%");
        T.eq(6, HueRows.litDots(on(30)), "30%");
        T.eq(11, HueRows.litDots(on(56.3)), "56%");
        T.eq(20, HueRows.litDots(on(100)), "100%");
        T.eq(20, HueRows.litDots(new HueState(true, false, 100)), "on, not dimmable");
    }

    public static void testToggleSelection() {
        List<String> s = HueRows.toggleSelection(new ArrayList<String>(), "a");
        s = HueRows.toggleSelection(s, "b");
        T.eq(Arrays.asList("a", "b"), s, "added in order");
        T.eq(Arrays.asList("b"), HueRows.toggleSelection(s, "a"), "removed");
        List<String> full = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
        T.eq(full, HueRows.toggleSelection(full, "i"), "a 9th pick is ignored");
        T.eq(8, HueRows.MAX, "max picks");
    }

    public static void testEncodeDecode() {
        T.eq("r1,z1", HueRows.encode(Arrays.asList("r1", "z1")), "encode");
        T.eq(Arrays.asList("r1", "z1"), HueRows.decode("r1,,z1, "), "decode drops blanks");
        T.eq(0, HueRows.decode(null).size(), "null");
        T.eq(8, HueRows.decode("a,b,c,d,e,f,g,h,i").size(), "capped at MAX");
    }

    public static void testRowsOrder() {
        Map<String, HueState> st = new HashMap<>();
        st.put("g1", on(56.3));
        List<HueRows.Row> rows = HueRows.rows(Arrays.asList("z1", "r1"), groups(), st);
        T.eq(2, rows.size(), "two rows");
        T.eq("UPSTAIRS", rows.get(0).name, "selection order, upper case");
        T.eq("g3", rows.get(0).groupedLightId, "grouped light");
        T.eq(HueState.OFF, rows.get(0).state, "no state yet reads as off");
        T.eq("LIVING ROOM", rows.get(1).name, "second");
        T.eq(on(56.3), rows.get(1).state, "state");
    }

    public static void testMissingGroupDropped() {
        List<String> sel = Arrays.asList("r1", "gone", "r2");
        T.eq(2, HueRows.rows(sel, groups(), new HashMap<String, HueState>()).size(), "a deleted room has no row");
        T.eq(Arrays.asList("r1", "r2"), HueRows.prune(sel, groups()), "and leaves the selection");
    }

    public static void testBodies() {
        T.eq("{\"on\":{\"on\":false}}", HueRows.toggleBody(on(50)), "toggle off");
        T.eq("{\"on\":{\"on\":true}}", HueRows.toggleBody(HueState.OFF), "toggle on");
        T.eq("{\"on\":{\"on\":true}}", HueRows.toggleBody(null), "unknown toggles on");
        T.eq("{\"on\":{\"on\":true},\"dimming\":{\"brightness\":60}}", HueRows.dimBody(on(10), 12), "step 12 = 60%");
        T.eq("{\"on\":{\"on\":true},\"dimming\":{\"brightness\":5}}", HueRows.dimBody(HueState.OFF, 1), "step 1 = 5%, from off");
        T.eq("{\"on\":{\"on\":true},\"dimming\":{\"brightness\":100}}", HueRows.dimBody(null, 20), "step 20");
        T.eq("{\"on\":{\"on\":false}}", HueRows.ALL_OFF_BODY, "all off");
    }

    public static void testDimTapWithoutDimming() {
        T.eq("{\"on\":{\"on\":true}}", HueRows.dimBody(new HueState(false, false, 100), 2), "only on");
        T.eq(new HueState(true, false, 100), HueRows.afterDim(new HueState(false, false, 100), 2), "on at full");
    }

    public static void testAfterToggleAndDim() {
        T.eq(new HueState(false, true, 56.3), HueRows.afterToggle(on(56.3)), "off keeps brightness");
        T.eq(on(56.3), HueRows.afterToggle(new HueState(false, true, 56.3)), "back on");
        T.eq(on(100), HueRows.afterToggle(null), "unknown toggles on");
        T.eq(on(80), HueRows.afterDim(HueState.OFF, 16), "step 16");
    }

    public static void testStaleRefreshIgnored() {
        HueRows.Seq seq = new HueRows.Seq();
        Map<String, HueState> shown = new HashMap<>();
        shown.put("g1", on(20));
        shown.put("g2", on(40));
        long mark = seq.mark(); // a refresh starts
        seq.tap("g1"); // a tap lands while it runs
        shown = HueRows.with(shown, "g1", on(100));
        Map<String, HueState> fetched = new HashMap<>();
        fetched.put("g1", on(20)); // read before the tap took effect
        fetched.put("g2", HueState.OFF);
        Map<String, HueState> merged = HueRows.apply(shown, fetched, seq, mark);
        T.eq(on(100), merged.get("g1"), "the tapped row keeps the tap");
        T.eq(HueState.OFF, merged.get("g2"), "the others take the refresh");
        Map<String, HueState> later = HueRows.apply(merged, fetched, seq, seq.mark());
        T.eq(on(20), later.get("g1"), "a refresh started after the tap applies");
    }

    public static void testRevert() {
        HueRows.Seq seq = new HueRows.Seq();
        Map<String, HueState> shown = new HashMap<>();
        shown.put("g1", on(40));
        HueState before = shown.get("g1");
        long t1 = seq.tap("g1");
        shown = HueRows.with(shown, "g1", HueRows.afterToggle(before));
        T.eq(before, HueRows.revert(shown, "g1", before, seq, t1).get("g1"), "a failed tap restores the row");
        long t2 = seq.tap("g1"); // a newer tap on the same row
        shown = HueRows.with(shown, "g1", on(100));
        T.check(t2 > t1, "seq grows");
        T.eq(on(100), HueRows.revert(shown, "g1", before, seq, t1).get("g1"), "an older failure never undoes a newer tap");
    }

    public static void testMessages() {
        T.eq("PAIR HUE IN DOT HOME", HueRows.UNPAIRED, "unpaired");
        T.eq("PICK ROOMS IN DOT HOME", HueRows.NO_ROOMS, "no rooms");
        T.eq("NO BRIDGE", HueRows.NO_BRIDGE, "unreachable");
    }
    /** The bridge's rate limit (429) and busy (503) answers are worth one retry; anything else is final. */
    public static void testRetryable() {
        T.check(HueRows.retryable(429), "too many requests");
        T.check(HueRows.retryable(503), "busy");
        T.check(!HueRows.retryable(200), "ok");
        T.check(!HueRows.retryable(404), "gone");
        T.check(!HueRows.retryable(-1), "unreachable is not retried");
    }
    public static void testFlip() {
        T.eq(1, HueRows.flip(0, 1, 3), "next");
        T.eq(0, HueRows.flip(2, 1, 3), "wraps to the first");
        T.eq(2, HueRows.flip(0, -1, 3), "wraps to the last");
        T.eq(2, HueRows.flip(5, 0, 3), "a stale index wraps back in range");
        T.eq(0, HueRows.flip(0, 1, 0), "no rooms");
        T.eq(1, HueRows.index(7, 3), "index wraps (7 mod 3)");
        T.eq(0, HueRows.index(-1, 3), "negative is the first");
    }

    public static void testAllOff() {
        Map<String, HueState> st = new HashMap<>();
        st.put("g1", on(60));
        st.put("g2", new HueState(true, false, 100));
        st.put("g3", HueState.OFF);
        Map<String, HueState> off = HueRows.allOff(st);
        T.eq(new HueState(false, true, 60), off.get("g1"), "off, brightness kept for the next on");
        T.eq(new HueState(false, false, 100), off.get("g2"), "non-dimmable off");
        T.eq(HueState.OFF, off.get("g3"), "already off");
        T.eq(on(60), st.get("g1"), "the input is not changed");
    }
    /** Step 0 is the red off dot: off, brightness kept for the next on. */
    public static void testOffDot() {
        T.eq("{\"on\":{\"on\":false}}", HueRows.dimBody(on(60), 0), "off");
        T.eq("{\"on\":{\"on\":false}}", HueRows.dimBody(new HueState(true, false, 100), 0), "off, not dimmable");
        T.eq(new HueState(false, true, 60), HueRows.afterDim(on(60), 0), "off keeps 60%");
        T.eq(HueState.OFF, HueRows.afterDim(null, 0), "unknown goes off");
    }
}
