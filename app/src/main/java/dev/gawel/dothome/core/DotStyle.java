package dev.gawel.dothome.core;

/**
 * The design-rule palette: a black ground, five greys and one colour. LASER red is the only colour in the
 * system and is used for lasers alone: the bolts, the muzzle flash and the hit sparks (Ship v2, the user's pick).
 * Everything else is drawn in the greys.
 */
public final class DotStyle {
    private DotStyle() {}

    public static final int GROUND = 0xFF000000;
    /** Primary text; lit clock dots, ship, active control state. */
    public static final int L100 = 0xFFDDDDDD;
    /** Secondary text; game, lock stills. */
    public static final int L65 = 0xFFA6A6A6;
    /** Tertiary text; map lines, inactive. */
    public static final int L46 = 0xFF757575;
    /** The moon's seas. */
    public static final int L30 = 0xFF4D4D4D;
    /** Unlit matrix cells. */
    public static final int L10 = 0xFF1A1A1A;
    /**
     * The one colour (#FF3B30), an accent used sparingly: laser bolt cores, the muzzle flash and hit sparks; outside
     * the game, Dot Hue's off dot. Lock stills dim it to 65%.
     */
    public static final int LASER = 0xFFFF3B30;
    /** LASER at 35% alpha: the soft glow around a bolt (2-3x its thickness) and the muzzle flash. */
    public static final int LASER_GLOW = 0x59FF3B30;
}
