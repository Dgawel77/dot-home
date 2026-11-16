package dev.gawel.dothome.test;

import dev.gawel.dothome.core.MiniJson;
import java.util.List;
import java.util.Map;

public final class MiniJsonTest {
    public static void testParseObjectsArraysNumbers() {
        Map<String, Object> o = MiniJson.obj(MiniJson.parse(
            "{\"a\":[1,-2.5,3e2,true,false,null],\"b\":{\"c\":\"x\"}, \"d\": 1.5E-1}"));
        List<Object> a = MiniJson.arr(o.get("a"));
        T.eq(6, a.size(), "len");
        T.near(1, MiniJson.num(a.get(0), -1), 0, "int");
        T.near(-2.5, MiniJson.num(a.get(1), 0), 0, "neg decimal");
        T.near(300, MiniJson.num(a.get(2), 0), 0, "exp");
        T.eq(Boolean.TRUE, a.get(3), "true");
        T.eq(Boolean.FALSE, a.get(4), "false");
        T.eq(null, a.get(5), "null");
        T.eq("x", MiniJson.str(MiniJson.obj(o.get("b")).get("c"), "?"), "nested");
        T.near(0.15, MiniJson.num(o.get("d"), 0), 1e-12, "exp neg");
        T.near(7, MiniJson.num("nope", 7), 0, "num default");
        T.eq("d", MiniJson.str(5.0, "d"), "str default");
    }

    public static void testParseStringEscapes() {
        T.eq("a\"b\\c/d\b\f\n\r\t", MiniJson.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""), "escapes");
        T.eq("33\u00b0", MiniJson.parse("\"33\\u00b0\""), "unicode escape");
        T.eq("33\u00b0", MiniJson.parse("\"33\u00b0\""), "literal degree");
    }

    public static void testParseMalformedThrows() {
        String[] bad = {"", "{", "{\"a\":", "<html>", "[1,]", "{\"a\" 1}", "\"abc", "1 2", "tru", "\"\\q\""};
        for (String s : bad) {
            boolean threw = false;
            try { MiniJson.parse(s); } catch (IllegalArgumentException e) { threw = true; }
            T.check(threw, "should throw: " + s);
        }
    }

    private static String nested(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i % 2 == 0 ? "[" : "{\"a\":");
        b.append("1");
        for (int i = n - 1; i >= 0; i--) b.append(i % 2 == 0 ? "]" : "}");
        return b.toString();
    }

    public static void testNestingDepthCap() {
        MiniJson.parse(nested(64)); // exactly at the cap parses
        boolean threw = false;
        try { MiniJson.parse(nested(65)); } catch (IllegalArgumentException e) { threw = true; }
        T.check(threw, "65 levels should throw");
        threw = false;
        try { MiniJson.parse(nested(100000)); } catch (IllegalArgumentException e) { threw = true; }
        T.check(threw, "absurd depth throws IAE, not StackOverflowError");
    }
}
