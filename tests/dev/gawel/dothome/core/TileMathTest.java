package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

public final class TileMathTest {
    private TileMathTest() {}

    public static void testTileMathChicagoZ9() {
        T.eq(131, TileMath.tileX(-87.63, 9), "x");
        T.eq(190, TileMath.tileY(41.88, 9), "y");
    }

    public static void testTileMathInverse() {
        double tx = TileMath.tileXf(-87.63, 9), ty = TileMath.tileYf(41.88, 9);
        T.near(-87.63, TileMath.lonOf(tx, 9), 1e-9, "lon");
        T.near(41.88, TileMath.latOf(ty, 9), 1e-9, "lat");
    }
}
