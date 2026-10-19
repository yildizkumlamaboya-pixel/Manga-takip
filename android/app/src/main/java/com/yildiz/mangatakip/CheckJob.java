package com.yildiz.mangatakip;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Android'in 15 dakikada bir çalıştırdığı arka plan kontrolü. */
public class CheckJob extends JobService {

    static final int JOB_ID = 1001;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CheckJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Net.applyWebView(this);
            Checker c = new Checker(this);
            c.run();
            // Çevrimdışı açık seriler için sıradaki bölümleri indir (en fazla 8 dk)
            if (c.lastList != null) Offline.ensureAll(this, c.lastList, c.lastState, 8 * 60_000L);
            else Offline.cleanup(this);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // yarıda kesildiyse tekrar dene
    }
}
