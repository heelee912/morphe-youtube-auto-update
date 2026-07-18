/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.updater;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verified, non-root self-updater for the Morphe YouTube package.
 */
public final class SelfUpdater {
    static final String EXPECTED_PACKAGE_NAME = "app.morphe.android.youtube";
    static final String ACTION_INSTALL_RESULT = EXPECTED_PACKAGE_NAME + ".SELF_UPDATE_RESULT";
    static final String EXTRA_ASSET_ID = "asset_id";

    private static final String TAG = "MorpheSelfUpdater";
    private static final String METADATA_RELEASE_API = "app.morphe.SELF_UPDATE_RELEASE_API";
    private static final String RELEASE_ASSET_NAME = "youtube-arm64-v8a-morphe-selfupdate.apk";
    private static final String PREFERENCES_NAME = "morphe_self_updater";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_LAST_INSTALLED_ASSET_ID = "last_installed_asset_id";
    private static final String KEY_PENDING_ASSET_ID = "pending_asset_id";
    private static final String KEY_PENDING_SINCE_MS = "pending_since_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final int PERIODIC_JOB_ID = 0x4d4f5250;
    private static final int ONE_OFF_JOB_ID = 0x4d4f5251;
    private static final long PERIODIC_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final long PERIODIC_FLEX_MS = 60L * 60L * 1000L;
    private static final long STARTUP_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long PENDING_TIMEOUT_MS = 6L * 60L * 60L * 1000L;
    private static final long MAX_APK_SIZE_BYTES = 250L * 1024L * 1024L;
    private static final int JSON_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final AtomicBoolean CHECK_RUNNING = new AtomicBoolean();

    private SelfUpdater() {
    }

    public static void initialize(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isExpectedPackage(appContext)) return;

