package dev.gawel.dothome.test;

import dev.gawel.dothome.core.Zones;
import java.util.List;

public final class ZonesTest {
    public static void testZoneDefaultsByAspect() {
        T.check(!Zones.isCover(620, 722), "main");
        T.check(Zones.isCover(330, 810), "cover");
        List<float[]> main = Zones.defaults(620, 722);
        T.eq(3, main.size(), "main count");
        T.near(0.032 * 620, main.get(0)[0], 0.05, "main x");
        T.near(0.033 * 722, main.get(0)[1], 0.05, "main y");
        List<float[]> cover = Zones.defaults(330, 810);
        T.eq(3, cover.size(), "cover count");
        float[] z = cover.get(0);
        T.near(11.88, z[0], 0.05, "cover x");
        T.near(20.25, z[1], 0.05, "cover y");
        T.near(305.91, z[2], 0.05, "cover w");
        T.near(196.02, z[3], 0.05, "cover h");
    }

    public static void testParseFallsBackToDefaults() {
        T.eq(3, Zones.parse("garbage", 620, 722).size(), "fallback");
    }
}
