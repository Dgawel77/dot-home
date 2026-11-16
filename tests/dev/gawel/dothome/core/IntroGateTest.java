package dev.gawel.dothome.core;

import dev.gawel.dothome.core.IntroGate.Action;
import dev.gawel.dothome.test.T;

public final class IntroGateTest {
    /** A gate whose first show has already played. */
    private static IntroGate played() {
        IntroGate g = new IntroGate();
        T.eq(Action.PLAY, g.visible(false), "first show of the process");
        return g;
    }

    public static void testFirstShowPlaysOnce() {
        IntroGate g = played();
        T.eq(Action.SKIP, g.visible(false), "second show");
        T.check(!g.armed(), "disarmed");
    }

    public static void testWakeAndUnlockFingerprintVisibleBehindKeyguard() {
        IntroGate g = played();
        g.screenOff();
        T.eq(Action.HOLD, g.visible(true), "shows while the keyguard is up: hold at the first dot");
        T.eq(Action.PLAY, g.userPresent(true), "fingerprint accepted: play");
        T.eq(Action.NONE, g.userPresent(true), "a second user-present changes nothing");
        T.eq(Action.SKIP, g.visible(false), "a later show without a screen-off");
    }

    public static void testWakeAndUnlockUnlockedBeforeVisible() {
        IntroGate g = played();
        g.screenOff();
        T.eq(Action.NONE, g.userPresent(false), "unlocked before any engine showed");
        T.eq(Action.PLAY, g.visible(false), "the first show after the unlock plays");
        T.eq(Action.NONE, g.userPresent(true), "late user-present");
    }

    public static void testWakeAndUnlockVisibleAlreadyUnlocked() {
        IntroGate g = played();
        g.screenOff();
        T.eq(Action.PLAY, g.visible(false), "keyguard already gone when visible");
        T.eq(Action.NONE, g.userPresent(true), "user-present arrives after: no replay");
    }

    public static void testPageSwipeNeverReplays() {
        IntroGate g = played();
        // some launchers flip visibility while paging; without a screen-off it must not replay
        for (int i = 0; i < 5; i++) T.eq(Action.SKIP, g.visible(false), "swipe " + i);
        T.eq(Action.NONE, g.userPresent(true), "no unlock pending");
    }

    public static void testFoldSkips() {
        IntroGate g = played();
        // fold: the main engine hides and the cover engine shows, with no screen-off between
        T.eq(Action.SKIP, g.visible(false), "cover engine shows");
        T.eq(Action.SKIP, g.visible(true), "even onto a locked cover without a screen-off");
    }

    public static void testVisibilityFlipWithoutScreenOff() {
        IntroGate g = played();
        T.eq(Action.SKIP, g.visible(false), "back from an app");
        T.eq(Action.SKIP, g.visible(false), "again");
        T.check(!g.armed(), "still disarmed");
    }

    public static void testScreenOffAgainWhileHolding() {
        IntroGate g = played();
        g.screenOff();
        T.eq(Action.HOLD, g.visible(true), "lock screen");
        g.screenOff();
        T.eq(Action.HOLD, g.visible(true), "lock screen again");
        T.eq(Action.PLAY, g.userPresent(true), "unlocked");
    }

    public static void testUnlockIntoAnAppThenHome() {
        IntroGate g = played();
        g.screenOff();
        T.eq(Action.HOLD, g.visible(true), "lock screen");
        T.eq(Action.NONE, g.userPresent(false), "unlocked straight into an app; the wallpaper is hidden");
        T.eq(Action.PLAY, g.visible(false), "first show after the unlock: home");
    }
}
