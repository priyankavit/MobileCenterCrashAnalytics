package com.microsoft.azure.mobile.distribute;

import android.app.Activity;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;

import com.microsoft.azure.mobile.utils.HashUtils;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.NetworkStateHelper;
import com.microsoft.azure.mobile.utils.UUIDUtils;
import com.microsoft.azure.mobile.utils.storage.StorageHelper;

import org.json.JSONException;

import static com.microsoft.azure.mobile.distribute.DistributeConstants.DOWNLOAD_STATE_COMPLETED;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.INVALID_DOWNLOAD_IDENTIFIER;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.LOG_TAG;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PARAMETER_PLATFORM;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PARAMETER_PLATFORM_VALUE;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PARAMETER_REDIRECT_ID;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PARAMETER_RELEASE_HASH;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PARAMETER_REQUEST_ID;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOAD_ID;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOAD_STATE;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PREFERENCE_KEY_RELEASE_DETAILS;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.PREFERENCE_KEY_REQUEST_ID;
import static com.microsoft.azure.mobile.distribute.DistributeConstants.UPDATE_SETUP_PATH_FORMAT;

/**
 * Some static util methods to avoid the main file getting too big.
 */
class DistributeUtils {

    /**
     * Get the intent used to open installation U.I.
     *
     * @param fileUri downloaded file URI from the download manager.
     * @return intent to open installation U.I.
     */
    @NonNull
    static Intent getInstallIntent(Uri fileUri) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(fileUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    /**
     * Get the notification identifier for downloads.
     *
     * @return notification identifier for downloads.
     */
    static int getNotificationId() {
        return Distribute.class.getName().hashCode();
    }

    @SuppressWarnings("deprecation")
    static Notification buildNotification(Notification.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return builder.build();
        } else {
            return builder.getNotification();
        }
    }

    /**
     * Get download identifier from storage.
     *
     * @return download identifier or negative value if not found.
     */
    static long getStoredDownloadId() {
        return StorageHelper.PreferencesStorage.getLong(PREFERENCE_KEY_DOWNLOAD_ID, INVALID_DOWNLOAD_IDENTIFIER);
    }

    /**
     * Get download state from storage.
     *
     * @return download state (completed by default).
     */
    static int getStoredDownloadState() {
        return StorageHelper.PreferencesStorage.getInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_COMPLETED);
    }

    @NonNull
    static String computeReleaseHash(@NonNull PackageInfo packageInfo) {
        return HashUtils.sha256(packageInfo.packageName + ":" + packageInfo.versionName + ":" + packageInfo.versionCode);
    }

    /**
     * Update setup using browser.
     *
     * @param activity    activity from which to start browser.
     * @param installUrl  base install site URL.
     * @param appSecret   application secret.
     * @param packageInfo package info.
     */
    static void updateSetupUsingBrowser(Activity activity, String installUrl, String appSecret, PackageInfo packageInfo) {

        /*
         * If network is disconnected, browser will fail so wait.
         * Also we can't just wait for network to be up and launch browser at that time
         * as it's unpredictable and will interrupt the user, so just wait next relaunch.
         */
        if (!NetworkStateHelper.getSharedInstance(activity).isNetworkConnected()) {
            MobileCenterLog.info(LOG_TAG, "Postpone enabling in app updates via browser as network is disconnected.");
            Distribute.getInstance().completeWorkflow();
            return;
        }

        /* Compute hash. */
        String releaseHash = computeReleaseHash(packageInfo);

        /* Generate request identifier. */
        String requestId = UUIDUtils.randomUUID().toString();

        /* Build URL. */
        String url = installUrl;
        url += String.format(UPDATE_SETUP_PATH_FORMAT, appSecret);
        url += "?" + PARAMETER_RELEASE_HASH + "=" + releaseHash;
        url += "&" + PARAMETER_REDIRECT_ID + "=" + activity.getPackageName();
        url += "&" + PARAMETER_REQUEST_ID + "=" + requestId;
        url += "&" + PARAMETER_PLATFORM + "=" + PARAMETER_PLATFORM_VALUE;
        MobileCenterLog.debug(LOG_TAG, "No token, need to open browser to url=" + url);

        /* Store request id. */
        StorageHelper.PreferencesStorage.putString(PREFERENCE_KEY_REQUEST_ID, requestId);

        /* Open browser, remember that whatever the outcome to avoid opening it twice. */
        BrowserUtils.openBrowser(url, activity);
    }

    /**
     * Get release details from cache if any.
     *
     * @return release details from cache or null.
     */
    static ReleaseDetails loadCachedReleaseDetails() {
        String cachedReleaseDetails = StorageHelper.PreferencesStorage.getString(PREFERENCE_KEY_RELEASE_DETAILS);
        if (cachedReleaseDetails != null) {
            try {
                return ReleaseDetails.parse(cachedReleaseDetails);
            } catch (JSONException e) {
                MobileCenterLog.error(LOG_TAG, "Invalid release details in cache.", e);
                StorageHelper.PreferencesStorage.remove(PREFERENCE_KEY_RELEASE_DETAILS);
            }
        }
        return null;
    }
}
