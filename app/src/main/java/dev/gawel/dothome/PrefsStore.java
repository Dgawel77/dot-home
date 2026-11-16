package dev.gawel.dothome;

import android.content.Context;
import android.content.SharedPreferences;

import dev.gawel.dothome.core.KeyValueStore;

/** KeyValueStore over SharedPreferences. Writes are applied asynchronously; the framework persists them. */
final class PrefsStore implements KeyValueStore {
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
}
