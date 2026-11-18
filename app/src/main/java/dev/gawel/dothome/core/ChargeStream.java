package dev.gawel.dothome.core;

/**
 * A fixed pool of dots that spawn a short way from the ship (SPAWN_NEAR..SPAWN_FAR of the short side, all round it,
 * kept on the screen) and stream into it while the phone charges. Each dot homes on
 * the ship and is absorbed on arrival; its glow rises from 0 to 1 as it closes. Allocation-free.
 */
public final class ChargeStream {
    public static final int CAP = 64;
    /** The homing filter's time constant, s. */
    private static final float TAU = 0.25f;
    /** A dot is absorbed within this multiple of the ship's radius. */
    private static final float ABSORB = 0.6f;
    /** Dots spawn this far from the ship, as fractions of the screen's short side. */
    public static final float SPAWN_NEAR = 0.15f, SPAWN_FAR = 0.30f;

    public final float[] x = new float[CAP], y = new float[CAP], vx = new float[CAP], vy = new float[CAP];
    /** 0..1: the dot's level, L30 (0) to L46 (1). */
    public final float[] glow = new float[CAP];
    public int n;
    public long spawned, absorbed;

    private final float[] dist0 = new float[CAP];
    private float budget;

    public void step(float dt, boolean on, float shipX, float shipY, float w, float h, GameConfig cfg, Rng rng) {
        float blend = 1f - (float) Math.exp(-dt / TAU);
        float absorbR = cfg.shipRadius * ABSORB;
        for (int i = n - 1; i >= 0; i--) {
            float dx = shipX - x[i], dy = shipY - y[i];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= absorbR) { remove(i); absorbed++; continue; }
            vx[i] += (dx / d * cfg.chargeSpeed - vx[i]) * blend;
            vy[i] += (dy / d * cfg.chargeSpeed - vy[i]) * blend;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            float nx = shipX - x[i], ny = shipY - y[i];
            float g = 1f - (float) Math.sqrt(nx * nx + ny * ny) / dist0[i];
            glow[i] = g < 0f ? 0f : g > 1f ? 1f : g;
        }
        if (!on) { budget = 0f; return; }
        budget += cfg.chargeRate * dt;
        while (budget >= 1f && n < CAP) {
            budget -= 1f;
            // a point a short way from the ship, all round it, kept on the screen
            float a = (float) (rng.next() * 2 * Math.PI);
            float r = (SPAWN_NEAR + (SPAWN_FAR - SPAWN_NEAR) * (float) rng.next()) * Math.min(w, h);
            float px = shipX + (float) Math.cos(a) * r, py = shipY + (float) Math.sin(a) * r;
            px = px < 0f ? 0f : px > w ? w : px;
            py = py < 0f ? 0f : py > h ? h : py;
            float dx = shipX - px, dy = shipY - py;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= absorbR) continue; // already on the ship
            x[n] = px; y[n] = py;
            vx[n] = dx / d * cfg.chargeSpeed * 0.25f;
            vy[n] = dy / d * cfg.chargeSpeed * 0.25f;
            dist0[n] = d;
            glow[n] = 0f;
            n++;
            spawned++;
        }
        if (n >= CAP) budget = Math.min(budget, 1f);
    }

    private void remove(int i) {
        int l = --n;
        x[i] = x[l]; y[i] = y[l]; vx[i] = vx[l]; vy[i] = vy[l]; glow[i] = glow[l]; dist0[i] = dist0[l];
    }

    /** The surface resized: keep the dots where they are on screen. */
    public void scale(float sx, float sy) {
        for (int i = 0; i < n; i++) { x[i] *= sx; y[i] *= sy; dist0[i] *= (sx + sy) * 0.5f; }
    }
}
