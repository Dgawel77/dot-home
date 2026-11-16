package dev.gawel.dothome.test;

import dev.gawel.dothome.core.KeyValueStore;
import java.util.HashMap;
import java.util.Map;

/** In-memory KeyValueStore for tests. */
public final class MapStore implements KeyValueStore {
    private final Map<String, String> m = new HashMap<>();
    public String get(String key, String def) { return m.containsKey(key) ? m.get(key) : def; }
    public void put(String key, String value) { m.put(key, value); }
}
