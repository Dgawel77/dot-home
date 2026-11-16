package dev.gawel.dothome.core;

import dev.gawel.dothome.test.MapStore;
import dev.gawel.dothome.test.T;

public final class RadarModeTest {
    private RadarModeTest() {}

    public static void testPerWidgetKey() {
        T.eq("radar.view.42", RadarMode.key(42), "key");
    }

    public static void testDefaultIsLocal() {
        T.check(!RadarMode.isRegion(null), "unset is local");
        T.check(!RadarMode.isRegion("area"), "unknown is local");
        T.check(RadarMode.isRegion("region"), "region");
    }

    public static void testToggleFlips() {
        T.eq("region", RadarMode.toggled(null), "unset -> region");
        T.eq("region", RadarMode.toggled("local"), "local -> region");
        T.eq("local", RadarMode.toggled("region"), "region -> local");
        T.eq("region", RadarMode.value(true), "value");
        T.eq("local", RadarMode.value(false), "value");
    }

    public static void testWidgetsKeepTheirOwnView() {
        MapStore s = new MapStore();
        s.put(RadarMode.key(7), RadarMode.toggled(s.get(RadarMode.key(7), RadarMode.LOCAL)));
        T.check(RadarMode.isRegion(s.get(RadarMode.key(7), RadarMode.LOCAL)), "7 toggled to region");
        T.check(!RadarMode.isRegion(s.get(RadarMode.key(8), RadarMode.LOCAL)), "8 untouched");
    }

    public static void testZoomMatchesTheFullScreenRadar() {
        T.eq(9, RadarMode.zoom(false), "local zoom");
        T.eq(6, RadarMode.zoom(true), "region zoom");
    }
}
