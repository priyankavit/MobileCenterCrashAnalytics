package com.microsoft.azure.mobile.crashes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;

import com.microsoft.azure.mobile.AbstractMobileCenterService;
import com.microsoft.azure.mobile.Constants;
import com.microsoft.azure.mobile.ResultCallback;
import com.microsoft.azure.mobile.channel.Channel;
import com.microsoft.azure.mobile.crashes.ingestion.models.ManagedErrorLog;
import com.microsoft.azure.mobile.crashes.ingestion.models.json.ManagedErrorLogFactory;
import com.microsoft.azure.mobile.crashes.model.ErrorReport;
import com.microsoft.azure.mobile.crashes.model.TestCrashException;
import com.microsoft.azure.mobile.crashes.utils.ErrorLogHelper;
import com.microsoft.azure.mobile.ingestion.models.Log;
import com.microsoft.azure.mobile.ingestion.models.json.DefaultLogSerializer;
import com.microsoft.azure.mobile.ingestion.models.json.LogFactory;
import com.microsoft.azure.mobile.ingestion.models.json.LogSerializer;
import com.microsoft.azure.mobile.utils.HandlerUtils;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.storage.StorageHelper;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * Crashes service.
 */
public class Crashes extends AbstractMobileCenterService {

    /**
     * Constant for SEND crash report.
     */
    public static final int SEND = 0;

    /**
     * Constant for DO NOT SEND crash report.
     */
    public static final int DONT_SEND = 1;

    /**
     * Constant for ALWAYS SEND crash reports.
     */
    public static final int ALWAYS_SEND = 2;

    /**
     * Preference storage key for ALWAYS SEND.
     */
    /* TODO maybe add an API to reset and make that private. */
    @VisibleForTesting
    public static final String PREF_KEY_ALWAYS_SEND = "com.microsoft.azure.mobile.crashes.always.send";

    /**
     * Group for sending logs.
     */
    @VisibleForTesting
    static final String ERROR_GROUP = "group_errors";

    /**
     * Thread name for persistence to access database.
     */
    private static final String THREAD_NAME = "CrashesThread";

    /**
     * Name of the service.
     */
    private static final String SERVICE_NAME = "Crashes";

    /**
     * TAG used in logging for Crashes.
     */
    public static final String LOG_TAG = MobileCenterLog.LOG_TAG + SERVICE_NAME;

    /**
     * Default crashes listener.
     */
    private static final CrashesListener DEFAULT_ERROR_REPORTING_LISTENER = new DefaultCrashesListener();

    /**
     * Singleton.
     */
    @SuppressLint("StaticFieldLeak")
    private static Crashes sInstance = null;

    /**
     * Handler for background thread loop.
     */
    private final Handler mHandler;

    /**
     * Log factories managed by this service.
     */
    private final Map<String, LogFactory> mFactories;

    /**
     * Crash reports not processed yet.
     */
    private final Map<UUID, ErrorLogReport> mUnprocessedErrorReports;

    /**
     * Cache for reports that are queued to channel but not yet sent.
     */
    private final Map<UUID, ErrorLogReport> mErrorReportCache;

    /**
     * List of crash report callbacks.
     */
    private final List<ResultCallback<ErrorReport>> mLastCrashErrorReportCallbacks = new ArrayList<>();

    /**
     * Count down latch for waiting crash report.
     */
    private CountDownLatch mCountDownLatch;

    /**
     * Log serializer.
     */
    private LogSerializer mLogSerializer;

    /**
     * Application context.
     */
    private Context mContext;

    /**
     * Timestamp of initialization.
     */
    private long mInitializeTimestamp;

    /**
     * Crash handler.
     */
    private UncaughtExceptionHandler mUncaughtExceptionHandler;

    /**
     * Custom crashes listener.
     */
    private CrashesListener mCrashesListener;

    /**
     * Wrapper SDK listener.
     */
    private WrapperSdkListener mWrapperSdkListener;

    /**
     * ErrorReport for the last session.
     */
    private ErrorReport mLastSessionErrorReport;

