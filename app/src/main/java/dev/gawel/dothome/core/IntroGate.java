package dev.gawel.dothome.core;

/**
 * Decides when the wallpaper's opening dot plays: on the first show after the screen comes back on and the
 * keyguard is gone, and never on a fold, a page swipe or a visibility flip without a screen-off. A show while
 * the keyguard is still up holds the intro at its first dot until the unlock. Pure state; the hub feeds it the
 * screen-off, user-present and visibility events and applies the returned action to the world.
 */
public final class IntroGate {
    public enum Action {
        /** Start the intro from its first frame now. */
        PLAY,
        /** Show the intro's first frame (one dot) and wait for the unlock. */
        HOLD,
        /** No intro: end any intro in progress. */
        SKIP,
        /** Change nothing. */
        NONE
    }

    /** The next unlocked show plays the intro: true for the process's first show and after every screen-off. */
    private boolean armed = true;

    /** The screen went off: the first show after it comes back and unlocks plays the intro. */
    public void screenOff() { armed = true; }

    /** An engine became visible; locked is whether the keyguard is still up. */
    public Action visible(boolean locked) {
        if (!armed) return Action.SKIP;
        if (locked) return Action.HOLD;
        armed = false;
        return Action.PLAY;
    }

    /** The keyguard was dismissed; showing is whether any engine is visible right now. */
    public Action userPresent(boolean showing) {
        if (!armed || !showing) return Action.NONE;
        armed = false;
        return Action.PLAY;
    }

    public boolean armed() { return armed; }
}
