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

/** Coarse location with cache and Chicago fallback. Blocking; call off the main thread. */
final class LocationSource {
    private static final String TAG = "DotHome";
    private static final String KEY_LAT = "loc.lat", KEY_LON = "loc.lon";
    private static final long FIX_TIMEOUT_MS = 10_000;

    private LocationSource() {}

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
                if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return lastKnown(lm);
                AtomicReference<Location> ref = new AtomicReference<>();
                CountDownLatch latch = new CountDownLatch(1);
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, Runnable::run, loc -> {
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
