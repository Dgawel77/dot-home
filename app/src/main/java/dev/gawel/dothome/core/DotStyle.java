package dev.gawel.dothome.core;

/** The design-rule palette: a black ground and four greys, nothing else. */
public final class DotStyle {
    private DotStyle() {}

    public static final int GROUND = 0xFF000000;
    /** Primary text; lit clock dots, ship, shots, active control state. */
    public static final int L100 = 0xFFDDDDDD;
    /** Secondary text; game, lock stills. */
    public static final int L65 = 0xFFA6A6A6;
    /** Tertiary text; map lines, inactive. */
    public static final int L46 = 0xFF757575;
    /** Unlit matrix cells. */
    public static final int L10 = 0xFF1A1A1A;
}
