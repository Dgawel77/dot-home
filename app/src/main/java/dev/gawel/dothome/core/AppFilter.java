package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Drawable naming and appfilter.xml generation for the icon pack. Pure Java. */
public final class AppFilter {
    private static final int MAX = 90;

    private AppFilter() {}

    /** Valid Android resource name for a component ("pkg/cls"), not yet made unique. */
    public static String drawableName(String component) {
        String s = "ic_" + component.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        return s.length() > MAX ? s.substring(0, MAX) : s;
    }

    /** One unique name per component, aligned with the input; collisions get _2, _3, ... */
    public static List<String> drawableNames(List<String> components) {
        List<String> out = new ArrayList<String>();
        Set<String> used = new HashSet<String>();
        for (String c : components) {
            String base = drawableName(c);
            String name = base;
            for (int n = 2; !used.add(name); n++) name = base + "_" + n;
            out.add(name);
        }
        return out;
    }

    public static String xml(List<String> components) {
        List<String> names = drawableNames(components);
        StringBuilder sb = new StringBuilder("<resources>\n");
        for (int i = 0; i < components.size(); i++) {
            sb.append("    <item component=\"ComponentInfo{").append(esc(components.get(i)))
              .append("}\" drawable=\"").append(names.get(i)).append("\"/>\n");
        }
        return sb.append("</resources>\n").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
