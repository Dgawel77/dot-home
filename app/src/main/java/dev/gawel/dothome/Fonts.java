package dev.gawel.dothome;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Loads the bundled fonts from assets (variable 'wght' axis), cached per path+weight. */
final class Fonts {
    static final String DOTO = "fonts/Doto-Regular.ttf";
    static final String INTER = "fonts/Inter-Regular.ttf";
    static final String INTER_MEDIUM = "fonts/Inter-Medium.ttf";

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private Fonts() {}

    static Typeface doto(Context c) { return load(c.getAssets(), DOTO, 400); }

    static Typeface inter(Context c) { return load(c.getAssets(), INTER, 400); }

    static Typeface interMedium(Context c) { return load(c.getAssets(), INTER_MEDIUM, 500); }

    static Typeface interBold(Context c) { return load(c.getAssets(), INTER, 700); }

    static synchronized Typeface load(AssetManager assets, String path, int weight) {
        String key = path + "@" + weight;
        Typeface cached = CACHE.get(key);
        if (cached != null) return cached;
        Typeface result = Typeface.MONOSPACE;
        try {
            Typeface t = new Typeface.Builder(assets, path).setFontVariationSettings("'wght' " + weight).build();
            if (t != null) result = t;
            else Log.w(DotWallpaperService.TAG, "font " + path + " could not be built, using monospace");
        } catch (RuntimeException e) {
            Log.w(DotWallpaperService.TAG, "font " + path + " failed: " + e);
        }
        CACHE.put(key, result);
        return result;
    }
}
