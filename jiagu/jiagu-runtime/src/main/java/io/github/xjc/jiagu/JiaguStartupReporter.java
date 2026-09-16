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

/** Shell-side telemetry. Delivery is bounded, asynchronous, and in-memory only. */
public final class JiaguStartupReporter {
    public static final String UPLOADER_META_DATA = "io.github.xjc.jiagu.STARTUP_LOG_UPLOADER";
    private static final String TAG = "Jiagu_Startup", PREFS = "jiagu_startup_state_v1",
            FIRST_COMPLETED = "first_main_launch_completed_v2",
            STARTUP_INSTANCE_ID = "startup_instance_id_v1";
    private static final int MAX_QUEUE = 64, MAX_ATTEMPTS = 2;
    private static final long FIRST_FRAME_TIMEOUT_MS = 10_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> EARLY = new ArrayDeque<>(), QUEUE = new ArrayDeque<>();
    private static final boolean[] TERMINAL = new boolean[JiaguStartupEvent.Stage.values().length];
    private static final long[] STARTED = new long[JiaguStartupEvent.Stage.values().length];
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Jiagu-StartupLog"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final JiaguStartupLogUploader DEFAULT = (c, e) -> Log.d(TAG, e.toString());
    private static volatile JiaguStartupLogUploader uploader = DEFAULT;
    private static volatile boolean initialized, manualUploader, uploadsEnabled, drainScheduled;
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
            firstLaunch = isFirstLaunch(appContext); readVersion(appContext);
            JiaguStartupLogUploader configured = loadUploader(appContext);
            if (configured != null && !manualUploader) uploader = configured;
            initialized = true;
            while (!EARLY.isEmpty()) {
                JiaguStartupEvent early = event(EARLY.removeFirst());
                if (mainProcess && firstLaunch) enqueueLocked(early, 0);
            }
        }
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
            if ((s == JiaguStartupEvent.Stage.REAL_APPLICATION_ON_CREATE && status == JiaguStartupEvent.Status.SUCCEEDED)
                    || s == JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME) {
                uploadsEnabled = true; scheduleDrain(0);
                if (s == JiaguStartupEvent.Stage.REAL_APPLICATION_ON_CREATE) scheduleDrain(2_000);
            }
            if (s == JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME
                    && status == JiaguStartupEvent.Status.SUCCEEDED) markFirstLaunchCompleted(appContext);
        }
    }
    private static void enqueueLocked(JiaguStartupEvent e, int attempts) {
        if (QUEUE.size() >= MAX_QUEUE) {
            Pending drop = null;
            for (Pending q : QUEUE) if (q.event.getStatus() == JiaguStartupEvent.Status.SUCCEEDED) { drop = q; break; }
            if (drop != null) QUEUE.remove(drop);
            else if (e.getStatus() == JiaguStartupEvent.Status.SUCCEEDED) return;
            else QUEUE.removeFirst();
        }
        QUEUE.addLast(new Pending(e, attempts));
    }
    private static void scheduleDrain(long delay) {
        synchronized (LOCK) { if (!uploadsEnabled || drainScheduled || QUEUE.isEmpty()) return; drainScheduled = true; }
        WORKER.schedule(JiaguStartupReporter::drain, delay, TimeUnit.MILLISECONDS);
    }
    private static void drain() {
        Pending p; JiaguStartupLogUploader target;
        synchronized (LOCK) { drainScheduled = false; if (!uploadsEnabled || QUEUE.isEmpty()) return; p = QUEUE.removeFirst(); target = uploader; }
        long retry = 0;
        try { target.upload(appContext, p.event); }
        catch (JiaguStartupUploadException e) {
            if (e.isRetryable() && p.attempts + 1 < MAX_ATTEMPTS) retry = Math.max(e.getRetryAfterMillis(), backoff(p.attempts));
            else Log.w(TAG, "Dropping startup upload: " + e.getMessage());
        } catch (Throwable e) {
            if (p.attempts + 1 < MAX_ATTEMPTS) retry = backoff(p.attempts); else Log.w(TAG, "Dropping startup upload", e);
        }
        if (retry > 0) synchronized (LOCK) { enqueueLocked(p.event, p.attempts + 1); }
        scheduleDrain(retry);
        if (retry == 0) scheduleDrain(0);
    }
    private static long backoff(int n) { return n == 0 ? 1_000 : n == 1 ? 3_000 : 8_000; }
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
    private static void markFirstLaunchCompleted(Context c) {
        if (mainProcess && firstLaunch && c != null && !c.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit().putBoolean(FIRST_COMPLETED, true).commit()) {
            Log.w(TAG, "Cannot persist first-launch completion");
        }
    }
    private static final class Pending {
        final JiaguStartupEvent event; final JiaguStartupEvent.Stage stage; final JiaguStartupEvent.Status status; final String code; final long occurred, elapsed, duration; final int attempts;
        Pending(JiaguStartupEvent.Stage s, JiaguStartupEvent.Status t, String c, long o, long e, long d) { event=null;stage=s;status=t;code=c;occurred=o;elapsed=e;duration=d;attempts=0; }
        Pending(JiaguStartupEvent e, int a) { event=e;stage=null;status=null;code=null;occurred=elapsed=duration=0;attempts=a; }
    }
}
