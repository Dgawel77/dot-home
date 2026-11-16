package dev.gawel.dothome.test;

/** Minimal assertions; each failure throws AssertionError. */
public final class T {
    private T() {}

    public static void eq(Object expected, Object actual, String msg) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void near(double e, double a, double tol, String msg) {
        if (!(Math.abs(e - a) <= tol)) {
            throw new AssertionError(msg + ": expected <" + e + "> +/- " + tol + " but was <" + a + ">");
        }
    }

    public static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
