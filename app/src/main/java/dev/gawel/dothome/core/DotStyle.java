package dev.gawel.dothome.core;

/** The one palette: no pure white, no colour. */
public final class DotStyle {
    private DotStyle() {}

    /** Wallpaper and radar background. */
    public static final int BG = 0xFF0B0B0B;
    /** Primary dots and text. */
    public static final int FG = 0xFFDDDDDD;
    /** Secondary text. */
    public static final int FG2 = 0xFF8E8E8E;
    /** Unlit grid dots. */
    public static final int FAINT = 0xFF222222;
    /** Widget backdrop. */
    public static final int PANEL = 0xCC0B0B0B;
}
