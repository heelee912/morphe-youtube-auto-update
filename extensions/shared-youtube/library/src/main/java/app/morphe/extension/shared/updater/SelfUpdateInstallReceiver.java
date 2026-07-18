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
import android.content.pm.PackageInstaller;
import android.util.Log;

public final class SelfUpdateInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "MorpheSelfUpdater";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SelfUpdater.ACTION_INSTALL_RESULT.equals(intent.getAction())) return;

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        long assetId = intent.getLongExtra(SelfUpdater.EXTRA_ASSET_ID, -1L);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            SelfUpdater.confirmPendingInstall(context);
            Log.i(TAG, "Install callback succeeded for asset " + assetId);
            return;
        }

        String error = "Install status " + status + (message == null ? "" : ": " + message);
        SelfUpdater.clearPendingInstall(context, error);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.e(TAG, "Unexpected user confirmation request for self-update asset " + assetId);
        } else {
            Log.e(TAG, error);
        }
    }
}
