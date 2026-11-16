package dev.gawel.dothome.core;

/** Shortens a string with an ellipsis so it fits a width. Pure Java; the caller supplies the measuring. */
public final class TextFit {
    private static final String ELLIPSIS = "…";

    public interface Measure { float width(String s); }

    private TextFit() {}

    /** s if it fits; else the longest prefix plus "…" that fits (just "…" if nothing more does). */
    public static String fit(String s, float maxWidth, Measure m) {
        if (s == null || s.isEmpty()) return "";
        if (m.width(s) <= maxWidth) return s;
        int lo = 0, hi = s.length() - 1; // largest n whose prefix + ellipsis fits
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(s.substring(0, mid) + ELLIPSIS) <= maxWidth) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ELLIPSIS;
    }
}
