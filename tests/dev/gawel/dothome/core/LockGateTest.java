package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class LockGateTest {
    public static void testFirstPushAllowed() {
        T.check(new LockGate().shouldPush(1000L, 5), "first push");
    }

    public static void testThrottledUnderAMinute() {
        LockGate g = new LockGate();
        g.pushed(0L, 1);
        T.check(!g.shouldPush(59_000L, 2), "59 s");
        T.check(g.shouldPush(60_000L, 2), "60 s");
    }

    public static void testUnchangedFrameSkipped() {
        LockGate g = new LockGate();
        g.pushed(0L, 7);
        T.check(!g.shouldPush(600_000L, 7), "same frame 10 min later");
    }

    public static void testFingerprintChangesWithScoreAndCell() {
        int base = LockGate.fingerprint(10, 500f, 500f, 1000f, 1000f);
        T.check(base != LockGate.fingerprint(11, 500f, 500f, 1000f, 1000f), "score");
        T.check(base != LockGate.fingerprint(10, 625f, 500f, 1000f, 1000f), "one cell right");
        T.eq(base, LockGate.fingerprint(10, 501f, 500f, 1000f, 1000f), "1 px");
    }
}