    private Crashes() {
        mFactories = new HashMap<>();
        mFactories.put(ManagedErrorLog.TYPE, ManagedErrorLogFactory.getInstance());
        mLogSerializer = new DefaultLogSerializer();
        mLogSerializer.addLogFactory(ManagedErrorLog.TYPE, ManagedErrorLogFactory.getInstance());
        mCrashesListener = DEFAULT_ERROR_REPORTING_LISTENER;
        mUnprocessedErrorReports = new LinkedHashMap<>();
        mErrorReportCache = new LinkedHashMap<>();
        HandlerThread thread = new HandlerThread(THREAD_NAME);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @NonNull
    public static synchronized Crashes getInstance() {
        if (sInstance == null) {
            sInstance = new Crashes();
        }
        return sInstance;
    }

    @VisibleForTesting
    static synchronized void unsetInstance() {
        sInstance = null;
    }

    /**
     * Check whether Crashes service is enabled or not.
     *
     * @return <code>true</code> if enabled, <code>false</code> otherwise.
     */
    public static boolean isEnabled() {
        return getInstance().isInstanceEnabled();
    }

    /**
     * Enable or disable Crashes service.
     *
     * @param enabled <code>true</code> to enable, <code>false</code> to disable.
     */
    public static void setEnabled(boolean enabled) {
        getInstance().setInstanceEnabled(enabled);
    }

    /**
     * Track an exception.
     * TODO the backend does not support that service yet, will be public method later.
     *
     * @param throwable An exception.
     */
    static void trackException(@NonNull Throwable throwable) {
        getInstance().queueException(throwable);
    }

    /**
     * Generates crash for test purpose.
     */
    public static void generateTestCrash() {
        if (Constants.APPLICATION_DEBUGGABLE)
            throw new TestCrashException();
        else
            MobileCenterLog.warn(LOG_TAG, "The application is not debuggable so SDK won't generate test crash");
    }

    /**
     * Sets a crashes listener.
     *
     * @param listener The custom crashes listener.
     */
    public static void setListener(CrashesListener listener) {
        getInstance().setInstanceListener(listener);
    }

    /**
     * Notifies SDK with a confirmation to handle the crash report.
     *
     * @param userConfirmation A user confirmation. Should be one of {@link #SEND}, {@link #DONT_SEND} or {@link #ALWAYS_SEND}
     * @see #SEND
     * @see #DONT_SEND
     * @see #ALWAYS_SEND
     */
    public static void notifyUserConfirmation(@UserConfirmationDef int userConfirmation) {
        getInstance().handleUserConfirmation(userConfirmation);
    }

    /**
     * Check whether the app crashed in its last session.
     *
     * @return {@code true} if a crash was recorded in the last session, otherwise {@code false}.
     */
    public static boolean hasCrashedInLastSession() {
        return getInstance().hasInstanceCrashedInLastSession();
    }

    /**
     * Provides information about any available crash report from the last session, if it crashed.
     * This method is a synchronous call and blocks caller thread.
     * Use {@link #getLastSessionCrashReport(ResultCallback)} for asynchronous call.
     *
     * @return The crash report from the last session if one was set.
     * @see #getLastSessionCrashReport(ResultCallback)
     */
    @Nullable
    @WorkerThread
    static ErrorReport getLastSessionCrashReport() {
        return getInstance().getInstanceLastSessionCrashReport();
    }

    /**
     * Provides information about any available crash report from the last session asynchronously, if it crashed.
     *
     * @param callback The callback that will receive crash in the last session.
     */
    public static void getLastSessionCrashReport(ResultCallback<ErrorReport> callback) {
        getInstance().getInstanceLastSessionCrashReport(callback);
    }

    /**
     * Implements {@link #hasCrashedInLastSession()} at instance level.
     */
    private synchronized boolean hasInstanceCrashedInLastSession() {
        return mLastSessionErrorReport != null || (mCountDownLatch != null && mCountDownLatch.getCount() > 0);
    }

    /**
     * Implements {@link #getLastSessionCrashReport()} at instance level.
     */
    private synchronized ErrorReport getInstanceLastSessionCrashReport() {
        if (mCountDownLatch != null) {
            MobileCenterLog.debug(LOG_TAG, "Waiting for Crashes service to complete crash report for the last session.");
            try {
                mCountDownLatch.await();
            } catch (InterruptedException e) {
                MobileCenterLog.debug(LOG_TAG, "Could not get crash report for the last session.", e);
            }
        }
        return mLastSessionErrorReport;
    }

    /**
     * Implements {@link #getLastSessionCrashReport(ResultCallback)} at instance level.
     */
    private synchronized void getInstanceLastSessionCrashReport(final ResultCallback<ErrorReport> callback) {
        if (mCountDownLatch == null || mCountDownLatch.getCount() <= 0)
            HandlerUtils.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    callback.onResult(mLastSessionErrorReport);
                }
            });
        else {
            mLastCrashErrorReportCallbacks.add(callback);
            MobileCenterLog.info(LOG_TAG, "Crashes for the last session have not been processed yet. The SDK will call listener when it completes processing.");
        }
    }

    @Override
    public synchronized void setInstanceEnabled(boolean enabled) {
        super.setInstanceEnabled(enabled);
        initialize();
        if (!enabled) {
            mHandler.getLooper().quit();
            for (File file : ErrorLogHelper.getErrorStorageDirectory().listFiles()) {
                MobileCenterLog.debug(LOG_TAG, "Deleting file " + file);
                if (!file.delete()) {
                    MobileCenterLog.warn(LOG_TAG, "Failed to delete file " + file);
                }
            }
            MobileCenterLog.info(LOG_TAG, "Deleted crashes local files");
        }
    }

    @Override
    public synchronized void onStarted(@NonNull Context context, @NonNull String appSecret, @NonNull Channel channel) {
        super.onStarted(context, appSecret, channel);
        mContext = context;
        initialize();
        if (isInstanceEnabled()) {
            processPendingErrors();
        }
    }

    @Override
    public Map<String, LogFactory> getLogFactories() {
        return mFactories;
    }

    /**
     * Track an exception.
     * TODO the backend does not support that service yet, will be public method later.
     *
     * @param exception An exception.
     */
    synchronized void trackException(@NonNull com.microsoft.azure.mobile.crashes.ingestion.models.Exception exception) {
        if (isInactive())
            return;

        ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(
                mContext,
                Thread.currentThread(),
                exception,
                Thread.getAllStackTraces(),
                getInitializeTimestamp(),
                false);
        mChannel.enqueue(errorLog, ERROR_GROUP);
    }

    @Override
    protected String getGroupName() {
        return ERROR_GROUP;
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    protected String getLoggerTag() {
        return LOG_TAG;
    }

    @Override
    protected int getTriggerCount() {
        return 1;
    }

    @Override
    protected Channel.GroupListener getChannelListener() {
        return new Channel.GroupListener() {

            /** Process callback (template method) */
            private void processCallback(Log log, final CallbackProcessor callbackProcessor) {
                if (log instanceof ManagedErrorLog) {
                    ManagedErrorLog errorLog = (ManagedErrorLog) log;
                    if (errorLog.getFatal()) {
                        final ErrorReport report = buildErrorReport(errorLog);
                        UUID id = errorLog.getId();

                        if (report != null) {

                            /* Clean up before calling callbacks if requested. */
                            if (callbackProcessor.shouldDeleteThrowable()) {
                                removeStoredThrowable(id);
                            }

                            /* Call back. */
                            HandlerUtils.runOnUiThread(new Runnable() {

                                @Override
                                public void run() {
                                    callbackProcessor.onCallBack(report);
                                }
                            });
                        } else
                            MobileCenterLog.warn(LOG_TAG, "Cannot find crash report for the error log: " + id);
                    }
                } else {
                    MobileCenterLog.warn(LOG_TAG, "A different type of log comes to crashes: " + log.getClass().getName());
                }
            }

            @Override
            public void onBeforeSending(Log log) {
                processCallback(log, new CallbackProcessor() {

                    @Override
                    public boolean shouldDeleteThrowable() {
                        return false;
                    }

                    @Override
                    public void onCallBack(ErrorReport report) {
                        mCrashesListener.onBeforeSending(report);
                    }
                });
            }

            @Override
            public void onSuccess(Log log) {
                processCallback(log, new CallbackProcessor() {

                    @Override
                    public boolean shouldDeleteThrowable() {
                        return true;
                    }

                    @Override
                    public void onCallBack(ErrorReport report) {
                        mCrashesListener.onSendingSucceeded(report);
                    }
                });
            }

            @Override
            public void onFailure(Log log, final Exception e) {
                processCallback(log, new CallbackProcessor() {

                    @Override
                    public boolean shouldDeleteThrowable() {
                        return true;
                    }

                    @Override
                    public void onCallBack(ErrorReport report) {
                        mCrashesListener.onSendingFailed(report, e);
                    }
                });
            }
        };
    }

    /**
     * Get initialization timestamp.
     *
     * @return initialization timestamp expressed using {@link SystemClock#elapsedRealtime()}.
     */
    @VisibleForTesting
    synchronized long getInitializeTimestamp() {
        return mInitializeTimestamp;
    }

    /**
     * Get Crashes handler for last session error report.
     *
     * @return Crashes handler.
     */
    @VisibleForTesting
    Handler getHandler() {
        return mHandler;
    }

    /**
     * Send an exception.
     *
     * @param throwable An exception.
     */
    private synchronized void queueException(@NonNull final Throwable throwable) {
        if (isInactive())
            return;
        ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(
                mContext,
                Thread.currentThread(),
                throwable,
                Thread.getAllStackTraces(),
                getInitializeTimestamp(),
                false);
        mChannel.enqueue(errorLog, ERROR_GROUP);
    }

    private void initialize() {
        boolean enabled = isInstanceEnabled();
        mInitializeTimestamp = enabled ? SystemClock.elapsedRealtime() : -1;

        if (!enabled) {
            if (mUncaughtExceptionHandler != null) {
                mUncaughtExceptionHandler.unregister();
                mUncaughtExceptionHandler = null;
            }
        } else if (mContext != null && mUncaughtExceptionHandler == null) {
            mUncaughtExceptionHandler = new UncaughtExceptionHandler();
            mUncaughtExceptionHandler.register();
            final File logFile = ErrorLogHelper.getLastErrorLogFile();
            if (logFile != null) {
                MobileCenterLog.debug(LOG_TAG, "Processing crash report for the last session.");
                mCountDownLatch = new CountDownLatch(1);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        String logFileContents = StorageHelper.InternalStorage.read(logFile);
                        if (logFileContents == null)
                            MobileCenterLog.error(LOG_TAG, "Error reading last session error log.");
                        else {
                            try {
                                ManagedErrorLog log = (ManagedErrorLog) mLogSerializer.deserializeLog(logFileContents);
                                mLastSessionErrorReport = buildErrorReport(log);
                                MobileCenterLog.debug(LOG_TAG, "Processed crash report for the last session.");
                            } catch (JSONException e) {
                                MobileCenterLog.error(LOG_TAG, "Error parsing last session error log.", e);
                            }
                        }

                        mCountDownLatch.countDown();

                        HandlerUtils.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                /* Call callbacks for getInstanceLastSessionCrashReport(ResultCallback) . */
                                for (Iterator<ResultCallback<ErrorReport>> iterator = mLastCrashErrorReportCallbacks.iterator(); iterator.hasNext(); ) {
                                    ResultCallback<ErrorReport> callback = iterator.next();
                                    iterator.remove();
                                    callback.onResult(mLastSessionErrorReport);
                                }
                            }
                        });
                    }
                });
            }
        }
    }

    private boolean shouldStopProcessingPendingErrors() {
        if (!isInstanceEnabled()) {
            MobileCenterLog.info(LOG_TAG, "Crashes service is disabled while processing errors. Cancel processing all pending errors.");
            return true;
        }
        return false;
    }

    private void processPendingErrors() {
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                for (File logFile : ErrorLogHelper.getStoredErrorLogFiles()) {
                    if (shouldStopProcessingPendingErrors())
                        return;
                    MobileCenterLog.debug(LOG_TAG, "Process pending error file: " + logFile);
                    String logfileContents = StorageHelper.InternalStorage.read(logFile);
                    if (logfileContents != null)
                        try {
                            ManagedErrorLog log = (ManagedErrorLog) mLogSerializer.deserializeLog(logfileContents);
                            UUID id = log.getId();
                            ErrorReport report = buildErrorReport(log);
                            if (report == null) {
                                removeAllStoredErrorLogFiles(id);
                            } else if (mCrashesListener.shouldProcess(report)) {
                                MobileCenterLog.debug(LOG_TAG, "CrashesListener.shouldProcess returned true, continue processing log: " + id.toString());
                                mUnprocessedErrorReports.put(id, mErrorReportCache.get(id));
                            } else {
                                MobileCenterLog.debug(LOG_TAG, "CrashesListener.shouldProcess returned false, clean up and ignore log: " + id.toString());
                                removeAllStoredErrorLogFiles(id);
                            }
                        } catch (JSONException e) {
                            MobileCenterLog.error(LOG_TAG, "Error parsing error log", e);
                        }
                }

                if (shouldStopProcessingPendingErrors())
                    return;

                processUserConfirmation();
            }
        });
    }

    private void processUserConfirmation() {

        /* Handle user confirmation in UI thread. */
        HandlerUtils.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                boolean shouldAwaitUserConfirmation = true;
                if (mUnprocessedErrorReports.size() > 0 &&
                        (StorageHelper.PreferencesStorage.getBoolean(PREF_KEY_ALWAYS_SEND, false)
                                || !(shouldAwaitUserConfirmation = mCrashesListener.shouldAwaitUserConfirmation()))) {
                    if (!shouldAwaitUserConfirmation)
                        MobileCenterLog.debug(LOG_TAG, "CrashesListener.shouldAwaitUserConfirmation returned false, continue sending logs");
                    else
                        MobileCenterLog.debug(LOG_TAG, "The flag for user confirmation is set to ALWAYS_SEND, continue sending logs");
                    handleUserConfirmation(SEND);
                }
            }
        });
    }

    private void removeAllStoredErrorLogFiles(UUID id) {
        ErrorLogHelper.removeStoredErrorLogFile(id);
        removeStoredThrowable(id);
    }

    private void removeStoredThrowable(UUID id) {
        mErrorReportCache.remove(id);
        WrapperSdkExceptionManager.deleteWrapperExceptionData(id);
        ErrorLogHelper.removeStoredThrowableFile(id);
    }

    @VisibleForTesting
    UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return mUncaughtExceptionHandler;
    }

    @VisibleForTesting
    void setUncaughtExceptionHandler(UncaughtExceptionHandler handler) {
        mUncaughtExceptionHandler = handler;
    }

    @VisibleForTesting
    @Nullable
    ErrorReport buildErrorReport(ManagedErrorLog log) {
        UUID id = log.getId();
        if (mErrorReportCache.containsKey(id)) {
            return mErrorReportCache.get(id).report;
        } else {
            File file = ErrorLogHelper.getStoredThrowableFile(id);
            if (file != null) {
                try {
                    Throwable throwable = StorageHelper.InternalStorage.readObject(file);
                    ErrorReport report = ErrorLogHelper.getErrorReportFromErrorLog(log, throwable);
                    mErrorReportCache.put(id, new ErrorLogReport(log, report));
                    return report;
                } catch (ClassNotFoundException ignored) {
                    MobileCenterLog.error(LOG_TAG, "Cannot read throwable file " + file.getName(), ignored);
                } catch (IOException ignored) {
                    MobileCenterLog.error(LOG_TAG, "Cannot access serialized throwable file " + file.getName(), ignored);
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    CrashesListener getInstanceListener() {
        return mCrashesListener;
    }

    @VisibleForTesting
    synchronized void setInstanceListener(CrashesListener listener) {
        if (listener == null) {
            listener = DEFAULT_ERROR_REPORTING_LISTENER;
        }
        mCrashesListener = listener;
    }

    /**
     * Set wrapper SDK listener.
     *
     * @param wrapperSdkListener listener.
     */
    @SuppressWarnings("WeakerAccess")
    public void setWrapperSdkListener(WrapperSdkListener wrapperSdkListener) {
        mWrapperSdkListener = wrapperSdkListener;
    }

    @VisibleForTesting
    private synchronized void handleUserConfirmation(@UserConfirmationDef final int userConfirmation) {
        if (mChannel == null) {
            MobileCenterLog.error(LOG_TAG, "Crashes service not initialized, discarding calls.");
            return;
        }

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (userConfirmation == DONT_SEND) {

                    /* Clean up all pending error log and throwable files. */
                    for (Iterator<UUID> iterator = mUnprocessedErrorReports.keySet().iterator(); iterator.hasNext(); ) {
                        UUID id = iterator.next();
                        iterator.remove();
                        removeAllStoredErrorLogFiles(id);
                    }
                } else {

                    if (userConfirmation == ALWAYS_SEND) {
                        StorageHelper.PreferencesStorage.putBoolean(PREF_KEY_ALWAYS_SEND, true);
                    }

                    Iterator<Map.Entry<UUID, ErrorLogReport>> unprocessedIterator = mUnprocessedErrorReports.entrySet().iterator();
                    while (unprocessedIterator.hasNext()) {
                        if (shouldStopProcessingPendingErrors())
                            break;

                        Map.Entry<UUID, ErrorLogReport> unprocessedEntry = unprocessedIterator.next();
                        ErrorLogReport errorLogReport = unprocessedEntry.getValue();

                        /* TODO (getErrorAttachment): Re-enable error attachment when the feature becomes available. */
//                        ErrorAttachment attachment = mCrashesListener.getErrorAttachment(errorLogReport.report);
//                        if (attachment == null)
//                            MobileCenterLog.debug(LOG_TAG, "CrashesListener.getErrorAttachment returned null, no additional information will be attached to log: " + errorLogReport.log.getId().toString());
//                        else
//                            errorLogReport.log.setErrorAttachment(attachment);
                        mChannel.enqueue(errorLogReport.log, ERROR_GROUP);

                        /* Clean up an error log file and map entry. */
                        unprocessedIterator.remove();
                        ErrorLogHelper.removeStoredErrorLogFile(unprocessedEntry.getKey());
                    }
                }

                /* Processed crash report for the last session. */
                if (isInstanceEnabled())
                    mHandler.getLooper().quit();
            }
        };

        /* Run on background thread if current thread is UI thread. */
        if (Looper.myLooper() == Looper.getMainLooper())
            mHandler.post(runnable);
        else
            runnable.run();
    }

    @VisibleForTesting
    void setLogSerializer(LogSerializer logSerializer) {
        mLogSerializer = logSerializer;
    }

    /**
     * Save a crash.
     *
     * @param thread    origin thread.
     * @param exception exception.
     */
    void saveUncaughtException(Thread thread, Throwable exception) {

        /* Save crash. */
        ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mContext, thread, exception, Thread.getAllStackTraces(), mInitializeTimestamp, true);
        try {
            File errorStorageDirectory = ErrorLogHelper.getErrorStorageDirectory();
            String filename = errorLog.getId().toString();
            MobileCenterLog.debug(Crashes.LOG_TAG, "Saving uncaught exception:", exception);
            saveErrorLog(errorLog, errorStorageDirectory, filename);
            File throwableFile = new File(errorStorageDirectory, filename + ErrorLogHelper.THROWABLE_FILE_EXTENSION);
            StorageHelper.InternalStorage.writeObject(throwableFile, exception);

            MobileCenterLog.debug(Crashes.LOG_TAG, "Saved Throwable as is for client side inspection in " + throwableFile);
            if (mWrapperSdkListener != null) {
                mWrapperSdkListener.onCrashCaptured(errorLog);
            }
        } catch (JSONException e) {
            MobileCenterLog.error(Crashes.LOG_TAG, "Error serializing error log to JSON", e);
        } catch (IOException e) {
            MobileCenterLog.error(Crashes.LOG_TAG, "Error writing error log to file", e);
        }
    }

    /**
     * Serialize error log to a file.
     */
    void saveErrorLog(ManagedErrorLog errorLog, File errorStorageDirectory, String filename) throws JSONException, IOException {
        File errorLogFile = new File(errorStorageDirectory, filename + ErrorLogHelper.ERROR_LOG_FILE_EXTENSION);
        String errorLogString = mLogSerializer.serializeLog(errorLog);
        StorageHelper.InternalStorage.write(errorLogFile, errorLogString);
        MobileCenterLog.debug(Crashes.LOG_TAG, "Saved JSON content for ingestion into " + errorLogFile);
    }

    /**
     * Callback template method.
     */
    private interface CallbackProcessor {

        /**
         * @return true to delete the stored serialized throwable file.
         */
        boolean shouldDeleteThrowable();

        /**
         * Execute call back.
         *
         * @param report error report related to the callback.
         */
        void onCallBack(ErrorReport report);
    }

    /**
     * Listener for Wrapper SDK. Meant only for internal use by wrapper SDK developers.
     */
    @SuppressWarnings("WeakerAccess")
    public interface WrapperSdkListener {

        /**
         * Called when crash has been caught and saved.
         *
         * @param errorLog generated error log for the crash.
         */
        void onCrashCaptured(ManagedErrorLog errorLog);
    }

    /**
     * Default crashes listener class.
     */
    private static class DefaultCrashesListener extends AbstractCrashesListener {

    }

    /**
     * Class holding an error log and its corresponding error report.
     */
    private static class ErrorLogReport {

        private final ManagedErrorLog log;

        private final ErrorReport report;

        private ErrorLogReport(ManagedErrorLog log, ErrorReport report) {
            this.log = log;
            this.report = report;
        }
    }
}
