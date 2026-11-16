package dev.gawel.dothome.test;

import dev.gawel.dothome.core.TextFit;

public final class TextFitTest {
    private static final TextFit.Measure TEN_PER_CHAR = s -> s.length() * 10f;

    public static void testFitShortUnchanged() {
        T.eq("Hello", TextFit.fit("Hello", 100, TEN_PER_CHAR), "fits as is");
        T.eq("0123456789", TextFit.fit("0123456789", 100, TEN_PER_CHAR), "exact fit");
    }

    public static void testFitEllipsizes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append((char) ('a' + i % 26));
        String r = TextFit.fit(sb.toString(), 100, TEN_PER_CHAR);
        T.check(r.length() <= 10, "length " + r.length());
        T.check(r.endsWith("…"), "ends with ellipsis");
        T.eq(sb.substring(0, 9) + "…", r, "longest prefix plus ellipsis");
    }

    public static void testFitNullEmpty() {
        T.eq("", TextFit.fit(null, 100, TEN_PER_CHAR), "null");
        T.eq("", TextFit.fit("", 100, TEN_PER_CHAR), "empty");
    }

    public static void testFitNothingFits() {
        T.eq("…", TextFit.fit("abcdef", 5, TEN_PER_CHAR), "even the ellipsis does not fit");
    }
}
