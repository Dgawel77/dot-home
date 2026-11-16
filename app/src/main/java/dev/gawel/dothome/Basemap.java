package dev.gawel.dothome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gawel.dothome.core.MiniJson;

/** The bundled Natural Earth/OSM basemap (assets/basemap.json), parsed once per process. All points are lon, lat. */
final class Basemap {
    static final class Road {
        final String ref;
        final double[][] pts;
        Road(String ref, double[][] pts) { this.ref = ref; this.pts = pts; }
    }

    static final class Label {
        final String name;
        final double lat, lon;
        final int rank;
        Label(String name, double lat, double lon, int rank) { this.name = name; this.lat = lat; this.lon = lon; this.rank = rank; }
    }

    final List<double[][]> lakes = new ArrayList<>(), shore = new ArrayList<>(), states = new ArrayList<>();
    final List<Road> roads = new ArrayList<>();
    final List<Label> places = new ArrayList<>(), regions = new ArrayList<>();

    private static Basemap instance;

    private Basemap() {}

    /** Blocking on first call (about 150 KB of JSON); call off the main thread. */
    static synchronized Basemap get(Context ctx) {
        if (instance != null) return instance;
        Basemap b = new Basemap();
        try (InputStream in = ctx.getAssets().open("basemap.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(160 * 1024);
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(new String(out.toByteArray(), StandardCharsets.UTF_8)));
            if (root != null) {
                lines(root.get("lakes"), b.lakes);
                lines(root.get("shore"), b.shore);
                lines(root.get("states"), b.states);
                List<Object> roads = MiniJson.arr(root.get("roads"));
                if (roads != null) for (Object o : roads) {
                    Map<String, Object> r = MiniJson.obj(o);
                    if (r != null) b.roads.add(new Road(MiniJson.str(r.get("ref"), ""), line(r.get("pts"))));
                }
                labels(root.get("places"), b.places);
                labels(root.get("regions"), b.regions);
            }
        } catch (Exception e) { // an unreadable basemap still leaves the radar usable on black
            Log.w("DotHome", "basemap failed: " + e);
        }
        instance = b;
        return b;
    }

    private static void lines(Object o, List<double[][]> into) {
        List<Object> l = MiniJson.arr(o);
        if (l != null) for (Object e : l) into.add(line(e));
    }

    private static double[][] line(Object o) {
        List<Object> l = MiniJson.arr(o);
        if (l == null) return new double[0][];
        double[][] out = new double[l.size()][];
        for (int i = 0; i < out.length; i++) {
            List<Object> p = MiniJson.arr(l.get(i));
            out[i] = p == null || p.size() < 2 ? new double[] {0, 0}
                : new double[] {MiniJson.num(p.get(0), 0), MiniJson.num(p.get(1), 0)};
        }
        return out;
    }

    private static void labels(Object o, List<Label> into) {
        List<Object> l = MiniJson.arr(o);
        if (l == null) return;
        for (Object e : l) {
            Map<String, Object> m = MiniJson.obj(e);
            if (m != null) into.add(new Label(MiniJson.str(m.get("name"), ""), MiniJson.num(m.get("lat"), 0),
                MiniJson.num(m.get("lon"), 0), (int) MiniJson.num(m.get("rank"), 3)));
        }
    }
}
