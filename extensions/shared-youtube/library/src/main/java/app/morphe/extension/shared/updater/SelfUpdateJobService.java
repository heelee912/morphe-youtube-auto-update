/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.updater;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SelfUpdateJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        boolean started = SelfUpdater.startCheck(
                this,
                () -> jobFinished(params, false));
        return started;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
