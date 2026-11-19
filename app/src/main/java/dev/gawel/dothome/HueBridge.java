package dev.gawel.dothome;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import dev.gawel.dothome.core.HueParser;

/**
 * The Hue Bridge's local HTTPS API. The bridge's certificate is self-signed, so it is pinned at pairing (SHA-256 of
 * the leaf, base64) and every later call trusts exactly that certificate and nothing else; at pairing it is trusted
 * on first use only when its CN is the bridge id that mDNS advertised. TLS checks are never switched off. Logs name
 * the call and the status or exception class only: never the host, the key or a body. Blocking: worker threads only.
 */
final class HueBridge {
    private static final String TAG = "DotHome";
    static final int CONNECT_MS = 3000, READ_MS = 5000;
    /** code: the HTTP status, or UNREACHABLE when the bridge could not be reached at all (connect/TLS/IO failure). */
    static final int UNREACHABLE = -1;

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code <= 299 && body != null;
        }
    }

    private final String host, pin;

    HueBridge(String host, String pinB64) {
        this.host = host;
        this.pin = pinB64;
    }

    Reply get(String path, String appKey, String what) {
        return call("GET", path, appKey, null, what);
    }

    Reply put(String path, String appKey, String body, String what) {
        return call("PUT", path, appKey, body, what);
    }

    private Reply call(String method, String path, String appKey, String body, String what) {
        try {
            final String want = pin;
            SSLSocketFactory f = factory(new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    throw new CertificateException("no client auth");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain == null || chain.length == 0 || !want.equals(fingerprint(chain[0]))) {
                        throw new CertificateException("bridge certificate does not match the pin");
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            });
            return exchange(method, path, appKey, body, f, what);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "hue " + what + " tls setup failed: " + e.getClass().getSimpleName());
            return new Reply(UNREACHABLE, null);
        }
    }

    /**
     * Pairs with the bridge at host: POST /api asking for an app key. Returns {appKey, pin} once the link button has
     * been pressed, {"LINK"} while it has not, or null on any other failure (including a certificate whose CN is not
     * bridgeId, compared ignoring case).
     */
    static String[] pair(String host, final String bridgeId) {
        final AtomicReference<X509Certificate> seen = new AtomicReference<>();
        try {
            SSLSocketFactory f = factory(new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    throw new CertificateException("no client auth");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
                    String cn = commonName(chain[0]);
                    if (cn == null || !cn.equalsIgnoreCase(bridgeId)) {
                        throw new CertificateException("certificate is not this bridge's");
                    }
                    seen.set(chain[0]);
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            });
            HueBridge b = new HueBridge(host, "");
            Reply r = b.exchange("POST", "/api", null,
                "{\"devicetype\":\"dothome#fold6\",\"generateclientkey\":true}", f, "pair");
            if (!r.ok() || seen.get() == null) return null;
            if (HueParser.linkButtonNeeded(r.body)) return new String[] {"LINK"};
            String key = HueParser.pairKey(r.body);
            return key == null ? null : new String[] {key, fingerprint(seen.get())};
        } catch (GeneralSecurityException e) { // includes the fingerprint's CertificateException
            Log.w(TAG, "hue pair failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static SSLSocketFactory factory(X509TrustManager tm) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx.getSocketFactory();
    }

    /**
     * One request over HTTPS with the given trust. The hostname check is the trust manager's: the certificate has
     * already been matched to the pin (or, at pairing, to the bridge id), and the bridge is addressed by IP.
     */
    private Reply exchange(String method, String path, String appKey, String body, SSLSocketFactory f, String what) {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL("https://" + host + path).openConnection();
            c.setSSLSocketFactory(f);
            c.setHostnameVerifier((h, session) -> true);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (appKey != null) c.setRequestProperty("hue-application-key", appKey);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                Log.w(TAG, "hue " + what + " HTTP " + code);
                return new Reply(code, null);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new Reply(code, out.toString("UTF-8"));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "hue " + what + " failed: " + e.getClass().getSimpleName());
            return new Reply(UNREACHABLE, null);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** SHA-256 of the certificate's DER, base64 without wrapping. */
    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e.getClass().getSimpleName());
        }
    }

    /** The subject's CN, lower-cased, or null. */
    private static String commonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3).toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
