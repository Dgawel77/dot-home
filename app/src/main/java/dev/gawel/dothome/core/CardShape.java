package dev.gawel.dothome.core;

/**
 * Corner shape of a widget card. One UI clips every widget to a rounded rect and passes that radius (dp, a float)
 * as "semShapeRadius" in the widget options: 24 for a 2-row clock, 39.6 for the 1-row music widget. Cards draw
 * with the same radius, so their corners and rim sit exactly inside the launcher's clip.
 */
public final class CardShape {
    private CardShape() {}

    /** The options-bundle key One UI uses for the clip radius. */
    public static final String OPTION_KEY = "semShapeRadius";
    /** Radius when the launcher passes none (stock Android launchers). */
    public static final float DEFAULT_RADIUS_DP = 24f;

    /**
     * The radius to draw with, in dp: raw when it is a usable value (0 means square corners), the 24 dp default
     * when it is absent (NaN) or nonsense, and never more than half the shorter side.
     */
    public static float radiusDp(float raw, float wDp, float hDp) {
        float r = Float.isNaN(raw) || Float.isInfinite(raw) || raw < 0f ? DEFAULT_RADIUS_DP : raw;
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
