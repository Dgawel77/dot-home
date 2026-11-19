package dev.gawel.dothome.core;

/** A Hue room or zone that can be switched: its id, its name and the grouped_light service that controls it. */
public final class HueGroup {
    public final String id, name, groupedLightId;
    public final boolean zone;

    public HueGroup(String id, String name, String groupedLightId, boolean zone) {
        this.id = id;
        this.name = name;
        this.groupedLightId = groupedLightId;
        this.zone = zone;
    }
}
