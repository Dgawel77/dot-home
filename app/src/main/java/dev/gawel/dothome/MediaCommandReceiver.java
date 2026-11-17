package dev.gawel.dothome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.os.SystemClock;
import android.util.Log;

import dev.gawel.dothome.core.MusicSeek;
import dev.gawel.dothome.core.NowPlaying;

/** Handles the music widget's buttons by forwarding to the chosen session's transport controls. */
public class MediaCommandReceiver extends BroadcastReceiver {
    static final String PREV = "dev.gawel.dothome.PREV";
    static final String PLAY_PAUSE = "dev.gawel.dothome.PLAY_PAUSE";
    static final String NEXT = "dev.gawel.dothome.NEXT";
    static final String SEEK = "dev.gawel.dothome.SEEK";
    static final String EXTRA_FRACTION = "fraction";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            MediaController c = MediaWatcher.current();
            if (c == null || intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case PREV: c.getTransportControls().skipToPrevious(); break;
                case NEXT: c.getTransportControls().skipToNext(); break;
                case PLAY_PAUSE:
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
                    else c.getTransportControls().play();
                    break;
                case SEEK: seek(context, c, intent.getFloatExtra(EXTRA_FRACTION, -1f)); break;
                default: break;
            }
        } catch (RuntimeException e) {
            Log.w("DotHome", "media command failed: " + e);
        }
    }

    /** Seeks the chosen (MediaWatcher-eligible) session, then redraws the widget at the new position. */
    private static void seek(Context context, MediaController c, float fraction) {
        if (fraction < 0f || fraction > 1f) return;
        MediaMetadata md = c.getMetadata();
        PlaybackState ps = c.getPlaybackState();
        if (md == null || ps == null) return;
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (!MusicSeek.canSeek(ps.getActions(), duration)) return;
        long pos = MusicSeek.positionFor(fraction, duration);
        c.getTransportControls().seekTo(pos);
        NowPlaying last = MusicWidget.last();
        if (last != null) MusicWidget.update(context, last.withPosition(pos, SystemClock.elapsedRealtime()));
    }
}
