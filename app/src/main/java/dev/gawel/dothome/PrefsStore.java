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
    /**
     * Clock widget time: "text" (default) is the matrix font in TextClocks, ticking the seconds in the launcher;
     * "bitmap" is the per-minute dot bitmap, the fallback for a launcher that cannot render the font. Hidden: set it
     * with the SetupActivity extra of the same name.
     */
    static final String KEY_CLOCK_MODE = "widget.clockMode";
    static final String CLOCK_TEXT = "text";
    static final String CLOCK_BITMAP = "bitmap";

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

    void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    /** True when the user picked outline rocks; anything else (including unset) means lattice. */
    boolean outlineRocks() {
        return ROCK_OUTLINE.equals(get(KEY_ROCK_STYLE, ROCK_LATTICE));
    }

    /** True when the clock widget should draw the per-minute bitmap time; anything else means TextClocks. */
    boolean bitmapClock() {
        return CLOCK_BITMAP.equals(get(KEY_CLOCK_MODE, CLOCK_TEXT));
    }
}
