package dev.gawel.dothome.core;

/** The 33 CTA Blue Line stations (City of Chicago "L" stops 8pix-ypme), north to south. */
public final class BlueLineStations {
    private BlueLineStations() {}

    public static final int[] IDS = {
        40230, 40820, 40750, 40890, 41280, 41330, 40550, 41240,
        40060, 41020, 40570, 40670, 40590, 40320, 41410, 40490,
        40380, 40370, 40790, 40070, 40470, 40810, 41340, 40430,
        40220, 40350, 40250, 40390, 40920, 40980, 40180, 40970,
        40010
    };
    public static final String[] NAMES = {
        "Cumberland",
        "Rosemont",
        "Harlem",
        "O'Hare",
        "Jefferson Park",
        "Montrose",
        "Irving Park",
        "Addison",
        "Belmont",
        "Logan Square",
        "California",
        "Western",
        "Damen",
        "Division",
        "Chicago",
        "Grand",
        "Clark/Lake",
        "Washington",
        "Monroe",
        "Jackson",
        "Racine",
        "Illinois Medical District",
        "LaSalle",
        "Clinton",
        "Western",
        "UIC-Halsted",
        "Kedzie-Homan",
        "Forest Park",
        "Pulaski",
        "Harlem",
        "Oak Park",
        "Cicero",
        "Austin"
    };
    public static final double[] LAT = {
        41.984246, 41.983507, 41.98227, 41.97766526,
        41.970634, 41.961539, 41.952925, 41.94738,
        41.938132, 41.929728, 41.921939, 41.916157,
        41.909744, 41.903355, 41.896075, 41.891189,
        41.885737, 41.883164, 41.880703, 41.878183,
        41.87592, 41.875706, 41.875568, 41.875539,
        41.875478, 41.875474, 41.874341, 41.874257,
        41.873797, 41.87349, 41.872108, 41.871574,
        41.870851
    };
    public static final double[] LON = {
        -87.838028, -87.859388, -87.8089, -87.90422307,
        -87.760892, -87.743574, -87.729229, -87.71906,
        -87.712359, -87.708541, -87.69689, -87.687364,
        -87.677437, -87.666496, -87.655214, -87.647578,
        -87.630886, -87.62944, -87.629378, -87.629296,
        -87.659458, -87.673932, -87.631722, -87.640984,
        -87.688436, -87.649707, -87.70604, -87.817318,
        -87.725663, -87.806961, -87.791602, -87.745154,
        -87.776812
    };

    /** Nearest station id within maxKm (equirectangular distance), else -1. */
    public static int nearest(double lat, double lon, double maxKm) {
        int best = -1;
        double bestKm = maxKm;
        double kmPerDegLat = 111.195;
        for (int i = 0; i < IDS.length; i++) {
            double dy = (LAT[i] - lat) * kmPerDegLat;
            double dx = (LON[i] - lon) * kmPerDegLat * Math.cos(Math.toRadians((LAT[i] + lat) / 2));
            double km = Math.sqrt(dx * dx + dy * dy);
            if (km <= bestKm) { bestKm = km; best = IDS[i]; }
        }
        return best;
    }

    public static int index(int mapid) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i] == mapid) return i;
        return -1;
    }

    public static String label(int mapid) {
        switch (mapid) {
            case 40670: return "WESTERN (O'HARE BR)";
            case 40220: return "WESTERN (FOREST PK BR)";
            case 40750: return "HARLEM (O'HARE BR)";
            case 40980: return "HARLEM (FOREST PK BR)";
            default: break;
        }
        int i = index(mapid);
        return i < 0 ? "" : NAMES[i].toUpperCase(java.util.Locale.ROOT);
    }
}
