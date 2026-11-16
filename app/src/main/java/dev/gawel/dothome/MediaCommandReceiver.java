package dev.gawel.dothome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.util.Log;

/** Handles the music widget's buttons by forwarding to the chosen session's transport controls. */
public class MediaCommandReceiver extends BroadcastReceiver {
    static final String PREV = "dev.gawel.dothome.PREV";
    static final String PLAY_PAUSE = "dev.gawel.dothome.PLAY_PAUSE";
    static final String NEXT = "dev.gawel.dothome.NEXT";

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
                default: break;
            }
        } catch (RuntimeException e) {
            Log.w("DotHome", "media command failed: " + e);
        }
    }
}
