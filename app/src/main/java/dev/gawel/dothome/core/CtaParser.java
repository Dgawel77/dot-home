package dev.gawel.dothome.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the Train Tracker ttarrivals JSON. Never throws; garbage gives errCd -1 and empty lists. */
public final class CtaParser {
    private CtaParser() {}

    private static final Comparator<Arrival> BY_MINUTES = new Comparator<Arrival>() {
        @Override public int compare(Arrival a, Arrival b) { return Integer.compare(a.minutes, b.minutes); }
    };

    public static CtaBoard parse(String json) {
        List<Arrival> oh = new ArrayList<Arrival>();
        List<Arrival> fp = new ArrayList<Arrival>();
        if (json == null) return new CtaBoard(-1, oh, fp);
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            Map<String, Object> tt = root == null ? null : MiniJson.obj(root.get("ctatt"));
            if (tt == null) return new CtaBoard(-1, oh, fp);
            int err;
            try {
                err = Integer.parseInt(MiniJson.str(tt.get("errCd"), "").trim());
            } catch (NumberFormatException e) {
                return new CtaBoard(-1, oh, fp);
            }
            if (err != 0) return new CtaBoard(err, oh, fp);
            Object etaRaw = tt.get("eta");
            List<Object> eta = MiniJson.arr(etaRaw);
            // the JSON is converted from XML: a lone prediction is an object, not a one-element array
            if (eta == null && MiniJson.obj(etaRaw) != null) eta = Collections.singletonList(etaRaw);
            LocalDateTime now = time(tt.get("tmst"));
            if (eta != null && now != null) {
                for (Object o : eta) {
                    Map<String, Object> e = MiniJson.obj(o);
                    if (e == null) continue;
                    LocalDateTime arr = time(e.get("arrT"));
                    if (arr == null) continue;
                    String dir = MiniJson.str(e.get("trDr"), "");
                    long min = Math.max(0L, Math.floorDiv(Duration.between(now, arr).getSeconds(), 60L));
                    int m = (int) Math.min(min, 9999L);
                    boolean due = "1".equals(MiniJson.str(e.get("isApp"), "")) || m <= 1;
                    Arrival a = new Arrival(MiniJson.str(e.get("destNm"), "").toUpperCase(Locale.ROOT), m, due);
                    if ("1".equals(dir)) oh.add(a);
                    else if ("5".equals(dir)) fp.add(a);
                }
            }
            Collections.sort(oh, BY_MINUTES);
            Collections.sort(fp, BY_MINUTES);
            return new CtaBoard(0, oh, fp);
        } catch (RuntimeException ex) {
            return new CtaBoard(-1, new ArrayList<Arrival>(), new ArrayList<Arrival>());
        }
    }

    private static LocalDateTime time(Object o) {
        String s = MiniJson.str(o, null);
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
