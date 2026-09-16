package io.github.xjc.jiagu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shell-side, best-effort startup stage telemetry. */
public final class JiaguStartupReporter {
    public static final String UPLOADER_META_DATA =
            "io.github.xjc.jiagu.STARTUP_LOG_UPLOADER";

    private static final String TAG = "Jiagu_Startup";
    private static final String STATE_PREFERENCES = "jiagu_startup_state_v1";
    private static final String FIRST_MAIN_LAUNCH_ATTEMPTED = "first_main_launch_attempted";
    private static final int MAX_HISTORY = 32;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<JiaguStartupEvent> HISTORY = new ArrayDeque<>();
    private static final ArrayDeque<PendingEvent> PENDING = new ArrayDeque<>();
    private static final long[] STAGE_STARTED_AT =
            new long[JiaguStartupEvent.Stage.values().length];
    private static final ExecutorService DISPATCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Jiagu-StartupLog");
        thread.setDaemon(true);
        return thread;
    });
    private static final JiaguStartupLogUploader DEFAULT_UPLOADER = (context, event) ->
            Log.d(TAG, event.toString());

    private static volatile JiaguStartupLogUploader uploader = DEFAULT_UPLOADER;
    private static volatile boolean manualUploaderSet;
    private static volatile boolean initialized;
    private static volatile long startupStartedAt;
    private static volatile String sessionId;
    private static volatile String packageName;
    private static volatile String versionName;
    private static volatile long versionCode;
    private static volatile Context applicationContext;
    private static volatile String processName;
    private static volatile boolean mainProcess;
    private static volatile boolean firstLaunch;
    private static volatile JiaguStartupEvent.AuthorizationSource authorizationSource =
            JiaguStartupEvent.AuthorizationSource.UNKNOWN;
    private static volatile boolean firstActivityObserverInstalled;
    private static volatile boolean firstActivityResumed;
    private static volatile boolean firstFrameReported;
    private static volatile String firstActivityName;
    private static volatile long firstActivityResumedElapsedMs = -1L;

    private JiaguStartupReporter() {
    }

    /** Starts the session before System.loadLibrary("jiagu-core"). */
    public static void shellCoreLoadStarted() {
        synchronized (LOCK) {
            if (startupStartedAt == 0L) {
                startupStartedAt = SystemClock.elapsedRealtime();
            }
        }
        emit(JiaguStartupEvent.Stage.SHELL_CORE_LOAD, JiaguStartupEvent.Status.STARTED,
                null, 0L);
    }

    public static void shellCoreLoadSucceeded(long durationMs) {
        emit(JiaguStartupEvent.Stage.SHELL_CORE_LOAD, JiaguStartupEvent.Status.SUCCEEDED,
                "CORE_LIBRARY_LOADED", durationMs);
    }

    public static void shellCoreLoadFailed(Throwable error, long durationMs) {
        emit(JiaguStartupEvent.Stage.SHELL_CORE_LOAD, JiaguStartupEvent.Status.FAILED,
                "CORE_LIBRARY_LOAD_FAILED", durationMs);
        Log.e(TAG, "Cannot load jiagu-core", error);
    }

    /** Initializes metadata and loads the uploader declared in the merged manifest. */
    public static void initialize(Context context) {
        List<JiaguStartupEvent> replay;
        JiaguStartupLogUploader target;
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            Context appContext = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();
            applicationContext = appContext;
            packageName = appContext.getPackageName();
            sessionId = UUID.randomUUID().toString();
            if (startupStartedAt == 0L) {
                startupStartedAt = SystemClock.elapsedRealtime();
            }
            readProcessState(appContext);
            firstLaunch = claimFirstMainLaunch(appContext);
            readVersion(appContext);
            JiaguStartupLogUploader configured = loadConfiguredUploader(appContext);
            if (configured != null && !manualUploaderSet) {
                uploader = configured;
            }
            initialized = true;
            while (!PENDING.isEmpty()) {
                addToHistory(createEvent(PENDING.removeFirst()));
            }
            replay = new ArrayList<>(HISTORY);
            target = uploader;
        }
        for (JiaguStartupEvent event : replay) {
            dispatch(target, event);
        }
    }

    /** Compatibility entry point for the former DECRYPT_STARTED event. */
    public static void beginDecryption(Context context) {
        initialize(context);
        stageStarted(JiaguStartupEvent.Stage.SHELL_ATTACH);
    }

    /** Compatibility entry point for the former STARTUP_COMPLETED event. */
    public static void startupCompleted(Context context) {
        initialize(context);
        stageSucceeded(JiaguStartupEvent.Stage.REAL_APPLICATION_ON_CREATE,
                "REAL_APPLICATION_ON_CREATE_COMPLETED", 0L);
    }

    public static void stageStarted(JiaguStartupEvent.Stage stage) {
        synchronized (LOCK) {
            STAGE_STARTED_AT[stage.ordinal()] = SystemClock.elapsedRealtime();
        }
        emit(stage, JiaguStartupEvent.Status.STARTED, null, 0L);
    }

    public static void stageSucceeded(JiaguStartupEvent.Stage stage, String resultCode,
                                      long stageDurationMs) {
        emit(stage, JiaguStartupEvent.Status.SUCCEEDED, resultCode,
                resolveStageDuration(stage, stageDurationMs));
    }

    public static void stageFailed(JiaguStartupEvent.Stage stage, String resultCode,
                                   long stageDurationMs) {
        emit(stage, JiaguStartupEvent.Status.FAILED, resultCode,
                resolveStageDuration(stage, stageDurationMs));
    }

    public static void stageBlocked(JiaguStartupEvent.Stage stage, String resultCode,
                                    long stageDurationMs) {
        emit(stage, JiaguStartupEvent.Status.BLOCKED, resultCode,
                resolveStageDuration(stage, stageDurationMs));
    }

    /** JNI bridge; status uses {@link JiaguStartupEvent.Status#ordinal()}. */
    public static void nativeStageStarted(int stageId) {
        try {
            stageStarted(JiaguStartupEvent.Stage.fromId(stageId));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot report native stage start", error);
        }
    }

    /** JNI bridge; status uses {@link JiaguStartupEvent.Status#ordinal()}. */
    public static void nativeStageFinished(int stageId, int statusOrdinal,
                                           String resultCode, long stageDurationMs) {
        try {
            emit(JiaguStartupEvent.Stage.fromId(stageId),
                    JiaguStartupEvent.Status.fromOrdinal(statusOrdinal),
                    resultCode, stageDurationMs);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot report native stage result", error);
        }
    }

    /** Records the successful authorization path selected by the shell. */
    static void setAuthorizationSource(JiaguStartupEvent.AuthorizationSource source) {
        authorizationSource = source == null
                ? JiaguStartupEvent.AuthorizationSource.UNKNOWN : source;
    }

    /**
     * Called after the real Application is attached. It tracks the first Activity resumed
     * and the first frame of that Activity without touching business classes.
     */
    public static void observeFirstActivity(Application application) {
        if (application == null || firstActivityObserverInstalled) {
            return;
        }
        synchronized (LOCK) {
            if (firstActivityObserverInstalled) {
                return;
            }
            firstActivityObserverInstalled = true;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity, Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityPaused(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }

            @Override public void onActivityResumed(@NonNull Activity activity) {
                if (!claimFirstActivityResumed()) {
                    return;
                }
                firstActivityName = activity.getClass().getName();
                firstActivityResumedElapsedMs = elapsedSinceStart();
                stageStarted(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME);
                reportFirstFrame(activity);
            }
        });
    }

    private static boolean claimFirstActivityResumed() {
        synchronized (LOCK) {
            if (firstActivityResumed) {
                return false;
            }
            firstActivityResumed = true;
            return true;
        }
    }

    private static void reportFirstFrame(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            ViewTreeObserver observer = decorView.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    ViewTreeObserver current = decorView.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnPreDrawListener(this);
                    }
                    synchronized (LOCK) {
                        if (firstFrameReported) {
                            return true;
                        }
                        firstFrameReported = true;
                    }
                    stageSucceeded(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME,
                            "FIRST_FRAME_DRAWN", 0L);
                    return true;
                }
            });
        } catch (Throwable error) {
            stageFailed(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME,
                    "FIRST_FRAME_OBSERVER_FAILED", 0L);
            Log.w(TAG, "Cannot install first-frame observer", error);
        }
    }

    /** Installs or replaces the uploader and replays in-memory events to a replacement. */
    public static void setUploader(JiaguStartupLogUploader replacement) {
        List<JiaguStartupEvent> replay;
        synchronized (LOCK) {
            uploader = replacement == null ? DEFAULT_UPLOADER : replacement;
            manualUploaderSet = replacement != null;
            replay = replacement == null ? new ArrayList<>() : new ArrayList<>(HISTORY);
        }
        for (JiaguStartupEvent event : replay) {
            dispatch(replacement, event);
        }
    }

    private static void emit(JiaguStartupEvent.Stage stage, JiaguStartupEvent.Status status,
                             String resultCode, long stageDurationMs) {
        PendingEvent pending = new PendingEvent(stage, status, resultCode,
                System.currentTimeMillis(), Math.max(0L, stageDurationMs));
        JiaguStartupEvent event;
        JiaguStartupLogUploader target;
        synchronized (LOCK) {
            if (!initialized) {
                PENDING.addLast(pending);
                return;
            }
            event = createEvent(pending);
            addToHistory(event);
            target = uploader;
        }
        dispatch(target, event);
    }

    private static long resolveStageDuration(JiaguStartupEvent.Stage stage, long suppliedDurationMs) {
        if (suppliedDurationMs > 0L) {
            return suppliedDurationMs;
        }
        synchronized (LOCK) {
            long startedAt = STAGE_STARTED_AT[stage.ordinal()];
            return startedAt == 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
        }
    }

    private static JiaguStartupEvent createEvent(PendingEvent pending) {
        long elapsed = elapsedSinceStart();
        return new JiaguStartupEvent(pending.stage, pending.status, pending.resultCode,
                sessionId, packageName, versionName, versionCode, pending.occurredAtMillis,
                elapsed, pending.stageDurationMs, authorizationSource, processName,
                mainProcess, firstLaunch, firstActivityName, firstActivityResumedElapsedMs);
    }

    private static long elapsedSinceStart() {
        return startupStartedAt == 0L ? 0L :
                Math.max(0L, SystemClock.elapsedRealtime() - startupStartedAt);
    }

    private static void addToHistory(JiaguStartupEvent event) {
        while (HISTORY.size() >= MAX_HISTORY) {
            HISTORY.removeFirst();
        }
        HISTORY.addLast(event);
    }

    private static void dispatch(JiaguStartupLogUploader target, JiaguStartupEvent event) {
        if (target == null || applicationContext == null) {
            return;
        }
        DISPATCHER.execute(() -> {
            try {
                target.upload(applicationContext, event);
            } catch (Throwable error) {
                Log.e(TAG, "Startup log uploader failed", error);
            }
        });
    }

    private static JiaguStartupLogUploader loadConfiguredUploader(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            if (metadata == null) {
                return null;
            }
            String className = metadata.getString(UPLOADER_META_DATA);
            if (className == null || className.trim().isEmpty()) {
                return null;
            }
            Class<?> type = Class.forName(className.trim(), true,
                    JiaguStartupReporter.class.getClassLoader());
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof JiaguStartupLogUploader)) {
                throw new IllegalArgumentException(className
                        + " does not implement JiaguStartupLogUploader");
            }
            return (JiaguStartupLogUploader) instance;
        } catch (Throwable error) {
            Log.e(TAG, "Cannot initialize configured startup log uploader", error);
            return null;
        }
    }

    private static void readVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            versionName = info.versionName;
            versionCode = info.getLongVersionCode();
        } catch (Throwable error) {
            versionName = null;
            versionCode = 0L;
            Log.w(TAG, "Cannot read package version for startup log", error);
        }
    }

    private static void readProcessState(Context context) {
        processName = Application.getProcessName();
        String mainProcessName = context.getApplicationInfo().processName;
        mainProcess = mainProcessName != null && mainProcessName.equals(processName);
    }

    private static boolean claimFirstMainLaunch(Context context) {
        if (!mainProcess) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                STATE_PREFERENCES, Context.MODE_PRIVATE);
        if (preferences.getBoolean(FIRST_MAIN_LAUNCH_ATTEMPTED, false)) {
            return false;
        }
        boolean committed = preferences.edit()
                .putBoolean(FIRST_MAIN_LAUNCH_ATTEMPTED, true)
                .commit();
        if (!committed) {
            Log.w(TAG, "Cannot persist first-launch startup marker");
        }
        return committed;
    }

    private static final class PendingEvent {
        final JiaguStartupEvent.Stage stage;
        final JiaguStartupEvent.Status status;
        final String resultCode;
        final long occurredAtMillis;
        final long stageDurationMs;

        PendingEvent(JiaguStartupEvent.Stage stage, JiaguStartupEvent.Status status,
                     String resultCode, long occurredAtMillis, long stageDurationMs) {
            this.stage = stage;
            this.status = status;
            this.resultCode = resultCode;
            this.occurredAtMillis = occurredAtMillis;
            this.stageDurationMs = stageDurationMs;
        }
    }
}
