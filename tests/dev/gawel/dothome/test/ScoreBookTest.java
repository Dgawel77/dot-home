package dev.gawel.dothome.test;

import dev.gawel.dothome.core.ScoreBook;
import java.util.Calendar;
import java.util.TimeZone;

public final class ScoreBookTest {
    private static final TimeZone TZ = TimeZone.getTimeZone("America/Chicago");

    private static long at(int day, int h, int m, int s) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, h, m, s);
        return c.getTimeInMillis();
    }

    public static void testAddRockCountsAndBest() {
        long[] now = {at(15, 12, 0, 0)};
        ScoreBook b = new ScoreBook(new MapStore(), () -> now[0], TZ);
        T.eq(0, b.today(), "today starts 0");
        b.addRock(); b.addRock();
        T.eq(2, b.today(), "today");
        T.eq(2, b.best(), "best follows today");
    }

    public static void testMidnightRolloverResetsOnce() {
        long[] now = {at(15, 23, 59, 50)};
        ScoreBook b = new ScoreBook(new MapStore(), () -> now[0], TZ);
        b.addRock(); b.addRock(); b.addRock();
        now[0] = at(16, 0, 0, 5);
        b.refresh();
        T.eq(0, b.today(), "reset after midnight");
        T.eq(3, b.best(), "best kept");
        b.refresh();
        T.eq(0, b.today(), "refresh idempotent");
        b.addRock();
        T.eq(1, b.today(), "new day counts");
        T.eq(3, b.best(), "best still 3");
    }

    public static void testRockJustAfterMidnightCountsForNewDay() {
        long[] now = {at(15, 23, 59, 50)};
        ScoreBook b = new ScoreBook(new MapStore(), () -> now[0], TZ);
        b.addRock(); b.addRock();
        now[0] = at(16, 0, 0, 1);
        b.addRock();
        T.eq(1, b.today(), "rollover inside addRock");
        T.eq(2, b.best(), "best");
    }

    public static void testPersistsAcrossInstances() {
        MapStore kv = new MapStore();
        long[] now = {at(15, 12, 0, 0)};
        ScoreBook a = new ScoreBook(kv, () -> now[0], TZ);
        a.addRock(); a.addRock();
        ScoreBook b = new ScoreBook(kv, () -> now[0], TZ);
        T.eq(2, b.today(), "today persisted");
        T.eq(2, b.best(), "best persisted");
        T.eq("2026-11-15", kv.get("score.day", ""), "day key");
    }
}
