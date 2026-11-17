package dev.gawel.dothome.core;

/**
 * A fixed pool of falling snow or rain drops. Drops fall toward gravity (screen-down when the tilt is slight),
 * slanted or drifted by the wind, and are recycled once they leave the screen. Allocation-free.
 */
public final class Precip {
    public static final int CAP = 240;
    /** A drop is recycled this far past an edge, in k units of px. */
    private static final float MARGIN_K = 8f;
    /** The tilt below this magnitude counts as flat: drops fall toward screen-down. */
    private static final float FLAT = 0.2f;
    /** Spawn rate as a multiple of the rate that keeps the target filled, so the sky fills in half a crossing. */
    private static final float FILL = 2f;

    public final float[] x = new float[CAP], y = new float[CAP], vx = new float[CAP], vy = new float[CAP];
    public final float[] size = new float[CAP];
    /** True = L65, false = L46 (snow only). */
    public final boolean[] light = new boolean[CAP];
    public int n;
    /** The kind of the live drops. */
    public boolean snow;

    private final float[] spd = new float[CAP], phase = new float[CAP];
    private float budget, time;

    public void step(float dt, WeatherFx fx, float gravX, float gravY, float w, float h, GameConfig cfg, Rng rng) {
        time += dt;
        float gm = (float) Math.sqrt(gravX * gravX + gravY * gravY);
        float gx = 0f, gy = 1f;
        if (gm >= FLAT) { gx = gravX / gm; gy = gravY / gm; }
        boolean wantSnow = fx.kind == WeatherFx.Kind.SNOW;
        int target = fx.kind == WeatherFx.Kind.NONE ? 0
                : Math.min(CAP, Math.round(Math.min(cfg.precipMax, CAP) * fx.intensity));
        // a change of kind lets the old drops fall out first: nothing clears at once
        if (n > 0 && wantSnow != snow) target = 0;
        float m = MARGIN_K * cfg.k;

        for (int i = 0; i < n; i++) {
            velocity(i, fx, gx, gy, cfg);
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (x[i] < -m || x[i] > w + m || y[i] < -m || y[i] > h + m) remove(i);
        }

        if (target <= 0) { budget = 0f; return; }
        if (n >= target) { budget = Math.min(budget, 1f); return; }
        if (n == 0) snow = wantSnow;
        float speed = wantSnow ? cfg.snowFall : cfg.rainFall;
        float cross = (Math.abs(gx) * w + Math.abs(gy) * h) / speed;
        budget += FILL * target / cross * dt;
        while (budget >= 1f && n < target) {
            budget -= 1f;
            // the upwind edge: an x edge with chance by how much of the flow crosses it
            float fluxX = Math.abs(gx) * h, fluxY = Math.abs(gy) * w;
            boolean xEdge = rng.next() * (fluxX + fluxY) < fluxX;
            if (xEdge) { x[n] = gx > 0 ? 0f : w; y[n] = (float) rng.next() * h; }
            else { y[n] = gy > 0 ? 0f : h; x[n] = (float) rng.next() * w; }
            spd[n] = snow ? (float) rng.range(0.7, 1.3) : 1f;
            phase[n] = (float) (rng.next() * 2 * Math.PI);
            size[n] = snow ? (float) rng.range(0.6, 1.0) : 1f;
            light[n] = snow && rng.next() < 0.4;
            velocity(n, fx, gx, gy, cfg);
            n++;
        }
    }

    /** Drop i's velocity: the fall toward (gx, gy) plus the wind, and for snow a slow sideways wobble. */
    private void velocity(int i, WeatherFx fx, float gx, float gy, GameConfig cfg) {
        if (snow) {
            float wob = (float) Math.sin(2 * Math.PI * time / 4f + phase[i]) * cfg.snowDrift * 0.5f;
            vx[i] = gx * cfg.snowFall * spd[i] + fx.windX * cfg.snowDrift - gy * wob;
            vy[i] = gy * cfg.snowFall * spd[i] + fx.windY * cfg.snowDrift + gx * wob;
        } else {
            vx[i] = gx * cfg.rainFall + fx.windX * cfg.rainSlant;
            vy[i] = gy * cfg.rainFall + fx.windY * cfg.rainSlant;
        }
    }

    private void remove(int i) {
        int l = --n;
        x[i] = x[l]; y[i] = y[l]; vx[i] = vx[l]; vy[i] = vy[l]; size[i] = size[l]; light[i] = light[l];
        spd[i] = spd[l]; phase[i] = phase[l];
    }

    /** The surface resized: keep the drops where they are on screen. */
    public void scale(float sx, float sy) {
        for (int i = 0; i < n; i++) { x[i] *= sx; y[i] *= sy; }
    }
}
