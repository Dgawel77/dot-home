package dev.gawel.dothome.core;

/**
 * Corner shape of a widget card. One UI clips every widget to a rounded rect and passes that radius (dp, a float)
 * as "semShapeRadius" in the widget options (about 39.6 on the device). Every card draws with
 * max(that, 40 dp), one radius for all, which sits inside the clip so nothing is cut.
 */
public final class CardShape {
    private CardShape() {}

    /** The options-bundle key One UI uses for the clip radius. */
    public static final String OPTION_KEY = "semShapeRadius";
    /** Radius when the launcher passes none (stock Android launchers). */
    public static final float DEFAULT_RADIUS_DP = 40f;

    /**
     * The radius to draw with, in dp: the larger of raw and 40 (40 when raw is absent or nonsense), and never more
     * than half the shorter side.
     */
    public static float radiusDp(float raw, float wDp, float hDp) {
        boolean ok = !Float.isNaN(raw) && !Float.isInfinite(raw) && raw >= 0f;
        float r = ok ? Math.max(raw, DEFAULT_RADIUS_DP) : DEFAULT_RADIUS_DP;
        float half = Math.min(wDp, hDp) / 2f;
        return half > 0f ? Math.min(r, half) : r;
    }

    /**
     * Inset of the rim stroke's centre line from the card edge: half the stroke width, so the whole stroke lies
     * inside the clip. The rim's corner radius shrinks by the same amount, keeping it concentric with the clip.
     */
    public static float rimInset(float strokePx) {
        return strokePx / 2f;
    }
}
