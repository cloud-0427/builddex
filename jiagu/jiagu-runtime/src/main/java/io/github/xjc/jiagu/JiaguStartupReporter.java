package io.github.xjc.jiagu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

/** Shell-side telemetry. Events are committed before asynchronous delivery and replayed on restart. */
public final class JiaguStartupReporter {
    public static final String UPLOADER_META_DATA = "io.github.xjc.jiagu.STARTUP_LOG_UPLOADER";
    private static final String TAG = "Jiagu_Startup", PREFS = "jiagu_startup_state_v1",
            FIRST_COMPLETED = "first_main_launch_completed_v2",
            STARTUP_INSTANCE_ID = "startup_instance_id_v1";
    private static final long FIRST_FRAME_TIMEOUT_MS = 10_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> EARLY = new ArrayDeque<>();
    private static final boolean[] TERMINAL = new boolean[JiaguStartupEvent.Stage.values().length];
    private static final long[] STARTED = new long[JiaguStartupEvent.Stage.values().length];
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Jiagu-StartupLog"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final JiaguStartupLogUploader DEFAULT = (c, e) -> Log.d(TAG, e.toString());
    private static volatile JiaguStartupLogUploader uploader = DEFAULT;
    private static volatile boolean initialized, manualUploader;
    private static StartupEventStore store;
    private static final StartupAcknowledgement ACKNOWLEDGEMENT = new StartupAcknowledgement();
    private static ScheduledFuture<?> drainTask;
    private static long drainAt = Long.MAX_VALUE;
    private static volatile long startupAt, versionCode, firstActivityResumedMs = -1L;
    private static volatile String sessionId, startupInstanceId, packageName, versionName, processName, firstActivityName;
    private static volatile boolean mainProcess, firstLaunch, observerInstalled, firstActivityResumed;
    private static volatile Context appContext;
    private static volatile JiaguStartupEvent.AuthorizationSource authorization =
            JiaguStartupEvent.AuthorizationSource.UNKNOWN;
    private JiaguStartupReporter() { }

