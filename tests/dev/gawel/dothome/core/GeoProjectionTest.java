package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class GeoProjectionTest {
    private GeoProjectionTest() {}

    public static void testProjectionRoundTrip() {
        GeoProjection p = GeoProjection.area(41.88, -87.63, 620f, 722f);
        T.near(-87.63, p.lon(p.x(-87.63)), 1e-6, "lon");
        T.near(41.88, p.lat(p.y(41.88)), 1e-6, "lat");
    }

    public static void testAreaViewPutsChicagoRightOfCentre() {
        GeoProjection p = GeoProjection.area(41.88, -87.63, 620f, 722f);
        float x = p.x(-87.63);
        T.check(x > 330 && x < 400, "x=" + x);
    }

    public static void testRegionIsWiderThanArea() {
        GeoProjection a = GeoProjection.area(41.88, -87.63, 620f, 722f);
        GeoProjection r = GeoProjection.region(41.88, -87.63, 620f, 722f);
        T.check(Math.abs(r.x(-88.63) - r.x(-87.63)) < Math.abs(a.x(-88.63) - a.x(-87.63)), "region scale");
    }
}
