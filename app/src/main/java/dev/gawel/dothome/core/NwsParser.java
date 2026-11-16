package dev.gawel.dothome.core;

import java.util.List;
import java.util.Map;

/** Picks the headline alert from an NWS alerts response. */
public final class NwsParser {
    private NwsParser() {}

    /** The event of the most severe feature (first wins on ties), or null if none. */
    public static String headline(String json) {
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            if (root == null) return null;
            List<Object> features = MiniJson.arr(root.get("features"));
            if (features == null) return null;
            String best = null;
            int bestRank = -1;
            for (Object f : features) {
                Map<String, Object> props = MiniJson.obj(MiniJson.obj(f) == null ? null : MiniJson.obj(f).get("properties"));
                if (props == null) continue;
                String event = MiniJson.str(props.get("event"), null);
                if (event == null) continue;
                int r = rank(MiniJson.str(props.get("severity"), ""));
                if (r > bestRank) {
                    best = event;
                    bestRank = r;
                }
            }
            return best;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int rank(String severity) {
        switch (severity) {
            case "Extreme": return 4;
            case "Severe": return 3;
            case "Moderate": return 2;
            case "Minor": return 1;
            default: return 0;
        }
    }
}