    public static void startupAttemptStarted() { ensureStart(); emit(JiaguStartupEvent.Stage.STARTUP_ATTEMPT, JiaguStartupEvent.Status.STARTED, null, 0); }
    public static void shellCoreLoadSucceeded(long ms) { emit(JiaguStartupEvent.Stage.SHELL_CORE_LOAD, JiaguStartupEvent.Status.SUCCEEDED, "CORE_LIBRARY_LOADED", ms); }
    public static void shellCoreLoadFailed(Throwable e, long ms) { emit(JiaguStartupEvent.Stage.SHELL_CORE_LOAD, JiaguStartupEvent.Status.FAILED, "CORE_LIBRARY_LOAD_FAILED", ms); Log.e(TAG, "Cannot load jiagu-core", e); }
    public static void initialize(Context context) {
        synchronized (LOCK) {
            if (initialized) return;
            appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
            packageName = appContext.getPackageName(); sessionId = UUID.randomUUID().toString(); ensureStart();
            processName = Application.getProcessName();
            mainProcess = appContext.getApplicationInfo().processName.equals(processName);
            startupInstanceId = readOrCreateStartupInstanceId(appContext);
            if (mainProcess) {
                try { store = new StartupEventStore(appContext); }
                catch (Throwable e) { Log.e(TAG, "Cannot open startup outbox", e); }
            }
            firstLaunch = isFirstLaunch(appContext);
            if (store != null) {
                try { firstLaunch = firstLaunch && !store.completed(); }
                catch (Throwable e) { Log.e(TAG, "Cannot read startup completion", e); }
            }
            readVersion(appContext);
            JiaguStartupLogUploader configured = loadUploader(appContext);
            if (configured != null && !manualUploader) uploader = configured;
            initialized = true;
            while (!EARLY.isEmpty()) {
                JiaguStartupEvent early = event(EARLY.removeFirst());
                if (mainProcess && firstLaunch) enqueueLocked(early, 0);
            }
        }
        scheduleDrain(0);
    }
    public static void beginDecryption(Context c) { initialize(c); stageStarted(JiaguStartupEvent.Stage.SHELL_ATTACH); }
    public static void startupCompleted(Context c) { initialize(c); stageSucceeded(JiaguStartupEvent.Stage.REAL_APPLICATION_ON_CREATE, "REAL_APPLICATION_ON_CREATE_COMPLETED", 0); }
    public static void stageStarted(JiaguStartupEvent.Stage s) { synchronized (LOCK) { STARTED[s.ordinal()] = SystemClock.elapsedRealtime(); } }
    public static void stageSucceeded(JiaguStartupEvent.Stage s, String c, long d) { emit(s, JiaguStartupEvent.Status.SUCCEEDED, c, duration(s, d)); }
    public static void stageFailed(JiaguStartupEvent.Stage s, String c, long d) { emit(s, JiaguStartupEvent.Status.FAILED, c, duration(s, d)); }
    public static void stageBlocked(JiaguStartupEvent.Stage s, String c, long d) { emit(s, JiaguStartupEvent.Status.BLOCKED, c, duration(s, d)); }
    public static void nativeStageFinished(int id, int status, String c, long d) {
        try { emit(JiaguStartupEvent.Stage.fromId(id), JiaguStartupEvent.Status.fromOrdinal(status), c, d); }
        catch (Throwable e) { Log.w(TAG, "Cannot report native stage", e); }
    }
    static void setAuthorizationSource(JiaguStartupEvent.AuthorizationSource source) {
        authorization = source == null ? JiaguStartupEvent.AuthorizationSource.UNKNOWN : source;
    }
    public static void setUploader(JiaguStartupLogUploader replacement) {
        synchronized (LOCK) { uploader = replacement == null ? DEFAULT : replacement; manualUploader = replacement != null; }
        scheduleDrain(0);
    }
    public static void observeFirstActivity(Application app) {
        synchronized (LOCK) { if (app == null || observerInstalled) return; observerInstalled = true; }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity a, Bundle s) { }
            @Override public void onActivityStarted(@NonNull Activity a) { }
            @Override public void onActivityPaused(@NonNull Activity a) { }
            @Override public void onActivityStopped(@NonNull Activity a) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle s) { }
            @Override public void onActivityDestroyed(@NonNull Activity a) { }
            @Override public void onActivityResumed(@NonNull Activity a) {
                synchronized (LOCK) { if (firstActivityResumed) return; firstActivityResumed = true; }
                firstActivityName = Integer.toHexString(a.getClass().getName().hashCode());
                firstActivityResumedMs = elapsed(); stageStarted(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME);
                Runnable timeout = () -> stageFailed(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME, "FIRST_FRAME_TIMEOUT", 0);
                MAIN.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS);
                try {
                    View decor = a.getWindow().getDecorView();
                    decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            ViewTreeObserver o = decor.getViewTreeObserver(); if (o.isAlive()) o.removeOnPreDrawListener(this);
                            MAIN.removeCallbacks(timeout);
                            stageSucceeded(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME, "FIRST_FRAME_DRAWN", 0);
                            return true;
                        }
                    });
                } catch (Throwable e) {
                    MAIN.removeCallbacks(timeout);
                    stageFailed(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME, "FIRST_FRAME_OBSERVER_FAILED", 0);
                }
            }
        });
    }
    private static void emit(JiaguStartupEvent.Stage s, JiaguStartupEvent.Status status, String code, long duration) {
        Pending p = new Pending(s, status, code, System.currentTimeMillis(), elapsed(), Math.max(0, duration));
        synchronized (LOCK) {
            if (s != JiaguStartupEvent.Stage.STARTUP_ATTEMPT && status != JiaguStartupEvent.Status.STARTED) {
                if (TERMINAL[s.ordinal()]) { Log.w(TAG, "Ignoring duplicate terminal stage: " + s); return; }
                TERMINAL[s.ordinal()] = true;
            }
            if (!initialized) { EARLY.addLast(p); return; }
            if (mainProcess && firstLaunch) enqueueLocked(event(p), 0);
            scheduleDrain(0);
        }
    }
    private static void enqueueLocked(JiaguStartupEvent e, int attempts) {
        try {
            if (store == null) throw new IllegalStateException("Startup outbox unavailable");
            store.append(e);
        } catch (Throwable error) {
            // Never acknowledge or mark first launch completed when persistence failed.
            Log.e(TAG, "Cannot persist startup event " + e.getTelemetryEventId(), error);
        }
        if (uploader == DEFAULT) Log.d(TAG, e.toString());
    }
    private static void scheduleDrain(long delay) {
        synchronized (LOCK) {
            if (!initialized || !mainProcess || store == null || uploader == DEFAULT) return;
            long at = SystemClock.elapsedRealtime() + Math.max(0, delay);
            if (drainTask != null && drainAt <= at) return;
            if (drainTask != null) drainTask.cancel(false);
            drainAt = at;
            drainTask = WORKER.schedule(JiaguStartupReporter::drain, Math.max(0, delay), TimeUnit.MILLISECONDS);
        }
    }
    private static void drain() {
        JiaguStartupLogUploader target;
        synchronized (LOCK) {
            drainTask = null; drainAt = Long.MAX_VALUE; target = uploader;
            if (target == DEFAULT) return;
        }
        try {
            // Finish a previously successful upload's local deletion before selecting
            // another row. A disk failure must not route the same event back to HTTP.
            ACKNOWLEDGEMENT.flush(store::acknowledge);
            StartupEventStore.Entry row = store.next(System.currentTimeMillis());
            if (row != null) {
                JiaguStartupEvent event;
                try { event = StartupEventCodec.decode(row.payload); }
                catch (Exception invalid) {
                    store.quarantine(row.id);
                    Log.e(TAG, "Quarantined invalid startup event " + row.id, invalid);
                    scheduleDrain(0); return;
                }
                boolean uploaded = false;
                try {
                    target.upload(appContext, event);
                    uploaded = true;
                } catch (JiaguStartupUploadException error) {
                    if (error.isRetryable()) retry(row, error.getRetryAfterMillis());
                    else {
                        store.quarantine(row.id);
                        Log.w(TAG, "Quarantined rejected startup event " + row.id, error);
                    }
                } catch (Throwable error) {
                    retry(row, 0);
                    Log.w(TAG, "Startup upload deferred " + row.id, error);
                }
                if (uploaded) {
                    ACKNOWLEDGEMENT.uploaded(row.id);
                    // No network-retry catch around local acknowledgement.
                    ACKNOWLEDGEMENT.flush(store::acknowledge);
                }
            }
            long delay = store.nextDelay(System.currentTimeMillis());
            if (delay >= 0) scheduleDrain(delay);
        } catch (Throwable error) {
            Log.e(TAG, "Startup outbox dispatch failed", error);
            scheduleDrain(60_000);
        }
    }
    private static void retry(StartupEventStore.Entry row, long serverDelay) {
        long backoff = Math.min(900_000L, 1_000L << Math.min(row.attempts, 20));
        long delay = Math.max(Math.min(serverDelay, TimeUnit.DAYS.toMillis(7)),
                backoff + (long) (Math.random() * backoff / 4));
        store.retry(row, System.currentTimeMillis() + delay);
    }
    private static long duration(JiaguStartupEvent.Stage s, long d) { if (d > 0) return d; synchronized (LOCK) { return STARTED[s.ordinal()] == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - STARTED[s.ordinal()]); } }
    private static JiaguStartupEvent event(Pending p) { return new JiaguStartupEvent(p.stage, p.status, p.code, sessionId, startupInstanceId, packageName, versionName, versionCode, p.occurred, p.elapsed, p.duration, authorization, processName, mainProcess, firstLaunch, firstActivityName, firstActivityResumedMs); }
    private static void ensureStart() { synchronized (LOCK) { if (startupAt == 0) startupAt = SystemClock.elapsedRealtime(); } }
    private static long elapsed() { return startupAt == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - startupAt); }
    private static JiaguStartupLogUploader loadUploader(Context c) {
        try {
            ApplicationInfo info = c.getPackageManager().getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
            String n = info.metaData == null ? null : info.metaData.getString(UPLOADER_META_DATA);
            if (n == null || n.trim().isEmpty()) return null;
            Object o = Class.forName(n.trim(), true, JiaguStartupReporter.class.getClassLoader()).getDeclaredConstructor().newInstance();
            if (!(o instanceof JiaguStartupLogUploader)) throw new IllegalArgumentException("Invalid uploader");
            return (JiaguStartupLogUploader) o;
        } catch (Throwable e) { Log.e(TAG, "Cannot initialize uploader", e); return null; }
    }
    private static void readVersion(Context c) { try { PackageInfo i = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); versionName = i.versionName; versionCode = i.getLongVersionCode(); } catch (Throwable e) { Log.w(TAG, "Cannot read package version", e); } }
    private static boolean isFirstLaunch(Context c) {
        return mainProcess && !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(FIRST_COMPLETED, false);
    }
    private static String readOrCreateStartupInstanceId(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = p.getString(STARTUP_INSTANCE_ID, null);
        if (value != null && !value.trim().isEmpty()) return value;
        String generated = UUID.randomUUID().toString();
        p.edit().putString(STARTUP_INSTANCE_ID, generated).commit();
        return generated;
    }
    private static final class Pending {
        final JiaguStartupEvent.Stage stage; final JiaguStartupEvent.Status status; final String code; final long occurred, elapsed, duration;
        Pending(JiaguStartupEvent.Stage s, JiaguStartupEvent.Status t, String c, long o, long e, long d) { stage=s;status=t;code=c;occurred=o;elapsed=e;duration=d; }
    }
}
