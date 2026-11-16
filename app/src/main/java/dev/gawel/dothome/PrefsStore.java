package dev.gawel.dothome;

import android.content.Context;
import android.content.SharedPreferences;

import dev.gawel.dothome.core.KeyValueStore;

/** KeyValueStore over SharedPreferences. Writes are applied asynchronously; the framework persists them. */
final class PrefsStore implements KeyValueStore {
    /** Wallpaper rock style: lattice silhouettes (default) or the free outline dots, kept to check shimmer on the device. */
    static final String KEY_ROCK_STYLE = "game.rockStyle";
    static final String ROCK_LATTICE = "lattice";
    static final String ROCK_OUTLINE = "outline";

    private final SharedPreferences prefs;

    PrefsStore(Context context) {
        prefs = context.getSharedPreferences("dothome", Context.MODE_PRIVATE);
    }

    @Override
    public String get(String key, String def) {
        return prefs.getString(key, def);
    }

    @Override
    public void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    /** True when the user picked outline rocks; anything else (including unset) means lattice. */
    boolean outlineRocks() {
        return ROCK_OUTLINE.equals(get(KEY_ROCK_STYLE, ROCK_LATTICE));
    }
}
