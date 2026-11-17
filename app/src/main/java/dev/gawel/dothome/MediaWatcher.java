package dev.gawel.dothome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.gawel.dothome.core.ArtMath;
import dev.gawel.dothome.core.MusicSeek;
import dev.gawel.dothome.core.NowPlaying;

/**
 * Reads the active media sessions (never the notifications themselves) and feeds the music widget.
 * Listeners are registered on connect and removed on disconnect/destroy; callbacks run on the main looper.
 */
public class MediaWatcher extends NotificationListenerService {
    private static final String TAG = "DotHome";
    private static volatile MediaController chosen;

    private MediaSessionManager sessions;
    private Handler main;
    private final Map<MediaController, MediaController.Callback> callbacks = new IdentityHashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener listener = this::track;
    private boolean registered;

    /** The controller for the session the widget currently shows, or null. */
    static MediaController current() { return chosen; }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        main = new Handler(Looper.getMainLooper());
        try {
            sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaWatcher.class);
            sessions.addOnActiveSessionsChangedListener(listener, me, main);
            registered = true;
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "media session access not granted: " + e);
            release();
        } catch (RuntimeException e) {
            Log.w(TAG, "media watcher start failed: " + e);
            release();
        }
    }

    @Override
    public void onListenerDisconnected() {
        release();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        try {
            if (registered && sessions != null) sessions.removeOnActiveSessionsChangedListener(listener);
        } catch (RuntimeException e) {
            Log.w(TAG, "removing session listener failed: " + e);
        }
        registered = false;
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try {
                e.getKey().unregisterCallback(e.getValue());
            } catch (RuntimeException ex) {
                Log.w(TAG, "unregister callback failed: " + ex);
            }
        }
        callbacks.clear();
        chosen = null;
        try {
            MusicWidget.update(getApplicationContext(), null);
        } catch (RuntimeException e) {
            Log.w(TAG, "music widget clear failed: " + e);
        }
    }

    private void track(List<MediaController> list) {
        try {
            List<MediaController> now = new ArrayList<>();
            // Sessions come only from this user (the API cannot return another profile's), minus work apps.
            if (list != null) for (MediaController c : list) if (NowPlaying.packageAllowed(c.getPackageName())) now.add(c);
            // drop callbacks for sessions that went away
            List<MediaController> gone = new ArrayList<>();
            for (MediaController old : callbacks.keySet()) {
                boolean still = false;
                for (MediaController c : now) if (c.getSessionToken().equals(old.getSessionToken())) still = true;
                if (!still) gone.add(old);
            }
            for (MediaController g : gone) {
                try { g.unregisterCallback(callbacks.remove(g)); } catch (RuntimeException ignored) { }
            }
            for (MediaController c : now) {
                boolean have = false;
                for (MediaController old : callbacks.keySet()) if (old.getSessionToken().equals(c.getSessionToken())) have = true;
                if (have) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState s) { refresh(); }
                    @Override public void onMetadataChanged(MediaMetadata m) { refresh(); }
                    @Override public void onSessionDestroyed() { drop(c); refresh(); }
                };
                c.registerCallback(cb, main);
                callbacks.put(c, cb);
            }
            refresh();
        } catch (RuntimeException e) {
            Log.w(TAG, "tracking sessions failed: " + e);
        }
    }

    private void drop(MediaController c) {
        MediaController.Callback cb = callbacks.remove(c);
        if (cb != null) {
            try { c.unregisterCallback(cb); } catch (RuntimeException ignored) { }
        }
    }

    private void refresh() {
        try {
            List<NowPlaying> items = new ArrayList<>();
            Map<String, MediaController> byKey = new java.util.HashMap<>();
            for (MediaController c : callbacks.keySet()) {
                if (!NowPlaying.packageAllowed(c.getPackageName())) continue;
                NowPlaying n;
                try {
                    n = build(c);
                } catch (RuntimeException e) {
                    Log.w(TAG, "skipping misbehaving controller: " + e);
                    continue;
                }
                if (n == null) continue;
                items.add(n);
                byKey.put(n.sessionKey, c);
            }
            NowPlaying pick = NowPlaying.choose(NowPlaying.eligible(items));
            chosen = pick == null ? null : byKey.get(pick.sessionKey);
            if (pick != null && chosen != null) ensureArt(chosen, pick);
            if (NowPlaying.sameDisplayed(MusicWidget.last(), pick)) {
                MusicWidget.remember(pick); // fresh position anchor, no redundant widget push
            } else {
                MusicWidget.update(getApplicationContext(), pick);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "media refresh failed: " + e);
        }
    }

    private static final java.util.concurrent.ExecutorService ART_POOL = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Keys whose art was already looked up (found or not), so frequent refreshes never redo the work. */
    private static final java.util.Set<String> ART_TRIED = new java.util.HashSet<>();

    /**
     * Sets pick.artKey and makes sure that track's art is cached. Only ever called for the chosen, eligible session
     * (never a work-app one). Metadata bitmaps are used at once; content:// art URIs load on a worker thread and push
     * a widget update when they arrive.
     */
    private void ensureArt(MediaController c, NowPlaying pick) {
        try {
            if (!NowPlaying.packageAllowed(c.getPackageName())) return;
            MediaMetadata md = c.getMetadata();
            if (md == null) return;
            String key = ArtMath.key(c.getPackageName(), pick.title, pick.artist, md.getString(MediaMetadata.METADATA_KEY_ALBUM));
            pick.artKey = key;
            synchronized (ART_TRIED) {
                if (ArtCache.get(key) != null || ART_TRIED.contains(key)) return;
                if (ART_TRIED.size() > 64) ART_TRIED.clear();
                ART_TRIED.add(key);
            }
            int[] px = null;
            String[] bmpKeys = {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON};
            for (String k : bmpKeys) {
                px = ArtCache.fromBitmap(md.getBitmap(k));
                if (px != null) break;
            }
            if (px != null) {
                ArtCache.put(key, px);
                return;
            }
            String[] uriKeys = {MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
            String uri = null;
            for (String k : uriKeys) {
                String u = md.getString(k);
                if (u != null && (u.startsWith("content://") || u.startsWith("android.resource://"))) { uri = u; break; }
            }
            if (uri == null) return;
            final String fUri = uri;
            final android.content.Context app = getApplicationContext();
            ART_POOL.execute(() -> {
                int[] loaded = ArtCache.fromUri(app, fUri);
                if (loaded == null) return;
                ArtCache.put(key, loaded);
                main.post(() -> {
                    NowPlaying last = MusicWidget.last();
                    if (last != null && key.equals(last.artKey)) MusicWidget.update(app, last);
                });
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "album art failed: " + e);
        }
    }

    private static NowPlaying build(MediaController c) {
        MediaMetadata md = c.getMetadata();
        PlaybackState ps = c.getPlaybackState();
        if (md == null && ps == null) return null;
        NowPlaying n = new NowPlaying();
        n.sessionKey = c.getPackageName() + "#" + c.getSessionToken().hashCode();
        n.pkg = c.getPackageName();
        try {
            n.sessionActivity = c.getSessionActivity();
        } catch (RuntimeException e) {
            Log.w(TAG, "session activity unavailable: " + e);
        }
        if (md != null) {
            n.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            n.artist = artist;
            n.durationMs = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        if (ps != null) {
            n.playing = ps.getState() == PlaybackState.STATE_PLAYING;
            int st = ps.getState();
            n.stopped = st == PlaybackState.STATE_STOPPED || st == PlaybackState.STATE_NONE
                || st == PlaybackState.STATE_ERROR;
            n.positionMs = Math.max(0, ps.getPosition());
            n.speed = ps.getPlaybackSpeed();
            n.canSeek = MusicSeek.canSeek(ps.getActions(), n.durationMs);
            n.positionAtElapsedMs = ps.getLastPositionUpdateTime();
            long updated = ps.getLastPositionUpdateTime();
            n.changedAtMs = updated > 0
                ? System.currentTimeMillis() - (SystemClock.elapsedRealtime() - updated)
                : System.currentTimeMillis();
        } else {
            n.changedAtMs = System.currentTimeMillis();
        }
        // No title or artist is nothing playing, even when the state says playing: idle players keep sessions with
        // empty metadata, and those used to show an empty card with the transport buttons.
        if (!n.hasMetadata()) return null;
        Log.d(TAG, "now playing from " + c.getPackageName() + ", playing=" + n.playing);
        return n;
    }
}
