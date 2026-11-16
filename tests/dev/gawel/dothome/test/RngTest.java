package dev.gawel.dothome.test;

import dev.gawel.dothome.core.Rng;

public final class RngTest {
    public static void testRngDeterministic() {
        Rng a = new Rng(7), b = new Rng(7);
        for (int i = 0; i < 100; i++) {
            T.eq(a.next(), b.next(), "next #" + i);
            T.eq(a.range(-3, 5), b.range(-3, 5), "range #" + i);
            T.eq(a.nextInt(10), b.nextInt(10), "nextInt #" + i);
        }
        Rng r = new Rng(1);
        for (int i = 0; i < 100; i++) {
            double v = r.range(2, 4);
            T.check(v >= 2 && v < 4, "range within bounds: " + v);
        }
    }
}
