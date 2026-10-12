package io.github.xjc.jiagu.local;

import android.app.*;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.util.*;
import java.util.concurrent.*;

/** Best-effort memory-only telemetry. No disk IO or delivery waits on the startup thread. */
public final class LocalEventReporter {
    private static final String TAG = "JiaguLocalEvents";
    private static final Object LOCK = new Object();
    private static final String SESSION = UUID.randomUUID().toString();
    private static final long START = SystemClock.elapsedRealtime();
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static final Set<LocalStartupStage> TERMINALS = EnumSet.noneOf(LocalStartupStage.class);
    private static final Set<LocalStartupStage> STARTED = EnumSet.noneOf(LocalStartupStage.class);
    private static final Set<String> CUSTOM_TERMINALS = new HashSet<>();
    private static final Set<String> CUSTOM_STARTED = new HashSet<>();
    private static String pkg = "", process = "";
    private static long version;
    private static boolean initialized, resumed;
    private static Context context;
    private static LocalEventUploader uploader;
    private static ScheduledThreadPoolExecutor worker;
    private static long dropped;
    private LocalEventReporter() {}

    static void metadata(String name, long code) {
        synchronized (LOCK) { pkg = name; version = code; }
    }
    /** Compatibility adapter; built-in startup stages use the typed overload. */
    public static void emit(String stage, String status, String code, long duration) {
        try {
            if (stage == null || status == null || code == null || !stage.matches("[A-Z_]{1,64}")
                    || !code.matches("[A-Z_0-9]{0,96}")) return;
            LocalStartupStatus.fromWireName(status);
            synchronized (LOCK) {
                capture(new LocalStartupEvent(SESSION, UUID.randomUUID().toString(), stage, status, code,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime() - START, Math.max(0, duration), "", 0, ""));
            }
        } catch (Throwable error) { Log.w(TAG, "EVENT_CAPTURE_FAILED"); }
    }
    public static void emit(LocalStartupStage stage, LocalStartupStatus status, LocalStartupResultCode code, long duration) {
        try {
            if (stage == null || status == null || code == null) return;
            synchronized (LOCK) {
                capture(new LocalStartupEvent(SESSION, UUID.randomUUID().toString(), stage, status, code,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime() - START, Math.max(0, duration), "", 0, ""));
            }
        } catch (Throwable error) { Log.w(TAG, "EVENT_CAPTURE_FAILED"); }
    }
    private static void capture(LocalStartupEvent event) {
        synchronized (LOCK) {
            LocalStartupStage stage = event.getStage();
            if (event.getStatus() == LocalStartupStatus.STARTED) {
                if (STARTED.size() + CUSTOM_STARTED.size() >= 64) return;
                if (stage == null ? !CUSTOM_STARTED.add(event.stage) : !STARTED.add(stage)) return;
            } else {
                if (TERMINALS.size() + CUSTOM_TERMINALS.size() >= 64) return;
                if (stage == null ? !CUSTOM_TERMINALS.add(event.stage) : !TERMINALS.add(stage)) return;
            }
            if (QUEUE.size() == 64) {
                Pending victim = null;
                for (Pending p : QUEUE) if (p.event.getStatus() != LocalStartupStatus.FAILED) { victim = p; break; }
                if (victim == null) { dropped++; return; }
                QUEUE.remove(victim); dropped++;
            }
            QUEUE.addLast(new Pending(event));
            if (worker != null) worker.execute(LocalEventReporter::drain);
        }
    }

    static void initialize(Context value, String uploaderName) {
        try {
            synchronized (LOCK) {
                if (initialized) return;
                initialized = true;
                context = value.getApplicationContext();
                if (!(context instanceof Application)) return;
                process = Application.getProcessName();
                if (!context.getApplicationInfo().processName.equals(process)) return;
                pkg = context.getPackageName();
                ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    public void onActivityResumed(Activity a) {
                        synchronized (LOCK) { if (resumed) return; resumed = true; }
                        emit(LocalStartupStage.FIRST_ACTIVITY_RESUMED, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.ACTIVITY_RESUMED, 0);
                    }
                    public void onActivityCreated(Activity a, Bundle b) {}
                    public void onActivityStarted(Activity a) {}
                    public void onActivityPaused(Activity a) {}
                    public void onActivityStopped(Activity a) {}
                    public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    public void onActivityDestroyed(Activity a) {}
                });
                worker = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r, "Jiagu-LocalEvents"); t.setDaemon(true); return t;
                });
                worker.setRemoveOnCancelPolicy(true);
                worker.execute(() -> {
                    try {
                        uploader = (LocalEventUploader) Class.forName(uploaderName, true, context.getClassLoader())
                                .getDeclaredConstructor().newInstance();
                        emit(LocalStartupStage.EVENT_REPORTER_READY, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.REPORTER_READY, 0);
                        drain();
                    } catch (Throwable error) { Log.w(TAG, "EVENT_UPLOADER_INITIALIZATION_FAILED"); }
                });
            }
        } catch (Throwable error) { Log.w(TAG, "EVENT_INITIALIZATION_FAILED"); }
    }

    private static void drain() {
        if (uploader == null) return;
        Pending pending;
        synchronized (LOCK) { pending = QUEUE.pollFirst(); }
        if (pending == null) return;
        LocalStartupEvent e = pending.event;
        LocalStartupEvent delivery = new LocalStartupEvent(e.sessionId, e.eventId, e.stage, e.status, e.resultCode,
                e.occurredAtMillis, e.elapsedSinceStartMs, e.stageDurationMs, pkg, version, process);
        try {

            if (delivery.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) return;
            uploader.upload(context, delivery);
            Log.i(TAG, "EVENT:" + delivery.toJson());

        } catch (Exception error) {
            boolean retry = error instanceof LocalUploadException && ((LocalUploadException) error).retryable;
            if (retry && ++pending.attempt < 3) {
                worker.schedule(() -> {
                    synchronized (LOCK) {
                        if (QUEUE.size() < 64) QUEUE.addLast(pending); else dropped++;
                    }
                    drain();
                }, pending.attempt == 1 ? 1 : 5, TimeUnit.SECONDS);
            }
            Log.w(TAG, "EVENT_FAILED:" + delivery);
        } catch (Throwable error) { Log.w(TAG, "EVENT_UPLOADER_FAILED"); }
        finally {
            synchronized (LOCK) { if (!QUEUE.isEmpty()) worker.execute(LocalEventReporter::drain); }
        }
    }
    private static final class Pending {
        final LocalStartupEvent event; int attempt;
        Pending(LocalStartupEvent event) { this.event = event; }
    }
}
