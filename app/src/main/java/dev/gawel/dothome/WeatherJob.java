package dev.gawel.dothome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodic and one-shot weather refresh. The work runs on its own thread, never the main thread. */
public class WeatherJob extends JobService {
    static final int ID_PERIODIC = 801;
    static final int ID_ONESHOT = 802;
    private static final long PERIOD_MS = 15L * 60 * 1000; // JobScheduler's minimum; the radar widget refreshes on it

    /** At most one fetch at a time in this process; a second job that starts meanwhile just ends. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, Worker> workers = new ConcurrentHashMap<>();

    private static final class Worker {
        volatile boolean stopped;
        volatile Thread thread;
    }

    /** Idempotent: keeps an existing periodic job (and its phase) when the interval already matches. */
    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo existing = js.getPendingJob(ID_PERIODIC);
            if (existing != null && existing.isPeriodic() && existing.getIntervalMillis() == PERIOD_MS) return;
            js.schedule(new JobInfo.Builder(ID_PERIODIC, new ComponentName(ctx, WeatherJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build());
        } catch (RuntimeException e) {
            Log.w("DotHome", "schedule failed: " + e);
        }
    }

    /** One-shot refresh. No-op while a one-shot is queued/running or a fetch is in flight. */
    static void refreshSoon(Context ctx) {
        try {
            if (RUNNING.get()) return;
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(ID_ONESHOT) != null) return;
            js.schedule(new JobInfo.Builder(ID_ONESHOT, new ComponentName(ctx, WeatherJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
        } catch (RuntimeException e) {
            Log.w("DotHome", "refreshSoon failed: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!RUNNING.compareAndSet(false, true)) return false; // another fetch is already running
        final Context app = getApplicationContext();
        final int id = params.getJobId();
        final Worker w = new Worker();
        workers.put(id, w);
        Thread t = new Thread(() -> {
            try {
                WeatherRepo.refresh(app, () -> w.stopped);
                if (!w.stopped) WidgetUpdater.updateAll(app);
                if (!w.stopped) {
                    try {
                        RadarWidget.updateAll(app); // network on this worker thread; a radar failure never fails the job
                    } catch (Throwable e) {
                        Log.w("DotHome", "radar widget error: " + e);
                    }
                }
            } catch (Throwable e) { // an Error here would otherwise kill the shared wallpaper process
                Log.w("DotHome", "weather job error: " + e);
            } finally {
                workers.remove(id, w);
                RUNNING.set(false);
                if (!w.stopped) jobFinished(params, false);
            }
        }, "weather-job-" + id);
        w.thread = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Worker w = workers.remove(params.getJobId());
        if (w != null) {
            w.stopped = true;
            Thread t = w.thread;
            if (t != null) t.interrupt();
        }
        return params.getJobId() == ID_PERIODIC; // reschedule the periodic one; the one-shot is re-requested on demand
    }
}
