package dev.gawel.dothome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** Periodic and one-shot weather refresh. The work runs on its own thread, never the main thread. */
public class WeatherJob extends JobService {
    static final int ID_PERIODIC = 801;
    static final int ID_ONESHOT = 802;
    private static final long PERIOD_MS = 30L * 60 * 1000;

    private volatile Thread worker;

    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(ID_PERIODIC, new ComponentName(ctx, WeatherJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build());
        } catch (RuntimeException e) {
            Log.w("DotHome", "schedule failed: " + e);
        }
    }

    static void refreshSoon(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(ID_ONESHOT, new ComponentName(ctx, WeatherJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
        } catch (RuntimeException e) {
            Log.w("DotHome", "refreshSoon failed: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                WeatherRepo.refresh(app);
                WidgetUpdater.updateAll(app);
            } catch (RuntimeException e) {
                Log.w("DotHome", "weather job error: " + e);
            } finally {
                jobFinished(params, false);
            }
        }, "weather-job");
        worker = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return false;
    }
}
