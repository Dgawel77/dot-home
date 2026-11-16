package dev.gawel.dothome.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class AllTests {
    /** Add each new test class here. */
    static final Class<?>[] SUITES = {
        DotMatrixFontTest.class,
        RngTest.class,
    };

    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (Class<?> suite : SUITES) {
            for (Method m : suite.getDeclaredMethods()) {
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