        schedulePeriodic(appContext);
        requestCheckIfDue(appContext, 0L);
    }

    static boolean startCheck(Context context, Runnable completion) {
        Context appContext = context.getApplicationContext();
        if (!isExpectedPackage(appContext) || !CHECK_RUNNING.compareAndSet(false, true)) {
            return false;
        }

        Thread worker = new Thread(() -> {
            try {
                performCheck(appContext);
            } catch (Exception exception) {
                recordError(appContext, exception.toString());
                Log.e(TAG, "Update check failed", exception);
            } finally {
                preferences(appContext).edit()
                        .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                        .apply();
                CHECK_RUNNING.set(false);
                if (completion != null) completion.run();
            }
        }, "MorpheSelfUpdater");
        worker.start();
        return true;
    }

    static void schedulePeriodic(Context context) {
        if (!isExpectedPackage(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(PERIODIC_JOB_ID) != null) return;

        JobInfo job = new JobInfo.Builder(
                PERIODIC_JOB_ID,
                new ComponentName(context, SelfUpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIODIC_INTERVAL_MS, PERIODIC_FLEX_MS)
                .setEstimatedNetworkBytes(MAX_APK_SIZE_BYTES, 0L)
                .build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Could not schedule periodic update check");
        }
    }

    static void requestCheckIfDue(Context context, long minimumLatencyMs) {
        if (!isExpectedPackage(context)) return;
        SharedPreferences preferences = preferences(context);
        long elapsed = System.currentTimeMillis() - preferences.getLong(KEY_LAST_CHECK_MS, 0L);
        if (elapsed >= 0L && elapsed < STARTUP_CHECK_INTERVAL_MS) return;

        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(ONE_OFF_JOB_ID) != null) return;

        JobInfo job = new JobInfo.Builder(
                ONE_OFF_JOB_ID,
                new ComponentName(context, SelfUpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(0L, minimumLatencyMs))
                .setOverrideDeadline(Math.max(5L * 60L * 1000L, minimumLatencyMs + 5L * 60L * 1000L))
                .setEstimatedNetworkBytes(MAX_APK_SIZE_BYTES, 0L)
                .build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Could not schedule one-off update check");
        }
    }

    static void confirmPendingInstall(Context context) {
        SharedPreferences preferences = preferences(context);
        long pendingAssetId = preferences.getLong(KEY_PENDING_ASSET_ID, -1L);
        if (pendingAssetId < 0L) return;

        preferences.edit()
                .putLong(KEY_LAST_INSTALLED_ASSET_ID, pendingAssetId)
                .remove(KEY_PENDING_ASSET_ID)
                .remove(KEY_PENDING_SINCE_MS)
                .remove(KEY_LAST_ERROR)
                .apply();
        Log.i(TAG, "Self-update installed from asset " + pendingAssetId);
    }

    static void clearPendingInstall(Context context, String error) {
        preferences(context).edit()
                .remove(KEY_PENDING_ASSET_ID)
                .remove(KEY_PENDING_SINCE_MS)
                .putString(KEY_LAST_ERROR, error)
                .apply();
    }

    private static void performCheck(Context context) throws Exception {
        AssetInfo asset = fetchLatestAsset(context);
        SharedPreferences preferences = preferences(context);
        if (preferences.getLong(KEY_LAST_INSTALLED_ASSET_ID, -1L) == asset.id) {
            Log.i(TAG, "Latest update is already installed");
            return;
        }

        long pendingAssetId = preferences.getLong(KEY_PENDING_ASSET_ID, -1L);
        long pendingSinceMs = preferences.getLong(KEY_PENDING_SINCE_MS, 0L);
        long pendingAgeMs = System.currentTimeMillis() - pendingSinceMs;
        if (pendingAssetId == asset.id && pendingAgeMs >= 0L && pendingAgeMs < PENDING_TIMEOUT_MS) {
            Log.i(TAG, "Latest update is already pending");
            return;
        }
        if (pendingAssetId >= 0L) {
            preferences.edit()
                    .remove(KEY_PENDING_ASSET_ID)
                    .remove(KEY_PENDING_SINCE_MS)
                    .apply();
        }

        File apk = downloadAndVerifyDigest(context, asset);
        try {
            verifyApk(context, apk);
            installApk(context, apk, asset);
        } finally {
            if (!apk.delete()) apk.deleteOnExit();
        }
    }

    private static AssetInfo fetchLatestAsset(Context context) throws Exception {
        URL url = requireHttpsUrl(getReleaseApiUrl(context));
        HttpURLConnection connection = openConnection(url);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Release API returned HTTP " + responseCode);
            }

            JSONObject release = new JSONObject(readUtf8(connection.getInputStream(), JSON_LIMIT_BYTES));
            JSONArray assets = release.getJSONArray("assets");
            for (int index = 0; index < assets.length(); index++) {
                JSONObject candidate = assets.getJSONObject(index);
                if (!RELEASE_ASSET_NAME.equals(candidate.optString("name"))) continue;

                long id = candidate.getLong("id");
                long size = candidate.getLong("size");
                String downloadUrl = candidate.getString("browser_download_url");
                String digest = candidate.optString("digest", "");
                if (!digest.startsWith("sha256:") || digest.length() != 71) {
                    throw new IllegalStateException("Release asset has no valid SHA-256 digest");
                }
                if (size <= 0L || size > MAX_APK_SIZE_BYTES) {
                    throw new IllegalStateException("Release asset size is invalid: " + size);
                }
                requireHttpsUrl(downloadUrl);
                return new AssetInfo(id, size, downloadUrl, digest.substring(7));
            }
            throw new IllegalStateException("Release asset was not found");
        } finally {
            connection.disconnect();
        }
    }

    private static File downloadAndVerifyDigest(Context context, AssetInfo asset) throws Exception {
        File destination = File.createTempFile("morphe-self-update-", ".apk", context.getCacheDir());
        boolean complete = false;
        HttpURLConnection connection = openConnection(requireHttpsUrl(asset.downloadUrl));
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("APK download returned HTTP " + responseCode);
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new SecurityException("APK download redirected away from HTTPS");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > asset.size || written > MAX_APK_SIZE_BYTES) {
                        throw new SecurityException("APK download exceeded the declared size");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }

            if (written != asset.size) {
                throw new SecurityException("APK size mismatch: expected " + asset.size + ", got " + written);
            }
            byte[] expectedDigest = hexToBytes(asset.sha256);
            if (!MessageDigest.isEqual(expectedDigest, digest.digest())) {
                throw new SecurityException("APK SHA-256 digest mismatch");
            }
            complete = true;
            return destination;
        } finally {
            connection.disconnect();
            if (!complete && !destination.delete()) destination.deleteOnExit();
        }
    }

    private static void verifyApk(Context context, File apk) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo current = packageManager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = packageManager.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null) throw new SecurityException("Downloaded APK could not be parsed");
        if (!context.getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("Downloaded APK package mismatch: " + candidate.packageName);
        }
        if (candidate.getLongVersionCode() < current.getLongVersionCode()) {
            throw new SecurityException("Downloaded APK is a downgrade");
        }
        if (!signerDigests(current.signingInfo).equals(signerDigests(candidate.signingInfo))) {
            throw new SecurityException("Downloaded APK signer does not match the installed app");
        }
    }

    private static void installApk(Context context, File apk, AssetInfo asset) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        if (!packageManager.canRequestPackageInstalls()) {
            throw new SecurityException("Install unknown apps access is not granted");
        }

        PackageInstaller installer = packageManager.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        params.setOriginatingUri(Uri.parse(asset.downloadUrl));
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        }

        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new FileInputStream(apk);
             OutputStream output = session.openWrite("base.apk", 0L, apk.length())) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            session.fsync(output);

            preferences(context).edit()
                    .putLong(KEY_PENDING_ASSET_ID, asset.id)
                    .putLong(KEY_PENDING_SINCE_MS, System.currentTimeMillis())
                    .remove(KEY_LAST_ERROR)
                    .commit();

            Intent result = new Intent(context, SelfUpdateInstallReceiver.class)
                    .setAction(ACTION_INSTALL_RESULT)
                    .putExtra(EXTRA_ASSET_ID, asset.id);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, result, flags);
            session.commit(pendingIntent.getIntentSender());
            committed = true;
            Log.i(TAG, "Committed self-update session " + sessionId + " for asset " + asset.id);
        } finally {
            if (!committed) {
                installer.abandonSession(sessionId);
                clearPendingInstall(context, "Install session failed before commit");
            }
        }
    }

    private static Set<String> signerDigests(SigningInfo signingInfo) throws Exception {
        if (signingInfo == null) throw new SecurityException("APK has no signing information");
        Signature[] signatures = signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) {
            throw new SecurityException("APK has no current signer");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Set<String> result = new TreeSet<>();
        for (Signature signature : signatures) {
            result.add(bytesToHex(digest.digest(signature.toByteArray())));
        }
        return result;
    }

    private static HttpURLConnection openConnection(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Morphe-YouTube-Self-Updater/1");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static URL requireHttpsUrl(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Only HTTPS update URLs are allowed");
        }
        return url;
    }

    private static String getReleaseApiUrl(Context context) throws Exception {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
        Bundle metadata = info.metaData;
        String value = metadata == null ? null : metadata.getString(METADATA_RELEASE_API);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Self-update release API metadata is missing");
        }
        return value;
    }

    private static String readUtf8(InputStream input, int limitBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > limitBytes) throw new SecurityException("Response exceeded size limit");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static byte[] hexToBytes(String value) {
        if (value.length() % 2 != 0) throw new IllegalArgumentException("Invalid hex length");
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            int high = Character.digit(value.charAt(index), 16);
            int low = Character.digit(value.charAt(index + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex value");
            result[index / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static boolean isExpectedPackage(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && EXPECTED_PACKAGE_NAME.equals(context.getPackageName());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void recordError(Context context, String error) {
        preferences(context).edit().putString(KEY_LAST_ERROR, error).apply();
    }

    private static final class AssetInfo {
        final long id;
        final long size;
        final String downloadUrl;
        final String sha256;

        AssetInfo(long id, long size, String downloadUrl, String sha256) {
            this.id = id;
            this.size = size;
            this.downloadUrl = downloadUrl;
            this.sha256 = sha256;
        }
    }
}
