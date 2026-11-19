package dev.gawel.dothome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the Hue Bridge on the home network over mDNS (_hue._tcp) with NsdManager: the first bridge found, or the one
 * whose TXT "bridgeid" is wantId. Resolves one service at a time (older NsdManagers allow one resolve at once).
 * The callback runs once, on the main thread: onFound with an IPv4 host and the bridge id (lower case, or null when
 * the TXT record is missing), or onNone after timeoutMs.
 */
final class HueDiscovery {
    private static final String TAG = "DotHome";
    private static final String TYPE = "_hue._tcp";

    interface Callback {
        void onFound(String host, String bridgeId);

        void onNone();
    }

    private final NsdManager nsd;
    private final String wantId;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean done, resolving, discovering;

    private HueDiscovery(NsdManager nsd, String wantId, Callback cb) {
        this.nsd = nsd;
        this.wantId = wantId == null ? null : wantId.toLowerCase(Locale.ROOT);
        this.cb = cb;
    }

    /** Main thread. */
    static void find(Context ctx, String wantId, long timeoutMs, Callback cb) {
        NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            cb.onNone();
            return;
        }
        new HueDiscovery(nsd, wantId, cb).start(timeoutMs);
    }

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override
        public void onDiscoveryStarted(String type) {
            main.post(() -> {
                discovering = true;
                if (done) stop(); // timed out before discovery started: stop it now
            });
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            main.post(() -> {
                if (done) return;
                queue.add(info);
                next();
            });
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {}

        @Override
        public void onDiscoveryStopped(String type) {}

        @Override
        public void onStartDiscoveryFailed(String type, int error) {
            Log.w(TAG, "hue discovery failed: " + error);
            main.post(() -> finish(null, null));
        }

        @Override
        public void onStopDiscoveryFailed(String type, int error) {}
    };

    private void start(long timeoutMs) {
        main.postDelayed(() -> finish(null, null), timeoutMs);
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(TAG, "hue discovery failed: " + e.getClass().getSimpleName());
            finish(null, null);
        }
    }

    @SuppressWarnings("deprecation") // resolveService: one-shot resolve, still supported; the callback API needs 34
    private void next() {
        if (done || resolving || queue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo info = queue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo s, int error) {
                    main.post(() -> {
                        resolving = false;
                        next();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo s) {
                    String host = ipv4(s), id = bridgeId(s);
                    main.post(() -> {
                        resolving = false;
                        if (host != null && (wantId == null || wantId.equals(id))) finish(host, id);
                        else next();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
            Log.w(TAG, "hue resolve failed: " + e.getClass().getSimpleName());
        }
    }

    private void finish(String host, String id) {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        if (discovering) stop();
        if (host != null) cb.onFound(host, id);
        else cb.onNone();
    }

    private void stop() {
        discovering = false;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException ignored) {
            // already stopped
        }
    }

    @SuppressWarnings("deprecation") // getHost: the only address before API 34
    private static String ipv4(NsdServiceInfo s) {
        if (Build.VERSION.SDK_INT >= 34) {
            List<InetAddress> all = s.getHostAddresses();
            for (InetAddress a : all) if (a instanceof Inet4Address) return a.getHostAddress();
            return null;
        }
        InetAddress a = s.getHost();
        return a instanceof Inet4Address ? a.getHostAddress() : null;
    }

    private static String bridgeId(NsdServiceInfo s) {
        Map<String, byte[]> attrs = s.getAttributes();
        byte[] v = attrs == null ? null : attrs.get("bridgeid");
        return v == null ? null : new String(v, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
    }
}
