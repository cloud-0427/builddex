package io.github.xjc.jiagu.local;

import android.app.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.*;
import android.util.Log;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Persist before delivery; telemetry failures never change the application outcome. */
public final class LocalEventReporter {
    private static final String TAG = "JiaguLocalEvents";
    private static final Object LOCK = new Object();
    private static final String SESSION = UUID.randomUUID().toString();
    private static final long START = SystemClock.elapsedRealtime();
    private static final Set<String> CAPTURED = new HashSet<>();
    private static final Map<String, String> STATUSES = new HashMap<>();
    private static final Map<String, LocalStartupEvent> RETAINED = new HashMap<>();
    private static final ArrayDeque<LocalStartupEvent> FALLBACK = new ArrayDeque<>();
    private static String pkg = "", process = "", build = "";
    private static String mainProcess = "";
    private static long version, sequence, persistFailures;
    private static boolean enabled, initialized, resumed;
    private static Context context;
    private static LocalEventJournal journal;
    private static LocalEventStore store;
    private static LocalEventUploader uploader;
    private static ScheduledThreadPoolExecutor worker;
    private static ScheduledFuture<?> scheduled;
    private static File directory;
    private static long maintainedAt;
    private LocalEventReporter() { }

    static void bootstrap(ApplicationInfo info) {
        synchronized (LOCK) {
            enabled = LocalBuildInfo.enabled();
            if (!enabled || journal != null) return;
            pkg = LocalBuildInfo.packageName(); version = LocalBuildInfo.versionCode(); build = LocalBuildInfo.buildId();
            process = Application.getProcessName(); mainProcess = info.processName;
            directory = new File(info.dataDir, "no_backup/jiagu-startup-v1");
            try {
                journal = new LocalEventJournal(directory, SESSION);
                journal.append(event(LocalStartupInternalEvent.SESSION_HEADER.getWireName(), LocalStartupStatus.STARTED, LocalStartupResultCode.NONE.getWireName(), 0, LocalStartupRecordType.CHECKPOINT).toJson().toString());
            }
            catch (Throwable failure) { persistFailures++; Log.w(TAG, "EARLY_STORE_FAILED"); }
        }
    }
    static void metadata(String name, long code) {
        synchronized (LOCK) {
            if (pkg.isEmpty()) pkg = name;
            if (version == 0) version = code;
        }
    }
    public static void emit(String stage, String status, String code, long duration) {
        try {
            synchronized (LOCK) {
                if (!enabled || stage == null || status == null || code == null || !stage.matches("[A-Z_]{1,64}")
                        || !code.matches("[A-Z_0-9]{0,96}")) return;
                LocalStartupStatus.fromWireName(status);
                String key = stage + (status.equals(LocalStartupStatus.STARTED.getWireName()) ? ":START" : ":END");
                if (CAPTURED.contains(key)) {
                    if (!status.equals(STATUSES.get(key)) && CAPTURED.add(key + ":CONFLICT"))
                        diagnostic(LocalStartupDiagnosticCode.TERMINAL_CONFLICT, SESSION);
                    return;
                }
                if (CAPTURED.size() >= 128) return;
                LocalStartupEvent e = RETAINED.get(key);
                if (e == null) { e = event(stage, LocalStartupStatus.fromWireName(status), code, duration, LocalStartupRecordType.STAGE); RETAINED.put(key, e); }
                if (persist(e)) { CAPTURED.add(key); STATUSES.put(key, e.status); RETAINED.remove(key); }
                wake(0);
            }
        } catch (Throwable error) { Log.w(TAG, "EVENT_CAPTURE_FAILED"); }
    }
    public static void emit(LocalStartupStage stage, LocalStartupStatus status, LocalStartupResultCode code, long duration) {
        if (stage != null && status != null && code != null) emit(stage.getWireName(), status.getWireName(), code.getWireName(), duration);
    }
    static void checkpoint(LocalStartupStage stage, LocalStartupCheckpoint step) {
        try {
            synchronized (LOCK) {
                if (!enabled) return;
                LocalStartupEvent event = event(stage.getWireName(), LocalStartupStatus.STARTED, LocalStartupResultCode.NONE.getWireName(), 0, LocalStartupRecordType.CHECKPOINT);
                event.checkpoint = step.getWireName(); persist(event);
                Log.d(TAG, "CHECKPOINT=" + stage + ":" + step);
            }
        } catch (Throwable ignored) { Log.w(TAG, "CHECKPOINT_FAILED"); }
    }
    /** Explicit optional integration from the real Application; no framework hooks. */
    public static void applicationCheckpoint(LocalStartupDiagnosticCode checkpoint) {
        if (checkpoint != null && checkpoint.getWireName().startsWith("APPLICATION_")) diagnostic(checkpoint, "");
    }
    /** Compatibility adapter for existing host integrations; prefer the typed overload. */
    public static void applicationCheckpoint(String step) {
        if (step != null && step.matches("[A-Z_]{1,48}")) diagnostic("APPLICATION_" + step, "");
    }
    private static LocalStartupEvent event(String stage, LocalStartupStatus status, String code, long duration, LocalStartupRecordType kind) {
        LocalStartupEvent e = new LocalStartupEvent(SESSION, UUID.randomUUID().toString(), stage, status.getWireName(), code,
                System.currentTimeMillis(), SystemClock.elapsedRealtime() - START, Math.max(0, duration), pkg, version, process);
        e.sequence = ++sequence; e.recordType = kind.getWireName(); e.buildId = build; e.versionName = LocalBuildInfo.versionName();
        e.mainProcessName = mainProcess == null ? pkg : mainProcess; return e;
    }
    private static boolean persist(LocalStartupEvent e) {
        try {
            if (journal == null) throw new IllegalStateException("NO_JOURNAL");
            journal.append(e.toJson().toString()); return true;
        } catch (Throwable failure) {
            persistFailures++;
            if (FALLBACK.size() < 64 && !FALLBACK.contains(e)) FALLBACK.addLast(e);
            Log.w(TAG, "EVENT_PERSIST_FAILED"); return false;
        }
    }
    private static void diagnostic(LocalStartupDiagnosticCode code, String related) {
        diagnostic(code.getWireName(), related);
    }
    private static void diagnostic(String code, String related) {
        try {
            synchronized (LOCK) {
                if (!enabled) return;
                LocalStartupEvent e = event(LocalStartupInternalEvent.STARTUP_DIAGNOSTIC.getWireName(), LocalStartupStatus.SUCCEEDED, code, 0, LocalStartupRecordType.DIAGNOSTIC);
                e.relatedSessionId = related; persist(e); wake(0);
            }
        } catch (Throwable ignored) { Log.w(TAG, "DIAGNOSTIC_FAILED"); }
    }
    static void initializationFailed(LocalStartupDiagnosticCode code) { diagnostic(code, ""); }
    static void initialize(Context value, String uploaderName) {
        try {
            synchronized (LOCK) {
                if (initialized) return;
                bootstrap(value.getApplicationInfo());
                if (!enabled) return;
                context = value.getApplicationContext();
                if (!(context instanceof Application)) { diagnostic(LocalStartupDiagnosticCode.CONTEXT_NOT_APPLICATION, ""); return; }
                if (!context.getApplicationInfo().processName.equals(process)) return;
                if (!directory.getCanonicalFile().equals(new File(context.getNoBackupFilesDir(), "jiagu-startup-v1").getCanonicalFile()))
                    throw new IllegalStateException("STORAGE_DOMAIN_MISMATCH");
                worker = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r, "Jiagu-LocalEvents"); t.setDaemon(true); return t; });
                worker.setRemoveOnCancelPolicy(true);
                initialized = true;
                try { ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    public void onActivityResumed(Activity a) {
                        synchronized (LOCK) { if (resumed) return; resumed = true; }
                        emit(LocalStartupStage.FIRST_ACTIVITY_RESUMED, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.ACTIVITY_RESUMED, 0);
                    }
                    public void onActivityCreated(Activity a, Bundle b) { }
                    public void onActivityStarted(Activity a) { }
                    public void onActivityPaused(Activity a) { }
                    public void onActivityStopped(Activity a) { }
                    public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    public void onActivityDestroyed(Activity a) { }
                }); } catch (Throwable failure) { diagnostic(LocalStartupDiagnosticCode.ACTIVITY_OBSERVER_INSTALL_FAILED, ""); }
                worker.execute(() -> open(uploaderName));
            }
        } catch (Throwable error) { diagnostic(LocalStartupDiagnosticCode.EVENT_INITIALIZATION_FAILED, ""); Log.w(TAG, "EVENT_INITIALIZATION_FAILED"); }
    }
    private static void open(String uploaderName) {
        try {
            synchronized (LOCK) {
                if (store == null) store = new LocalEventStore(directory);
                store.target(build + ":" + uploaderName);
                if (journal == null) journal = new LocalEventJournal(directory, SESSION);
                flushFallback();
                if (persistFailures > 0) { diagnostic(LocalStartupDiagnosticCode.PERSISTENCE_DEGRADED, ""); persistFailures = 0; }
            }
            uploader = (LocalEventUploader) Class.forName(uploaderName, true, context.getClassLoader()).getDeclaredConstructor().newInstance();
            emit(LocalStartupStage.EVENT_REPORTER_READY, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.REPORTER_READY, 0);
            drain();
        } catch (Throwable error) {
            diagnostic(LocalStartupDiagnosticCode.EVENT_UPLOADER_INITIALIZATION_FAILED, "");
            Log.w(TAG, "EVENT_UPLOADER_INITIALIZATION_FAILED");
            worker.schedule(() -> open(uploaderName), 60, TimeUnit.SECONDS);
        }
    }
    private static void wake(long delay) {
        synchronized (LOCK) {
            if (worker == null || store == null || uploader == null) return;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = worker.schedule(LocalEventReporter::drain, delay, TimeUnit.MILLISECONDS);
        }
    }
    private static void drain() {
        synchronized (LOCK) { scheduled = null; }
        if (store == null || uploader == null) return;
        try {
            synchronized (LOCK) {
                flushFallback();
                long previousSequence = sequence;
                LocalStartupEvent discovery = event(LocalStartupInternalEvent.STARTUP_DIAGNOSTIC.getWireName(), LocalStartupStatus.SUCCEEDED, LocalStartupDiagnosticCode.RECOVERY.getWireName(), 0, LocalStartupRecordType.DIAGNOSTIC);
                discovery.sequence = previousSequence;
                try { store.importJournals(discovery); }
                finally { sequence = Math.max(previousSequence, discovery.sequence); }
            }
            long now = System.currentTimeMillis();
            if (now - maintainedAt > 60000 || now < maintainedAt) { store.maintain(now); maintainedAt = now; }
            LocalEventStore.Entry row = store.claim(System.currentTimeMillis());
            if (row == null) { wake(1000); return; }
            LocalStartupEvent e;
            try { e = LocalStartupEvent.decode(row.payload); }
            catch (Exception malformed) { store.reject(row.id, LocalStartupDiagnosticCode.INVALID_PAYLOAD.getWireName()); wake(0); return; }
            boolean uploaded = false;
            try { uploader.upload(context, e); uploaded = true; }
            catch (Exception error) {
                String code = error instanceof LocalUploadException ? ((LocalUploadException) error).code : LocalStartupDiagnosticCode.UPLOADER_EXCEPTION.getWireName();
                LocalUploadException transport = error instanceof LocalUploadException ? (LocalUploadException) error : null;
                if (transport != null && transport.targetPause) store.pause(row, System.currentTimeMillis(), code);
                else if (transport != null && !transport.retryable) store.reject(row.id, code);
                else {
                    long delay = Math.min(900000L, 1000L << Math.min(row.attempts, 20));
                    delay += (long) (Math.random() * delay / 4);
                    if (transport != null) delay = Math.max(delay, transport.retryAfterMillis);
                    store.retry(row, System.currentTimeMillis() + delay, code);
                }
                Log.w(TAG, "EVENT_DEFERRED=" + row.id + " CODE=" + code);
            }
            // A local ACK failure cannot enter the HTTP exception handler.
            if (uploaded) { store.ack(row.id); Log.i(TAG, "EVENT_ACK=" + row.id); }
            wake(0);
        } catch (Throwable error) { Log.w(TAG, "OUTBOX_FAILED"); wake(10000); }
    }
    private static void flushFallback() throws Exception {
        if (persistFailures > 0) {
            store.audit(LocalStartupDiagnosticCode.PERSISTENCE_DEGRADED,Long.toString(persistFailures)); persistFailures=0;
        }
        while (!FALLBACK.isEmpty()) {
            LocalStartupEvent e = FALLBACK.peekFirst();
            store.insert(e); FALLBACK.removeFirst();
            if (e.getRecordType() == LocalStartupRecordType.STAGE) {
                String key = e.stage + (e.status.equals(LocalStartupStatus.STARTED.getWireName()) ? ":START" : ":END");
                CAPTURED.add(key); STATUSES.put(key,e.status); RETAINED.remove(key);
            }
        }
    }
}
