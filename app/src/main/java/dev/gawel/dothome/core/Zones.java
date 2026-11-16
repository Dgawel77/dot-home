package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/** Calm zones: rectangles {x, y, w, h} in pixels behind widgets, where the game is dimmed. */
public final class Zones {
    private static final float[][] MAIN = {
        {0.032f, 0.033f, 0.935f, 0.233f}, {0.032f, 0.693f, 0.935f, 0.133f}, {0.058f, 0.848f, 0.884f, 0.122f}};
    private static final float[][] COVER = {
        {0.036f, 0.025f, 0.927f, 0.242f}, {0.036f, 0.716f, 0.927f, 0.148f}, {0.048f, 0.879f, 0.903f, 0.104f}};

    private Zones() {}

    public static boolean isCover(float w, float h) { return w / h < 0.6f; }

    public static List<float[]> defaults(float w, float h) {
        return scale(isCover(w, h) ? COVER : MAIN, w, h);
    }

    /** Task 7 replaces this with a real MiniJson parse; until then it always yields defaults. */
    public static List<float[]> parse(String json, float w, float h) {
        return defaults(w, h);
    }

    private static List<float[]> scale(float[][] fr, float w, float h) {
        List<float[]> out = new ArrayList<>();
        for (float[] f : fr) out.add(new float[] {f[0] * w, f[1] * h, f[2] * w, f[3] * h});
        return out;
    }
}
