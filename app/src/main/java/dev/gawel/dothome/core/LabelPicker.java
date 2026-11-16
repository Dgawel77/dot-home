package dev.gawel.dothome.core;

/**
 * Picks which map labels to draw: candidates arrive in priority order (most important first); the user's own place
 * is always kept, the rest are taken in order up to a total cap, skipping any that lands within a minimum distance
 * of one already kept.
 */
public final class LabelPicker {
    private LabelPicker() {}

    /**
     * @param x       candidate x positions, in priority order
     * @param y       candidate y positions
     * @param mine    which candidates are the user's own place (may be null for none); these are always kept
     * @param max     most labels kept in all, own places included
     * @param minDist least distance between any two kept labels (own places are exempt from nothing: they block neighbours)
     * @return keep flag per candidate
     */
    public static boolean[] pick(float[] x, float[] y, boolean[] mine, int max, float minDist) {
        int n = x.length;
        boolean[] keep = new boolean[n];
        int count = 0;
        if (mine != null) {
            for (int i = 0; i < n; i++) {
                if (mine[i]) { keep[i] = true; count++; }
            }
        }
        for (int i = 0; i < n && count < max; i++) {
            if (keep[i]) continue;
            boolean clear = true;
            for (int k = 0; k < n && clear; k++) {
                if (keep[k] && Math.hypot(x[i] - x[k], y[i] - y[k]) < minDist) clear = false;
            }
            if (!clear) continue;
            keep[i] = true;
            count++;
        }
        return keep;
    }
}
