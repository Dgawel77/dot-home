package dev.gawel.dothome.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

public final class AllTests {
    /** Add each new test class here. */
    static final Class<?>[] SUITES = {
        MiniJsonTest.class,
        NowPlayingTest.class,
        RngTest.class,
        ScoreBookTest.class,
        TextFitTest.class,
        TiltInputTest.class,
        WeatherTest.class,
        ZonesTest.class,
        dev.gawel.dothome.core.GameWorldTest.class,
        dev.gawel.dothome.core.GeoProjectionTest.class,
        dev.gawel.dothome.core.TileMathTest.class,
        dev.gawel.dothome.core.RadarScaleTest.class,
        dev.gawel.dothome.core.MonoIconTest.class,
        dev.gawel.dothome.core.AppFilterTest.class,
        dev.gawel.dothome.core.ShakeDetectorTest.class,
        DotMatrixFontTest.class,
        dev.gawel.dothome.core.BoxSetTest.class,
        dev.gawel.dothome.core.DesignValuesTest.class,
        dev.gawel.dothome.core.ClockFitTest.class,
        dev.gawel.dothome.core.WidgetResourcesTest.class,
        dev.gawel.dothome.core.RadarModeTest.class,
        dev.gawel.dothome.core.CardShapeTest.class,
        dev.gawel.dothome.core.LatticeRasterTest.class,
        dev.gawel.dothome.core.IntroGateTest.class,
        dev.gawel.dothome.core.LabelPickerTest.class,
        dev.gawel.dothome.core.LabelClearTest.class,
        dev.gawel.dothome.core.ShipFlightTest.class,
        dev.gawel.dothome.core.AimTest.class,
        dev.gawel.dothome.core.ManualModeTest.class,
        dev.gawel.dothome.core.WeatherFxTest.class,
        dev.gawel.dothome.core.BarrageTest.class,
        dev.gawel.dothome.core.StarsTest.class,
        dev.gawel.dothome.core.SkyTest.class,
        dev.gawel.dothome.core.MusicSeekTest.class,
        dev.gawel.dothome.core.ArtMathTest.class,
        dev.gawel.dothome.core.PrecipTest.class,
    };

    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (Class<?> suite : SUITES) {
            Method[] methods = suite.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())
                        || !Modifier.isPublic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                String name = suite.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    System.out.println("PASS " + name);
                    passed++;
                } catch (InvocationTargetException e) {
                    System.out.println("FAIL " + name + ": " + e.getCause());
                    failed++;
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
