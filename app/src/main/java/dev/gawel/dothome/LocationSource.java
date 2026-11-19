package dev.gawel.dothome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.gawel.dothome.core.LocationChooser;
import dev.gawel.dothome.core.Place;

/**
 * Location with cache and Chicago fallback. Blocking; call off the main thread. The feeds (weather, radar) get the
 * place rounded to 0.01 degrees. With precise location granted, the unrounded fix is also kept ("loc.fine.*") for
 * one on-phone use only, picking the nearest Blue Line station; it is never sent anywhere and is dropped when
 * precise location is revoked.
 */
final class LocationSource {
    private static final String TAG = "DotHome";
    private static final String KEY_LAT = "loc.lat", KEY_LON = "loc.lon";
    private static final String KEY_FINE_LAT = "loc.fine.lat", KEY_FINE_LON = "loc.fine.lon";
    private static final long FIX_TIMEOUT_MS = 10_000;

    private LocationSource() {}

    /** The last saved place, with no location fix or network work (the weather's own cache); null before the first fix. */
    static Place cached(Context ctx) {
        PrefsStore prefs = new PrefsStore(ctx);
        Double la = parse(prefs.get(KEY_LAT, null)), lo = parse(prefs.get(KEY_LON, null));
        return la == null || lo == null ? null : new Place(la, lo, Place.Source.CACHED);
    }

    /** For the nearest station: the last precise fix while precise location is granted, else {@link #cached}. */
    static Place cachedPrecise(Context ctx) {
        PrefsStore prefs = new PrefsStore(ctx);
        Double la = null, lo = null;
        if (precise(ctx)) {
            la = parse(prefs.get(KEY_FINE_LAT, null));
            lo = parse(prefs.get(KEY_FINE_LON, null));
        } else if (prefs.get(KEY_FINE_LAT, null) != null) {
            prefs.remove(KEY_FINE_LAT);
            prefs.remove(KEY_FINE_LON);
        }
        return LocationChooser.forStation(la, lo, cached(ctx));
    }

    static boolean precise(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static Place current(Context ctx) {
        PrefsStore prefs = new PrefsStore(ctx);
        Double cLat = parse(prefs.get(KEY_LAT, null)), cLon = parse(prefs.get(KEY_LON, null));
        Location fix = null;
        try {
            fix = fix(ctx);
        } catch (RuntimeException e) {
            Log.w(TAG, "location failed: " + e);
        }
        Place p = LocationChooser.choose(fix == null ? null : fix.getLatitude(),
            fix == null ? null : fix.getLongitude(), cLat, cLon);
        if (p.source == Place.Source.FRESH) {
            prefs.put(KEY_LAT, String.valueOf(p.lat));
            prefs.put(KEY_LON, String.valueOf(p.lon));
            if (precise(ctx)) { // the unrounded fix, for the nearest station only (never logged)
                prefs.put(KEY_FINE_LAT, String.valueOf(fix.getLatitude()));
                prefs.put(KEY_FINE_LON, String.valueOf(fix.getLongitude()));
            }
        }
        Log.i(TAG, "place " + p.source + (p.source == Place.Source.DEFAULT ? " Chicago" : " " + p.lat + "," + p.lon));
        return p;
    }

    @SuppressWarnings("deprecation") // blocking Geocoder overload; we are on a worker thread
    static String placeName(Context ctx, Place p) {
        if (p.source == Place.Source.DEFAULT) return "Chicago";
        try {
            if (!Geocoder.isPresent()) return "";
            List<Address> list = new Geocoder(ctx, Locale.getDefault()).getFromLocation(p.lat, p.lon, 1);
            if (list == null || list.isEmpty()) return "";
            Address a = list.get(0);
            String name = a.getLocality();
            if (name == null) name = a.getSubAdminArea();
            return name == null ? "" : name;
        } catch (Exception e) {
            Log.w(TAG, "geocoder failed: " + e);
            return "";
        }
    }

    private static Location fix(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // precise: the fused provider (Wi-Fi, cell and GPS as available); approximate: the network provider
                String provider = Build.VERSION.SDK_INT >= 31 && precise(ctx)
                    && lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : LocationManager.NETWORK_PROVIDER;
                if (!lm.isProviderEnabled(provider)) return lastKnown(lm);
                AtomicReference<Location> ref = new AtomicReference<>();
                CountDownLatch latch = new CountDownLatch(1);
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(provider, cancel, Runnable::run, loc -> {
                    ref.set(loc);
                    latch.countDown();
                });
                try {
                    if (!latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) cancel.cancel();
                } catch (InterruptedException e) {
                    cancel.cancel();
                    Thread.currentThread().interrupt();
                }
                Location got = ref.get();
                return got != null ? got : lastKnown(lm);
            } catch (SecurityException e) {
                return null;
            }
        }
        return lastKnown(lm);
    }

    private static Location lastKnown(LocationManager lm) {
        try {
            return lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Double parse(String s) {
        if (s == null) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
