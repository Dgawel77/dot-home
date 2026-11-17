package dev.gawel.dothome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Every 6 h, with any network: refreshes the live feeds even while the screen stays off (the minute tick only runs
 * while it is on). The work runs on {@link LiveFeeds}' executor, never the main thread.
 */
public class FeedJob extends JobService {
    static final int ID = 803;
    private static final long PERIOD_MS = 6L * 3_600_000;

    private volatile boolean stopped;

    /** Idempotent: keeps an existing periodic job (and its phase) when the interval already matches. */
    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo existing = js.getPendingJob(ID);
            if (existing != null && existing.isPeriodic() && existing.getIntervalMillis() == PERIOD_MS) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(ctx, FeedJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build());
        } catch (RuntimeException e) {
            Log.w("DotHome", "feed schedule failed: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        LiveFeeds.run(getApplicationContext(), () -> {
            if (!stopped) jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true; // the fetch is bounded by its 10 s timeouts; it finishes on the executor
        return true;
    }
}
