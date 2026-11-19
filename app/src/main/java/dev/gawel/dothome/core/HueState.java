package dev.gawel.dothome.core;

/** A grouped_light's state: on/off and brightness 0..100 (100 when the group has no dimmable lights). */
public final class HueState {
    public static final HueState OFF = new HueState(false, true, 100.0);

    public final boolean on;
    /** False when the bridge reports no dimming for the group (no dimmable lights in it). */
    public final boolean dimmable;
    public final double brightness;

    public HueState(boolean on, boolean dimmable, double brightness) {
        this.on = on;
        this.dimmable = dimmable;
        this.brightness = brightness;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HueState)) return false;
        HueState s = (HueState) o;
        return on == s.on && dimmable == s.dimmable && Double.compare(brightness, s.brightness) == 0;
    }

    @Override
    public int hashCode() {
        return (on ? 1 : 0) + (dimmable ? 2 : 0) + 31 * Double.valueOf(brightness).hashCode();
    }

    @Override
    public String toString() {
        return (on ? "on" : "off") + (dimmable ? " " + brightness : " (no dimming)");
    }
}
