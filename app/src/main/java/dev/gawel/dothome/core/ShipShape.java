package dev.gawel.dothome.core;

/**
 * The approved Ship v2 hull, "forked-5" (design/mockups/ShipV2.dc.html): a compact hull that splits into two short
 * tail booms, an engine in each, with an angular wingtip fin on each boom's outer side and the cockpit forward.
 * Ship-local mockup px: the nose along +x, +y to starboard. The game scales positions by GameConfig.k; dot
 * diameters are mockup px on the 4 px lattice (3.4 is heavy weight).
 */
public final class ShipShape {
    private ShipShape() {}

    /** The nose tip: the turret's muzzle for targets ahead. */
    public static final float NOSE_X = 19f, NOSE_Y = 0f;
    /** Hit radius against rocks. */
    public static final float RADIUS = 11f;

    /** Main engines: nozzle 0 is to port (-y), nozzle 1 to starboard (+y). */
    public static final int NOZZLES = 2;
    public static final float NOZZLE_X = -12f, NOZZLE_Y = 5.5f;
    /** Plume dots leave from a band this far either side of the nozzle centre. */
    public static final float NOZZLE_SPREAD = 0.8f;
    /** Each nozzle is drawn as a flat bar of dots of this diameter across +/- NOZZLE_HALF_WIDTH. */
    public static final float NOZZLE_DOT = 1.6f, NOZZLE_HALF_WIDTH = 1f;

    /** RCS ports, one pair at the nose and one at the wingtips, mirrored to both sides. */
    public static final int RCS_NOSE = 0, RCS_TAIL = 1;
    public static final float RCS_NOSE_X = 12f, RCS_NOSE_Y = 1.9f, RCS_TAIL_X = -11.5f, RCS_TAIL_Y = 12.5f;

    private static final float HEAVY = 3.4f;
    /** Dot levels as the prototype's alpha on L100: 1 is L100, 0.75 is L65. */
    public static final float LEVEL_L100 = 1f, LEVEL_L65 = 0.75f;

    /** Nozzle i's centre y. */
    public static float nozzleY(int i) { return i == 0 ? -NOZZLE_Y : NOZZLE_Y; }

    /** An RCS port's x. */
    public static float rcsX(int port) { return port == RCS_NOSE ? RCS_NOSE_X : RCS_TAIL_X; }

    /** An RCS port's y on one side (+1 starboard, -1 port). */
    public static float rcsY(int port, int side) { return side * (port == RCS_NOSE ? RCS_NOSE_Y : RCS_TAIL_Y); }

    /**
     * The hull as x, y, diameter, level quads: the inner outline of the booms, then the outer outline (starboard
     * boom, fin, nose, port fin, boom), dots every ~4 px, then two cockpit dots in L65. A vertex with its own
     * diameter tapers the dots along both edges, so the nose and fin tips end in a point, not a round dot.
     */
    public static float[] hullDots() {
        Dots out = new Dots();
        poly(out, new float[][] {{-13, 3.5f}, {-5, 3.5f}, {-2, 0}, {-5, -3.5f}, {-13, -3.5f}}, HEAVY, LEVEL_L100);
        poly(out, sym(new float[][] {{-13, 7.5f}, {-11.5f, 12.5f, 2.4f}, {-7, 7.5f}, {-2, 7.5f}, {8, 3}, {19, 0, 1.4f}}),
            HEAVY, LEVEL_L100);
        out.add(7, 0, 2.2f, LEVEL_L65);
        out.add(3.5f, 0, 2.2f, LEVEL_L65);
        return out.toArray();
    }

    /**
     * The nozzle marks as x, y, diameter, level quads: for each nozzle in order a bar of three dots across
     * +/- NOZZLE_HALF_WIDTH (level 0.75 is the bar's size scale; the renderer brightens it with thrust).
     */
    public static float[] nozzleMarks() {
        Dots out = new Dots();
        float w = NOZZLE_HALF_WIDTH;
        int n = Math.max(2, (int) Math.ceil(2 * w / 1.2f));
        for (int i = 0; i < NOZZLES; i++) {
            for (int j = 0; j <= n; j++) out.add(NOZZLE_X, nozzleY(i) - w + 2 * w * j / n, NOZZLE_DOT, 0.75f);
        }
        return out.toArray();
    }

    /** Dots per nozzle in nozzleMarks(). */
    public static int marksPerNozzle() { return nozzleMarks().length / 4 / NOZZLES; }

    /**
     * The turret muzzle (ship-local mockup px) for a target rel radians off the nose (-PI..PI), into out[0..1]:
     * the nose tip for a target ahead; for one behind (more than 90 degrees off) the point facing it on a circle of
     * 0.6 x the nose length, so the bolt leaves from the hull edge facing the tap, not back through the hull.
     */
    public static void muzzle(float rel, float[] out) {
        if (Math.abs(rel) > Math.PI / 2) {
            float rr = 0.6f * NOSE_X;
            out[0] = (float) Math.cos(rel) * rr;
            out[1] = (float) Math.sin(rel) * rr;
        } else {
            out[0] = NOSE_X;
            out[1] = NOSE_Y;
        }
    }

    /** A starboard half outline, tail to nose, mirrored into the full open outline: starboard side, nose, port side. */
    private static float[][] sym(float[][] v) {
        float[][] out = new float[2 * v.length - 1][];
        for (int i = 0; i < v.length; i++) out[i] = v[i];
        for (int i = 0; i < v.length - 1; i++) {
            float[] p = v[v.length - 2 - i].clone();
            p[1] = -p[1];
            out[v.length + i] = p;
        }
        return out;
    }

    /** Dots every ~4 px along an open outline; a vertex {x, y, d} sets the diameter there, blended along its edges. */
    private static void poly(Dots out, float[][] v, float d, float level) {
        for (int i = 0; i < v.length - 1; i++) {
            float[] p = v[i], q = v[i + 1];
            int n = Math.max(1, Math.round((float) Math.hypot(q[0] - p[0], q[1] - p[1]) / 4f));
            float dp = p.length > 2 ? p[2] : d, dq = q.length > 2 ? q[2] : d;
            for (int j = 0; j < n; j++) {
                float t = (float) j / n;
                out.add(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, dp + (dq - dp) * t, level);
            }
        }
        float[] p = v[v.length - 1];
        out.add(p[0], p[1], p.length > 2 ? p[2] : d, level);
    }

    private static final class Dots {
        float[] a = new float[256];
        int n;

        void add(float x, float y, float d, float level) {
            if (n + 4 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x;
            a[n++] = y;
            a[n++] = d;
            a[n++] = level;
        }

        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
