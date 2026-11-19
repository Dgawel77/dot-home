package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hue Bridge's local API: CLIP v2 room/zone and grouped_light responses ({"errors":[],"data":[...]}) and the
 * v1 pairing response ([{"success":{"username":...}}] or [{"error":{"type":101,...}}]). Bad input gives an empty
 * result, never an exception.
 */
public final class HueParser {
    private HueParser() {}

    /** The rooms (or zones) that have a grouped_light service, in the bridge's order. */
    public static List<HueGroup> groups(String json, boolean zone) {
        List<HueGroup> out = new ArrayList<>();
        for (Object item : data(json)) {
            Map<String, Object> o = MiniJson.obj(item);
            if (o == null) continue;
            String id = MiniJson.str(o.get("id"), null);
            Map<String, Object> meta = MiniJson.obj(o.get("metadata"));
            String name = meta == null ? null : MiniJson.str(meta.get("name"), null);
            String light = groupedLight(MiniJson.arr(o.get("services")));
            if (id == null || name == null || light == null) continue;
            out.add(new HueGroup(id, name, light, zone));
        }
        return out;
    }

    private static String groupedLight(List<Object> services) {
        if (services == null) return null;
        for (Object s : services) {
            Map<String, Object> o = MiniJson.obj(s);
            if (o != null && "grouped_light".equals(o.get("rtype"))) return MiniJson.str(o.get("rid"), null);
        }
        return null;
    }

    /** grouped_light states by id, skipping the bridge_home group (every light in the home). */
    public static Map<String, HueState> states(String json) {
        Map<String, HueState> out = new LinkedHashMap<>();
        for (Object item : data(json)) {
            Map<String, Object> o = MiniJson.obj(item);
            if (o == null) continue;
            String id = MiniJson.str(o.get("id"), null);
            Map<String, Object> owner = MiniJson.obj(o.get("owner"));
            if (id == null || (owner != null && "bridge_home".equals(owner.get("rtype")))) continue;
            Map<String, Object> on = MiniJson.obj(o.get("on"));
            Map<String, Object> dim = MiniJson.obj(o.get("dimming"));
            boolean isOn = on != null && Boolean.TRUE.equals(on.get("on"));
            double b = dim == null ? 100.0 : MiniJson.num(dim.get("brightness"), 100.0);
            out.put(id, new HueState(isOn, dim != null, b));
        }
        return out;
    }

    /** The new app key from a pairing response, or null. */
    public static String pairKey(String json) {
        Map<String, Object> first = firstOf(json);
        Map<String, Object> ok = first == null ? null : MiniJson.obj(first.get("success"));
        return ok == null ? null : MiniJson.str(ok.get("username"), null);
    }

    /** True when the pairing response says the bridge's link button has not been pressed yet (error type 101). */
    public static boolean linkButtonNeeded(String json) {
        Map<String, Object> first = firstOf(json);
        Map<String, Object> err = first == null ? null : MiniJson.obj(first.get("error"));
        return err != null && MiniJson.num(err.get("type"), -1) == 101;
    }

    private static Map<String, Object> firstOf(String json) {
        List<Object> a = MiniJson.arr(parse(json));
        return a == null || a.isEmpty() ? null : MiniJson.obj(a.get(0));
    }

    private static List<Object> data(String json) {
        Map<String, Object> root = MiniJson.obj(parse(json));
        List<Object> d = root == null ? null : MiniJson.arr(root.get("data"));
        return d == null ? Collections.emptyList() : d;
    }

    private static Object parse(String json) {
        if (json == null) return null;
        try {
            return MiniJson.parse(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
