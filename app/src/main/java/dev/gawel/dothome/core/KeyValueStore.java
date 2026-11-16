package dev.gawel.dothome.core;

/** Minimal string key-value persistence, implemented over SharedPreferences on Android. */
public interface KeyValueStore {
    String get(String key, String def);
    void put(String key, String value);
}
