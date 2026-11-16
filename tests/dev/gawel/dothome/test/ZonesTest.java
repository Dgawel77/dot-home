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

    public static void testZonesParseAndFallback() {
        String json = "{\"main\":[[0.1,0.2,0.5,0.25]],\"cover\":[[0.5,0.5,0.25,0.125],[0,0,1,1]]}";
        List<float[]> m = Zones.parse(json, 600, 800);
        T.eq(1, m.size(), "main count");
        T.near(60, m.get(0)[0], 0.01, "x");
        T.near(160, m.get(0)[1], 0.01, "y");
        T.near(300, m.get(0)[2], 0.01, "w");
        T.near(200, m.get(0)[3], 0.01, "h");
        List<float[]> c = Zones.parse(json, 300, 800);
        T.eq(2, c.size(), "cover count");
        T.near(150, c.get(0)[0], 0.01, "cover x");
        T.near(100, c.get(0)[3], 0.01, "cover h");
        T.eq(3, Zones.parse("garbage", 600, 800).size(), "garbage");
        T.eq(3, Zones.parse(null, 600, 800).size(), "null");
        T.eq(3, Zones.parse("{\"main\":[]}", 600, 800).size(), "empty list");
        T.eq(3, Zones.parse("{\"main\":[[1,2,3]]}", 600, 800).size(), "ill-shaped");
        T.eq(3, Zones.parse("{\"cover\":[[0,0,1,1]]}", 600, 800).size(), "missing list for aspect");
    }
}
