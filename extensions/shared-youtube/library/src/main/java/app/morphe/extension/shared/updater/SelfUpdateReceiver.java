/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SelfUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SelfUpdater.EXPECTED_PACKAGE_NAME.equals(context.getPackageName())) return;

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            SelfUpdater.confirmPendingInstall(context);
        }
        SelfUpdater.schedulePeriodic(context);
        SelfUpdater.requestCheckIfDue(context, 2L * 60L * 1000L);
    }
}
