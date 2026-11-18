package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Calm zones: rectangles {x, y, w, h} in pixels behind widgets, where the game is dimmed. */
public final class Zones {
    private static final float[][] MAIN = {
        {0.032f, 0.033f, 0.935f, 0.233f}, {0.032f, 0.693f, 0.935f, 0.133f}, {0.058f, 0.848f, 0.884f, 0.122f}};
    private static final float[][] COVER = {
        {0.036f, 0.025f, 0.927f, 0.242f}, {0.036f, 0.716f, 0.927f, 0.148f}, {0.048f, 0.879f, 0.903f, 0.104f}};

    private Zones() {}

    public static boolean isCover(float w, float h) { return w / h < 0.6f; }

    /** Cover-shaped in either orientation (min/max < 0.6); used where a rotation must not look like a fold. */
    public static boolean isCoverShape(float w, float h) {
        return Math.min(w, h) / Math.max(w, h) < 0.6f;
    }

    /** The bottom edge of the default top widget zone, px. */
    public static float topZoneBottom(float w, float h) {
        float[] z = (isCover(w, h) ? COVER : MAIN)[0];
        return (z[1] + z[3]) * h;
    }

    public static List<float[]> defaults(float w, float h) {
        return scale(isCover(w, h) ? COVER : MAIN, w, h);
    }

    /** Parses {"main":[[x,y,w,h],...],"cover":[...]} (fractions 0..1); falls back to defaults on any problem. */
    public static List<float[]> parse(String json, float w, float h) {
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            List<Object> rects = root == null ? null : MiniJson.arr(root.get(isCover(w, h) ? "cover" : "main"));
            if (rects == null || rects.isEmpty()) return defaults(w, h);
            List<float[]> out = new ArrayList<>();
            for (Object r : rects) {
                List<Object> v = MiniJson.arr(r);
                if (v == null || v.size() != 4) return defaults(w, h);
                float[] f = new float[4];
                for (int k = 0; k < 4; k++) {
                    if (!(v.get(k) instanceof Double)) return defaults(w, h);
                    f[k] = ((Double) v.get(k)).floatValue();
                }
                out.add(new float[] {f[0] * w, f[1] * h, f[2] * w, f[3] * h});
            }
            return out;
        } catch (IllegalArgumentException e) {
            return defaults(w, h);
        }
    }

    private static List<float[]> scale(float[][] fr, float w, float h) {
        List<float[]> out = new ArrayList<>();
        for (float[] f : fr) out.add(new float[] {f[0] * w, f[1] * h, f[2] * w, f[3] * h});
        return out;
    }
}
