package dev.gawel.dothome.core;

/** The one dot scale, taken from the wallpaper: radius and pitch in px for a given display density. */
public final class DotScale {
    private DotScale() {}

    public static float radiusPx(float density) { return 1.35f * density; }

    public static float pitchPx(float density) { return 4.5f * density; }

    /** How many dot rows a glyph of this pixel height spans at the shared pitch. */
    public static float glyphPitchRows(float glyphHeightPx, float density) {
        return glyphHeightPx / pitchPx(density);
    }
}
