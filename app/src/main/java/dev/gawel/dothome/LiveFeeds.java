package dev.gawel.dothome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live widget feeds (ESPN for Dot Bears): one background executor does every fetch and render, never the main
 * thread. {@link #onMinute} runs on each TIME_TICK while the screen is on, FeedJob every 6 h. A fetch only happens
 * when it is due (the repo decides), and a failed fetch keeps the cache.
 */
final class LiveFeeds {
    private static final String TAG = "DotHome";
    private static final int TIMEOUT_MS = 10_000;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dothome-feeds");
        t.setDaemon(true);
        return t;
    });
    /** A minute's work is queued or running: the next tick skips rather than piling up behind a slow fetch. */
    private static final AtomicBoolean MINUTE_PENDING = new AtomicBoolean(false);

    private LiveFeeds() {}

    /** Main thread, once a minute while the screen is on. No network unless a feed widget exists. */
    static void onMinute(Context ctx) {
        if (!BearsWidget.exists(ctx)) return;
        if (!MINUTE_PENDING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        submit(() -> {
            try {
                work(app, false);
            } finally {
                MINUTE_PENDING.set(false);
            }
        });
    }

    /** Refresh whatever is due, then redraw every feed widget (a widget was added or resized). Any thread. */
    static void kick(Context ctx) {
        final Context app = ctx.getApplicationContext();
        submit(() -> work(app, true));
    }

    /** As {@link #kick}, then runs done (on the executor) when finished; done always runs. */
    static void run(Context ctx, Runnable done) {
        final Context app = ctx.getApplicationContext();
        if (!submit(() -> {
            try {
                work(app, true);
            } finally {
                done.run();
            }
        })) done.run();
    }

    private static boolean submit(Runnable r) {
        try {
            EXEC.execute(r);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "feeds busy: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** On the executor. force redraws even when the shown content is unchanged. Never throws. */
    private static void work(Context app, boolean force) {
        try {
            if (BearsWidget.exists(app)) {
                BearsRepo.refresh(app, System.currentTimeMillis());
                BearsWidget.push(app, force);
            }
        } catch (Throwable e) { // an Error here would otherwise kill the shared wallpaper process
            Log.w(TAG, "feeds error: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Blocking GET with 10 s timeouts: the body, or null on any failure. Logs only the feed name and the HTTP status
     * (or the exception class), never the URL, which may carry a key.
     */
    static String get(String url, String feed) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                Log.w(TAG, feed + " HTTP " + code);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, feed + " failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
