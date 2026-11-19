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

    public static void testClockIsBitmapsOnly() {
        String xml = read("layout/widget_clock.xml");
        T.check(!xml.contains("<TextClock"), "One UI ignores widget fonts: no TextClock");
        for (String id : new String[] {"time_img", "date_img", "weather_img"}) byId(elements(xml, "ImageView"), id);
        T.check(xml.contains("android:padding=\"" + ClockFit.PAD_DP + "dp\""), "pad");
        T.check(xml.contains("android:gravity=\"center_vertical\""), "block centred vertically");
        T.check(xml.contains("android:layout_height=\"" + ClockFit.ROW_DP + "dp\""), "row height");
        T.check(xml.contains("android:layout_marginTop=\"" + ClockFit.GAP_DP + "dp\""), "gap");
        T.eq(12, ClockFit.GAP_DP, "gap is 12 dp");
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

    public static void testRadarToggleTouchTarget() {
        String toggle = byId(elements(read("layout/widget_radar.xml"), "ImageView"), "radar_toggle");
        T.eq("48dp", attr(toggle, "layout_height"), "48 dp touch target");
        T.eq("top|end", attr(toggle, "layout_gravity"), "top right");
    }
    public static void testBearsWidgetResources() {
        String info = read("xml/widget_bears_info.xml");
        T.check(info.contains("android:previewImage=\"@drawable/preview_bears\""), "picker preview");
        T.check(info.contains("android:initialLayout=\"@layout/widget_bears_empty\""), "a new Bears widget starts blank");
        String empty = read("layout/widget_bears_empty.xml");
        T.eq(1, elements(empty, "FrameLayout").size(), "one view");
        T.check(!empty.contains("ImageView"), "no card, no image");
        T.check(read("values/strings.xml").contains("<string name=\"widget_bears_label\">Dot Bears</string>"), "label");
        T.check(Files.exists(Paths.get(RES + "drawable-nodpi/preview_bears.png")), "preview png");
    }

    public static void testBlueLineWidgetResources() {
        String info = read("xml/widget_blueline_info.xml");
        T.check(info.contains("android:previewImage=\"@drawable/preview_blueline\""), "picker preview");
        T.check(info.contains("android:initialLayout=\"@layout/widget_blueline_empty\""),
            "a new Blue Line widget starts blank");
        T.check(info.contains("android:minWidth=\"250dp\""), "min width 250 dp");
        T.check(info.contains("android:minResizeHeight=\"110dp\""), "shrinks to 110 dp");
        Matcher mh = Pattern.compile("android:minHeight=\"(\\d+)dp\"").matcher(info);
        T.check(mh.find() && Integer.parseInt(mh.group(1)) >= 136, "placed tall enough for 2+ arrivals");
        T.check(PlatformBoard.rowsFit(Integer.parseInt(mh.group(1))) >= 2, "minHeight fits 2+ rows");
        T.check(info.contains("android:targetCellHeight=\"3\""), "3 cells tall by default");
        String empty = read("layout/widget_blueline_empty.xml");
        T.eq(1, elements(empty, "FrameLayout").size(), "one view");
        T.check(!empty.contains("ImageView"), "no card, no image");
        byId(elements(read("layout/widget_blueline.xml"), "ImageView"), "blueline_img");
        T.check(read("values/strings.xml").contains("<string name=\"widget_blueline_label\">Dot Blue Line</string>"),
            "label");
        T.check(Files.exists(Paths.get(RES + "drawable-nodpi/preview_blueline.png")), "preview png");
    }

    private static final Pattern PLAIN_VIEW = Pattern.compile("<View[\\s/>]");

    public static void testPlainViewPatternCatchesNewline() {
        T.check(PLAIN_VIEW.matcher("<View\n    android:id=\"@+id/x\" />").find(), "newline after <View");
        T.check(PLAIN_VIEW.matcher("<View />").find() && PLAIN_VIEW.matcher("<View/>").find()
            && PLAIN_VIEW.matcher("<View>").find() && PLAIN_VIEW.matcher("<View\t").find(), "space, slash, >, tab");
        T.check(!PLAIN_VIEW.matcher("<ViewFlipper android:id=\"@+id/x\">").find(), "ViewFlipper is not a View");
    }

    public static void testNoPlainViewInWidgetLayouts() {
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(Paths.get(RES + "layout"))) {
            int n = 0;
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.startsWith("widget_") || !name.endsWith(".xml")) continue;
                String xml = read("layout/" + name);
                T.check(!PLAIN_VIEW.matcher(xml).find(), name + " has no plain View");
                n++;
            }
            T.check(n >= 7, "widget layouts found");
        } catch (IOException e) {
            throw new AssertionError("cannot list layouts: " + e);
        }
    }
    /** Dot Hue: 4 tap rows, each a name cell and 5 dot cells as wide as HueLayout.DOT_CELL_DP (placed at runtime). */
    public static void testHueLayoutIds() {
        String xml = read("layout/widget_hue.xml");
        List<String> cells = elements(xml, "FrameLayout");
        List<String> rows = elements(xml, "LinearLayout");
        for (int i = 0; i < HueRows.MAX; i++) {
            T.check(byId(rows, "hue_row_" + i) != null, "row " + i);
            T.check(byId(cells, "hue_r" + i + "_name") != null, "name cell " + i);
            for (int d = 0; d < HueRows.DOTS; d++) {
                String c = byId(cells, "hue_r" + i + "_d" + d);
                T.check(c != null, "dot cell " + i + "/" + d);
                T.eq(Math.round(HueLayout.DOT_CELL_DP) + "dp", attr(c, "layout_width"), "dot cell width " + i + "/" + d);
            }
        }
        T.check(byId(elements(xml, "ImageView"), "hue_img") != null, "art");
    }
}
