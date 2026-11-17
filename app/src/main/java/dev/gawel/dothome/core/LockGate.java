package dev.gawel.dothome.core;

/** Decides when the lock still is worth re-rendering: at most once a minute, and only if the frame changed. */
public final class LockGate {
    public static final long MIN_GAP_MS = 60_000;

    private boolean has;
    private long lastMs;
    private int lastFp;

    /** The score times 64 plus the ship's cell on an 8 x 8 grid. */
    public static int fingerprint(int today, float shipX, float shipY, float w, float h) {
        return today * 64 + cell(shipY, h) * 8 + cell(shipX, w);
    }

    private static int cell(float v, float size) {
        if (!(size > 0f)) return 0;
        int c = (int) Math.floor(v / size * 8f);
        return c < 0 ? 0 : (c > 7 ? 7 : c);
    }

    public boolean shouldPush(long nowMs, int fp) {
        return !has || (nowMs - lastMs >= MIN_GAP_MS && fp != lastFp);
    }

    public void pushed(long nowMs, int fp) {
        has = true;
        lastMs = nowMs;
        lastFp = fp;
    }
}
