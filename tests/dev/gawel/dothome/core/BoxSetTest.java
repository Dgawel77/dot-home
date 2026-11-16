package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class BoxSetTest {
    private BoxSetTest() {}

    public static void testOverlapIsRejected() {
        BoxSet s = new BoxSet();
        T.check(s.tryAdd(0, 0, 10, 10), "first box");
        T.check(!s.tryAdd(5, 5, 15, 15), "overlapping box");
        T.check(!s.tryAdd(2, 2, 3, 3), "contained box");
        T.eq(1, s.size(), "only the first kept");
    }

    public static void testDisjointAndTouchingAreAccepted() {
        BoxSet s = new BoxSet();
        T.check(s.tryAdd(0, 0, 10, 10), "first");
        T.check(s.tryAdd(10, 0, 20, 10), "touching edge");
        T.check(s.tryAdd(0, 20, 10, 30), "below");
        T.eq(3, s.size(), "three kept");
    }
}
