package dev.gawel.dothome.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.LongSupplier;

/** Daily rock count with an all-time best day. Resets today's count when the local date changes. */
public final class ScoreBook {
    private static final String K_DAY = "score.day";
    private static final String K_TODAY = "score.today";
    private static final String K_BEST = "score.best";

    private final KeyValueStore kv;
    private final LongSupplier nowMillis;
    private final SimpleDateFormat fmt;

    public ScoreBook(KeyValueStore kv, LongSupplier nowMillis, TimeZone tz) {
        this.kv = kv;
        this.nowMillis = nowMillis;
        this.fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.fmt.setTimeZone(tz);
        refresh();
    }

    public int today() { return readInt(K_TODAY); }

    public int best() { return readInt(K_BEST); }

    public void addRock() {
        refresh();
        int t = today() + 1;
        kv.put(K_TODAY, Integer.toString(t));
        if (t > best()) kv.put(K_BEST, Integer.toString(t));
    }

    /** Rolls over to a new day if the date changed; safe to call repeatedly. */
    public void refresh() {
        String day = fmt.format(new Date(nowMillis.getAsLong()));
        if (!day.equals(kv.get(K_DAY, ""))) {
            kv.put(K_DAY, day);
            kv.put(K_TODAY, "0");
        }
    }

    private int readInt(String key) {
        try {
            return Integer.parseInt(kv.get(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
