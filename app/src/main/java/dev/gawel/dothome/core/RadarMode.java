package dev.gawel.dothome.core;

/**
 * The radar's two views: local (the area around you, tile zoom 9) and region (about 250 miles, zoom 6). Each radar
 * widget keeps its own choice in the pref {@code radar.view.<appWidgetId>} = "local" | "region", default local.
 */
public final class RadarMode {
    private RadarMode() {}

    public static final String LOCAL = "local", REGION = "region";
    public static final int ZOOM_LOCAL = 9, ZOOM_REGION = 6;

    /** The pref key for one widget's view. */
    public static String key(int appWidgetId) {
        return "radar.view." + appWidgetId;
    }

    /** True for a stored "region"; anything else (unset, unknown) is local. */
    public static boolean isRegion(String stored) {
        return REGION.equals(stored);
    }

    /** The value a toggle stores: region from local (or unset), local from region. */
    public static String toggled(String stored) {
        return isRegion(stored) ? LOCAL : REGION;
    }

    public static String value(boolean region) {
        return region ? REGION : LOCAL;
    }

    /** Radar tile zoom for the view (the full-screen radar uses the same). */
    public static int zoom(boolean region) {
        return region ? ZOOM_REGION : ZOOM_LOCAL;
    }
}
