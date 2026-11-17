package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public final class BlueLineTest {
    private static String fixture(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read fixture " + name + ": " + e);
        }
    }

    public static void testTableMatchesCsv() {
        String[] lines = fixture("cta-blue-stations.csv").split("\r?\n");
        T.eq("mapid,name,lat,lon", lines[0], "header");
        int n = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            String[] f = lines[i].split(",");
            T.eq(4, f.length, "columns row " + i);
            T.eq(Integer.parseInt(f[0]), BlueLineStations.IDS[n], "id " + n);
            T.eq(f[1], BlueLineStations.NAMES[n], "name " + n);
            T.near(Double.parseDouble(f[2]), BlueLineStations.LAT[n], 1e-6, "lat " + n);
            T.near(Double.parseDouble(f[3]), BlueLineStations.LON[n], 1e-6, "lon " + n);
            n++;
        }
        T.eq(33, n, "csv rows");
        T.eq(33, BlueLineStations.IDS.length, "table rows");
    }

    public static void testNearest() {
        T.eq(40380, BlueLineStations.nearest(41.8857, -87.6309, 5), "clark/lake");
        T.eq(40980, BlueLineStations.nearest(41.8735, -87.8069, 5), "harlem forest park");
        T.eq(-1, BlueLineStations.nearest(40.7128, -74.0060, 10), "new york");
    }

    public static void testLabels() {
        T.eq("CLARK/LAKE", BlueLineStations.label(40380), "clark");
        T.eq("WESTERN (O'HARE BR)", BlueLineStations.label(40670), "western oh");
        T.eq("WESTERN (FOREST PK BR)", BlueLineStations.label(40220), "western fp");
        T.eq("HARLEM (O'HARE BR)", BlueLineStations.label(40750), "harlem oh");
        T.eq("HARLEM (FOREST PK BR)", BlueLineStations.label(40980), "harlem fp");
        T.eq(16, BlueLineStations.index(40380), "index");
        T.eq(-1, BlueLineStations.index(1), "unknown index");
    }

    public static void testParsesFixture() {
        CtaBoard b = CtaParser.parse(fixture("cta-arrivals.json"));
        T.eq(0, b.errCd, "errCd");
        T.eq(4, b.ohare.size(), "ohare size");
        T.eq(3, b.forestPark.size(), "fp size");
        String[] od = {"O'HARE", "JEFFERSON PARK", "O'HARE", "O'HARE"};
        int[] om = {0, 7, 15, 24};
        for (int i = 0; i < 4; i++) {
            T.eq(od[i], b.ohare.get(i).dest, "ohare dest " + i);
            T.eq(om[i], b.ohare.get(i).minutes, "ohare min " + i);
        }
        String[] fd = {"FOREST PARK", "UIC-HALSTED", "FOREST PARK"};
        int[] fm = {4, 12, 20};
        for (int i = 0; i < 3; i++) {
            T.eq(fd[i], b.forestPark.get(i).dest, "fp dest " + i);
            T.eq(fm[i], b.forestPark.get(i).minutes, "fp min " + i);
        }
        T.check(b.ohare.get(0).due, "first due");
        T.check(!b.ohare.get(1).due, "second not due");
        T.eq("DUE", PlatformBoard.text(b.ohare.get(0)), "due text");
        T.eq("4", PlatformBoard.text(b.forestPark.get(0)), "minutes text");
        T.eq(3, PlatformBoard.ROWS, "rows");
    }

    private static void empty(CtaBoard b, int err, String what) {
        T.eq(err, b.errCd, what + " errCd");
        T.check(b.ohare.isEmpty() && b.forestPark.isEmpty(), what + " empty");
    }

    public static void testGarbageAndBadKey() {
        empty(CtaParser.parse(fixture("cta-arrivals-badkey.json")), 101, "badkey");
        empty(CtaParser.parse("<html>"), -1, "html");
        empty(CtaParser.parse(null), -1, "null");
        empty(CtaParser.parse("{\"ctatt\":{\"errCd\":\"x\",\"eta\":[1,{\"arrT\":\"bad\"}]}}"), -1, "bad errCd");
        List<Arrival> none = CtaParser.parse("{\"ctatt\":{\"tmst\":\"nope\",\"errCd\":\"0\",\"eta\":[{\"trDr\":\"1\"}]}}").ohare;
        T.check(none.isEmpty(), "unparseable times skipped");
    }

    /** CTA's JSON is converted from XML: a single prediction arrives as an object, not a one-element array. */
    public static void testSingleEtaObject() {
        String json = "{\"ctatt\":{\"tmst\":\"2026-11-17T08:00:00\",\"errCd\":\"0\",\"eta\":"
            + "{\"destNm\":\"Forest Park\",\"trDr\":\"5\",\"arrT\":\"2026-11-17T08:06:30\",\"isApp\":\"0\"}}}";
        CtaBoard b = CtaParser.parse(json);
        T.eq(0, b.errCd, "errCd");
        T.check(b.ohare.isEmpty(), "no o'hare trains");
        T.eq(1, b.forestPark.size(), "one forest park train");
        T.eq("FOREST PARK", b.forestPark.get(0).dest, "dest");
        T.eq(6, b.forestPark.get(0).minutes, "minutes");
        T.check(!b.forestPark.get(0).due, "not due");
    }

    public static void testRowsFit() {
        T.eq(0, PlatformBoard.rowsFit(80), "headers only");
        T.eq(1, PlatformBoard.rowsFit(110), "min resize height");
        T.eq(1, PlatformBoard.rowsFit(135), "just short of two");
        T.eq(2, PlatformBoard.rowsFit(136), "two rows");
        T.eq(3, PlatformBoard.rowsFit(168), "three rows");
        T.eq(3, PlatformBoard.rowsFit(400), "never more than ROWS");
    }

    public static void testAgedMinutes() {
        List<Arrival> in = java.util.Arrays.asList(new Arrival("O'HARE", 0, true), new Arrival("O'HARE", 3, false),
            new Arrival("JEFFERSON PARK", 7, false));
        T.check(PlatformBoard.aged(in, 0) == in, "fresh board unchanged");
        List<Arrival> two = PlatformBoard.aged(in, 2);
        T.eq(2, two.size(), "the train already gone is dropped");
        T.eq(1, two.get(0).minutes, "3 - 2");
        T.check(two.get(0).due, "1 min is due");
        T.eq("DUE", PlatformBoard.text(two.get(0)), "due text");
        T.eq(5, two.get(1).minutes, "7 - 2");
        T.check(!two.get(1).due, "5 min not due");
        T.eq("JEFFERSON PARK", two.get(1).dest, "dest kept");
        T.check(PlatformBoard.aged(in, 8).isEmpty(), "all gone");
    }

    public static void testUrlEncodesKey() {
        String u = CtaUrls.arrivals("a b&c", 40380);
        T.check(u.startsWith("https://lapi.transitchicago.com/api/1.0/ttarrivals.aspx?key=a+b%26c&"), "key " + u);
        T.check(u.contains("mapid=40380"), "mapid");
        T.check(u.contains("rt=Blue"), "rt");
        T.check(u.endsWith("outputType=JSON"), "output");
    }
}
