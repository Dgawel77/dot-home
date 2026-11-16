package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The clock widget layout and colours agree with ClockFit and DotStyle (resources are checked as text). */
public final class WidgetResourcesTest {
    private WidgetResourcesTest() {}

    private static final String RES = "app/src/main/res/";

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(RES + path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path + ": " + e);
        }
    }

    /** The attribute blocks of every element with the given tag. */
    private static List<String> elements(String xml, String tag) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + "\\b([^>]*)>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("android:" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String byId(List<String> els, String id) {
        for (String e : els) if (("@+id/" + id).equals(attr(e, "id"))) return e;
        throw new AssertionError("no element @+id/" + id);
    }

    public static void testColoursMatchDotStyle() {
        String xml = read("values/colors.xml");
        int[] levels = {DotStyle.L100, DotStyle.L65, DotStyle.L46, DotStyle.L10};
        String[] names = {"dot_l100", "dot_l65", "dot_l46", "dot_l10"};
        for (int i = 0; i < levels.length; i++) {
            String want = String.format("#%06X", levels[i] & 0xFFFFFF);
            T.check(xml.contains("<color name=\"" + names[i] + "\">" + want + "</color>"), names[i] + " is " + want);
        }
    }

    public static void testTimeClocksAreStackedTwins() {
        List<String> clocks = elements(read("layout/widget_clock.xml"), "TextClock");
        String grid = byId(clocks, "time_grid"), lit = byId(clocks, "time_lit");
        T.eq("@font/dot_matrix_grid", attr(grid, "fontFamily"), "grid font");
        T.eq("@font/dot_matrix", attr(lit, "fontFamily"), "lit font");
        T.eq("@color/dot_l10", attr(grid, "textColor"), "grid colour");
        T.eq("@color/dot_l100", attr(lit, "textColor"), "lit colour");
        for (String c : new String[] {grid, lit}) {
            T.eq("hh:mm:ss", attr(c, "format12Hour"), "12 h");
            T.eq("HH:mm:ss", attr(c, "format24Hour"), "24 h");
            T.eq("false", attr(c, "includeFontPadding"), "no font padding");
            T.eq("start", attr(c, "gravity"), "gravity");
            T.eq("1", attr(c, "maxLines"), "one line");
            T.eq("true", attr(c, "scrollHorizontally"), "never wraps (the trailing gap may overhang)");
        }
        T.check(clocks.indexOf(grid) < clocks.indexOf(lit), "grid layer below the lit layer");
    }

    public static void testBottomRowAndPaddingMatchClockFit() {
        String xml = read("layout/widget_clock.xml");
        T.check(xml.contains("android:padding=\"" + ClockFit.PAD_DP + "dp\""), "pad");
        T.check(xml.contains("android:layout_height=\"" + ClockFit.ROW_DP + "dp\""), "row height");
        T.check(xml.contains("android:layout_marginTop=\"" + ClockFit.GAP_DP + "dp\""), "gap");
        String date = byId(elements(xml, "TextClock"), "date");
        T.eq("EEEE, MMMM d", attr(date, "format12Hour"), "date 12 h");
        T.eq("EEEE, MMMM d", attr(date, "format24Hour"), "date 24 h");
        T.eq("@font/inter", attr(date, "fontFamily"), "Inter");
        T.eq("@color/dot_l65", attr(date, "textColor"), "date colour");
        T.eq("15dp", attr(date, "textSize"), "date size");
    }

    public static void testMusicBlankWhenNothingPlays() {
        T.check(read("xml/widget_music_info.xml").contains("android:initialLayout=\"@layout/widget_music_empty\""),
            "a new music widget starts blank");
        String empty = read("layout/widget_music_empty.xml");
        T.eq(1, elements(empty, "FrameLayout").size(), "one view");
        T.check(!empty.contains("ImageButton") && !empty.contains("ImageView"), "no card, no buttons");
    }

    public static void testMusicButtons56dpWith32dpGlyphs() {
        List<String> buttons = elements(read("layout/widget_music.xml"), "ImageButton");
        T.eq(3, buttons.size(), "three buttons");
        for (String b : buttons) {
            T.eq("56dp", attr(b, "layout_width"), "touch width");
            T.eq("56dp", attr(b, "layout_height"), "touch height");
            T.eq("center", attr(b, "scaleType"), "glyph at its own size");
        }
        T.check(56 <= 95 - 2 * 16, "fits the 1-row 95 dp widget inside the 16 dp pad");
        for (String icon : new String[] {"ic_play", "ic_pause", "ic_next", "ic_prev"}) {
            String v = elements(read("drawable/" + icon + ".xml"), "vector").get(0);
            T.eq("32dp", attr(v, "width"), icon + " width");
            T.eq("32dp", attr(v, "height"), icon + " height");
            T.eq("32", attr(v, "viewportWidth"), icon + " viewport");
            T.eq("32", attr(v, "viewportHeight"), icon + " viewport");
            T.check(!read("drawable/" + icon + ".xml").contains("#"), icon + " uses @color/dot_l100");
        }
    }

    public static void testFontResourcesExist() {
        for (String f : new String[] {"dot_matrix.ttf", "dot_matrix_grid.ttf", "inter.ttf"}) {
            T.check(Files.isRegularFile(Paths.get(RES + "font/" + f)), "res/font/" + f);
        }
    }
}
