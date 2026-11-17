package dev.gawel.dothome.core;

/** The daily rock counts that trigger an easter egg, each firing once per day (persisted). */
public final class Milestones {
    public static final int[] COUNTS = {100, 250, 500, 1000};
    private static final String K_DAY = "milestone.day";
    private static final String K_FIRED = "milestone.fired";

    private final KeyValueStore kv;

    public Milestones(KeyValueStore kv) { this.kv = kv; }

    /** The milestone this count hits and has not yet fired today, or 0. */
    public int reached(int todayCount, String day) {
        int idx = -1;
        for (int i = 0; i < COUNTS.length; i++) if (COUNTS[i] == todayCount) idx = i;
        if (idx < 0) return 0;
        int fired = 0;
        if (day.equals(kv.get(K_DAY, ""))) {
            try {
                fired = Integer.parseInt(kv.get(K_FIRED, "0"));
            } catch (NumberFormatException e) {
                fired = 0;
            }
        }
        if ((fired & (1 << idx)) != 0) return 0;
        kv.put(K_DAY, day);
        kv.put(K_FIRED, Integer.toString(fired | (1 << idx)));
        return COUNTS[idx];
    }
}
