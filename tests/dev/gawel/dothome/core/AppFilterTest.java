package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class AppFilterTest {
    private AppFilterTest() {}

    public static void testDrawableNamesUniqueAndValid() {
        List<String> names = AppFilter.drawableNames(Arrays.asList("com.a/b.C", "com.a/b_C", "com.a/b.C"));
        T.eq(3, new HashSet<String>(names).size(), "distinct");
        for (String n : names) T.check(n.matches("[a-z][a-z0-9_]*"), "valid " + n);
        T.eq("ic_com_a_b_c", names.get(0), "first plain");
    }

    public static void testLongNamesTruncated() {
        StringBuilder sb = new StringBuilder("com.");
        for (int i = 0; i < 200; i++) sb.append('x');
        List<String> names = AppFilter.drawableNames(Arrays.asList(sb + "/A", sb + "/B"));
        T.check(names.get(0).length() <= 90, "len");
        T.check(!names.get(0).equals(names.get(1)), "distinct after truncation");
        T.check(names.get(1).length() <= 94, "suffix len");
    }

    public static void testDrawableNameBase() {
        T.eq("ic_com_x_y__main", AppFilter.drawableName("com.X.Y/.Main"), "base");
    }

    public static void testAppFilterXmlItem() {
        String xml = AppFilter.xml(Arrays.asList("com.a/com.a.Main"));
        T.check(xml.contains("<resources>"), "root");
        T.check(xml.contains("<item component=\"ComponentInfo{com.a/com.a.Main}\" drawable=\"ic_com_a_com_a_main\"/>"), xml);
        T.check(xml.contains("</resources>"), "close");
    }

    public static void testXmlMatchesDrawableNamesWithCollisions() {
        List<String> comps = Arrays.asList("com.a/b.C", "com.a/b_C", "com.a/b.C", "com.z/Q");
        List<String> names = AppFilter.drawableNames(comps);
        String xml = AppFilter.xml(comps);
        for (int i = 0; i < comps.size(); i++) {
            T.check(xml.contains("ComponentInfo{" + comps.get(i) + "}\" drawable=\"" + names.get(i) + "\""),
                "item " + i + " uses " + names.get(i));
        }
    }
}
