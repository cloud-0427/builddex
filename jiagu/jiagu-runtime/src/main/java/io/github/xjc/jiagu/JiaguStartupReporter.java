package io.github.xjc.jiagu;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell-side startup telemetry entry point.
 *
 * <p>The uploader is deliberately an interface instead of a subclass hook:
 * the consumer can use any transport and a throwing uploader cannot break
 * decryption or application startup.</p>
 */
public final class JiaguStartupReporter {
    public static final String UPLOADER_META_DATA =
            "io.github.xjc.jiagu.STARTUP_LOG_UPLOADER";

    private static final String TAG = "Jiagu_Startup";
    private static final String STATE_PREFERENCES = "jiagu_startup_state_v1";
    private static final String FIRST_MAIN_LAUNCH_ATTEMPTED = "first_main_launch_attempted";
    private static final int MAX_HISTORY = 4;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<JiaguStartupEvent> HISTORY = new ArrayDeque<>();
    private static final ExecutorService DISPATCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Jiagu-StartupLog");
        thread.setDaemon(true);
        return thread;
    });
    private static final JiaguStartupLogUploader DEFAULT_UPLOADER = (context, event) ->
            Log.i(TAG, event.toString());

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

    private JiaguStartupReporter() {
    }

    /**
     * Initializes the reporter and loads the uploader class declared in the
     * merged manifest. The class must have a public no-argument constructor.
     */
    public static void initialize(Context context) {
        if (initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            Context appContext = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();
            applicationContext = appContext;
            packageName = appContext.getPackageName();
            sessionId = UUID.randomUUID().toString();
            startupStartedAt = SystemClock.elapsedRealtime();
            readProcessState(appContext);
            firstLaunch = claimFirstMainLaunch(appContext);
            readVersion(appContext);
            JiaguStartupLogUploader configured = loadConfiguredUploader(appContext);
            if (configured != null && !manualUploaderSet) {
                uploader = configured;
            }
            initialized = true;
        }
    }

    /** Emits the event immediately before native startup begins decryption. */
    public static void beginDecryption(Context context) {
        initialize(context);
        emit(JiaguStartupEvent.Type.DECRYPT_STARTED, 0L);
    }

    /** Emits the event after the real Application.onCreate() returns. */
    public static void startupCompleted(Context context) {
        initialize(context);
        emit(JiaguStartupEvent.Type.STARTUP_COMPLETED,
                Math.max(0L, SystemClock.elapsedRealtime() - startupStartedAt));
    }

    /** Records the successful authorization path selected by the shell. */
    static void setAuthorizationSource(JiaguStartupEvent.AuthorizationSource source) {
        authorizationSource = source == null
                ? JiaguStartupEvent.AuthorizationSource.UNKNOWN : source;
    }

    /**
     * Installs or replaces the uploader. Calling this from the real
     * Application.onCreate() still replays events already emitted by the
     * shell, so the consumer does not lose DECRYPT_STARTED.
     *
     * <p>Pass null to restore the Android logcat fallback.</p>
     */
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

    private static void emit(JiaguStartupEvent.Type type, long elapsedMs) {
        JiaguStartupEvent event;
        JiaguStartupLogUploader target;
        synchronized (LOCK) {
            event = new JiaguStartupEvent(type, sessionId, packageName, versionName,
                    versionCode, System.currentTimeMillis(), elapsedMs, authorizationSource,
                    processName, mainProcess, firstLaunch);
            while (HISTORY.size() >= MAX_HISTORY) {
                HISTORY.removeFirst();
            }
            HISTORY.addLast(event);
            target = uploader;
        }
        dispatch(target, event);
    }

    private static void dispatch(JiaguStartupLogUploader target, JiaguStartupEvent event) {
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

    /**
     * Claims the first startup attempt before DECRYPT_STARTED is emitted. A
     * synchronous commit is intentional: a crash during decryption must not
     * cause the following launch to be labeled as the first attempt again.
     */
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
}
