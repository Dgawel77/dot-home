package dev.gawel.dothome.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public final class CtaUrls {
    private CtaUrls() {}

    public static String arrivals(String key, int mapid) {
        String k;
        try {
            k = URLEncoder.encode(key == null ? "" : key, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unsupported"); // never include the key here
        }
        return "https://lapi.transitchicago.com/api/1.0/ttarrivals.aspx?key=" + k
                + "&mapid=" + mapid + "&rt=Blue&outputType=JSON";
    }
}
