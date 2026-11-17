package dev.gawel.dothome.core;

/**
 * A fixed pool of dots that stream from the screen edges into the ship while the phone charges. Each dot homes on
 * the ship and is absorbed on arrival; its glow rises from 0 to 1 as it closes. Allocation-free.
 */
public final class ChargeStream {
    public static final int CAP = 64;
    /** The homing filter's time constant, s. */
    private static final float TAU = 0.25f;
    /** A dot is absorbed within this multiple of the ship's radius. */
    private static final float ABSORB = 0.6f;

    public final float[] x = new float[CAP], y = new float[CAP], vx = new float[CAP], vy = new float[CAP];
    /** 0..1: the dot's level, L46 (0) to L65 (1). */
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
            // a point on the perimeter, chosen uniformly by length
            float t = (float) rng.next() * 2f * (w + h);
            float px, py;
            if (t < w) { px = t; py = 0f; }
            else if (t < w + h) { px = w; py = t - w; }
            else if (t < 2f * w + h) { px = t - w - h; py = h; }
            else { px = 0f; py = t - 2f * w - h; }
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
