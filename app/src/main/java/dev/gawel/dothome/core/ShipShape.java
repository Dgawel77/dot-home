package dev.gawel.dothome.core;

/**
 * The approved Ship v2 hull, "forked-5" (design/mockups/ShipV2.dc.html): a compact hull that splits into two short
 * tail booms, an engine in each, with an angular wingtip fin on each boom's outer side and the cockpit forward.
 * Ship-local mockup px: the nose along +x, +y to starboard. The game scales positions by GameConfig.k; dot
 * diameters are mockup px on the 4 px lattice (3.4 is heavy weight).
 */
public final class ShipShape {
    private ShipShape() {}

    /** The nose tip. */
    public static final float NOSE_X = 19f, NOSE_Y = 0f;
    /**
     * Four guns (design/mockups ForkedGuns board, variants C and D), in firing order, which crosses the ship:
     * 0 the under-nose twin's port barrel, 1 the starboard wingtip gun, 2 the under-nose starboard barrel, 3 the
     * port wingtip gun. Each is a straight barrel of dots from its breech (the first dot's centre) along its axis.
     */
    public static final int GUNS = 4;
    public static final int NOSE_PORT = 0, WING_STARBOARD = 1, NOSE_STARBOARD = 2, WING_PORT = 3;
    /** The under-nose twin (D): two parallel barrels close either side of the centreline, slung under the hull. */
    public static final float NOSE_GUN_X = 14.2f, NOSE_GUN_Y = 1.25f;
    /** The wingtip guns (C): on the angular wingtip fins, toed in 1.5 degrees. */
    public static final float WING_GUN_X = -10.4f, WING_GUN_Y = 12.5f, WING_GUN_TOE = (float) Math.toRadians(1.5);
    private static final int NOSE_GUN_DOTS = 5, WING_GUN_DOTS = 3;
    private static final float NOSE_GUN_GAP = 2.1f, WING_GUN_GAP = 1.9f, NOSE_GUN_DOT = 1.7f, WING_GUN_DOT = 1.7f;
    /** A barrel slides back this share of its length on its shot. */
    public static final float RECOIL_SHARE = 0.4f;
    /** Recoil: back over RECOIL_BACK s, then forward over RECOIL_RETURN s (cubic ease-out). */
    public static final float RECOIL_BACK = 0.06f, RECOIL_RETURN = 0.22f;
    /** The hull kick (render only): back over RECOIL_BACK s, a KICK_SETTLE s cubic settle; summed shots cap at KICK_MAX. */
    public static final float KICK_SETTLE = 0.3f, KICK_MAX = 1.6f;
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

    /**
     * The two nose retros (they push the ship backwards), retro 0 to port (-y), 1 to starboard, just inside the
     * nose's outline behind the RCS nose ports; their plumes blow forward, splayed RETRO_SPLAY outward to clear it.
     */
    public static final int RETROS = 2;
    public static final float RETRO_X = 10f, RETRO_Y = 2.4f, RETRO_SPLAY = 0.35f;

    private static final float HEAVY = 3.4f;
    /** Dot levels as the prototype's alpha on L100: 1 is L100, 0.75 is L65. */
    public static final float LEVEL_L100 = 1f, LEVEL_L65 = 0.75f;

    private static boolean nose(int gun) { return gun == NOSE_PORT || gun == NOSE_STARBOARD; }

    /** Gun i's side: -1 port, +1 starboard. */
    public static int gunSide(int gun) { return gun == NOSE_PORT || gun == WING_PORT ? -1 : 1; }

    /** Gun i's breech: the centre of its rearmost dot. */
    public static float breechX(int gun) { return nose(gun) ? NOSE_GUN_X : WING_GUN_X; }

    public static float breechY(int gun) { return gunSide(gun) * (nose(gun) ? NOSE_GUN_Y : WING_GUN_Y); }

    /** Gun i's axis, radians from the heading: 0 for the nose twin, toed in toward the centreline for the wingtips. */
    public static float gunAngle(int gun) { return nose(gun) ? 0f : -gunSide(gun) * WING_GUN_TOE; }

    /** Dots along gun i's barrel, their spacing and diameter. */
    public static int gunDots(int gun) { return nose(gun) ? NOSE_GUN_DOTS : WING_GUN_DOTS; }

    public static float gunGap(int gun) { return nose(gun) ? NOSE_GUN_GAP : WING_GUN_GAP; }

    public static float gunDot(int gun) { return nose(gun) ? NOSE_GUN_DOT : WING_GUN_DOT; }

    /** Gun i's barrel length, end to end. */
    public static float gunLength(int gun) { return (gunDots(gun) - 1) * gunGap(gun) + gunDot(gun); }

    /** From the breech to the muzzle along the axis (the front dot's far edge). */
    public static float muzzleReach(int gun) { return (gunDots(gun) - 1) * gunGap(gun) + gunDot(gun) / 2f; }

    /** Gun i's muzzle with the barrel at rest. */
    public static float muzzleX(int gun) { return breechX(gun) + (float) Math.cos(gunAngle(gun)) * muzzleReach(gun); }

    public static float muzzleY(int gun) { return breechY(gun) + (float) Math.sin(gunAngle(gun)) * muzzleReach(gun); }

    /** True for the nose twin: drawn under the hull, whose dots draw over it. */
    public static boolean gunUnder(int gun) { return nose(gun); }

    /** How far gun i's barrel slides back on a shot. */
    public static float recoilStroke(int gun) { return RECOIL_SHARE * gunLength(gun); }

    /** The barrel's recoil, 0..1 of its stroke, age seconds after its shot: back fast (ease-out), forward with a cubic ease-out. */
    public static float recoil(float age) {
        if (!(age >= 0f) || age >= RECOIL_BACK + RECOIL_RETURN) return 0f;
        if (age < RECOIL_BACK) {
            float u = 1f - age / RECOIL_BACK;
            return 1f - u * u;
        }
        float u = 1f - (age - RECOIL_BACK) / RECOIL_RETURN;
        return u * u * u;
    }

    /** One shot's hull kick, ship units back, age seconds after it: back over RECOIL_BACK, a cubic settle over KICK_SETTLE. */
    public static float kick(float age) {
        if (!(age >= 0f) || age >= RECOIL_BACK + KICK_SETTLE) return 0f;
        if (age < RECOIL_BACK) {
            float u = 1f - age / RECOIL_BACK;
            return 1f - u * u;
        }
        float u = 1f - (age - RECOIL_BACK) / KICK_SETTLE;
        return u * u * u;
    }

    /** Nozzle i's centre y. */
    public static float nozzleY(int i) { return i == 0 ? -NOZZLE_Y : NOZZLE_Y; }

    /** Retro i's centre y: -1 port, +1 starboard side. */
    public static float retroY(int i) { return i == 0 ? -RETRO_Y : RETRO_Y; }

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
