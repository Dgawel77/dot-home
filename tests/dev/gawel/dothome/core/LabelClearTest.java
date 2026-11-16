package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.ArrayList;
import java.util.List;

public final class LabelClearTest {
    private LabelClearTest() {}

    public static void testPaddedRectExcludesInsideKeepsOutside() {
        List<float[]> r = new ArrayList<float[]>();
        r.add(new float[] {100, 100, 200, 112}); // text bounds
        LabelClear c = LabelClear.of(r, 10f);   // padded: 90..210 x 90..122
        T.check(c.hits(150, 106, 1f), "inside text");
        T.check(c.hits(95, 106, 1f), "inside pad");
        T.check(!c.hits(80, 106, 1f), "just outside pad");
        T.check(!c.hits(150, 130, 1f), "below pad");
        T.check(c.hits(86, 106, 5f), "disc reaching the pad");
        T.check(!c.hits(215, 126, 3f), "corner disc not touching");
    }
}
