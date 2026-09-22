package io.github.xjc.jiagu;

import androidx.annotation.NonNull;

/** Immutable, non-sensitive startup telemetry event. */
public final class JiaguStartupEvent {
    public enum Stage {
        STARTUP_ATTEMPT(1),
        SHELL_CORE_LOAD(2),
        SHELL_ATTACH(3),
        RUNTIME_PROTECTION_CHECK(4),
        RUNTIME_BUNDLE_LOAD(5),
        DEVICE_AUTHORIZATION(6),
        PAYLOAD_DECRYPT(7),
        BUSINESS_DEX_DECOMPRESS(8),
        BUSINESS_CLASSLOADER_INJECT(9),
        REAL_APPLICATION_ATTACH(10),
        REAL_APPLICATION_ON_CREATE(11),
        FIRST_ACTIVITY_FIRST_FRAME(12);

        private final int id;

        Stage(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        static Stage fromId(int id) {
            switch (id) {
                case 1: return STARTUP_ATTEMPT;
                case 2: return SHELL_CORE_LOAD;
                case 3: return SHELL_ATTACH;
                case 4: return RUNTIME_PROTECTION_CHECK;
                case 5: return RUNTIME_BUNDLE_LOAD;
                case 6: return DEVICE_AUTHORIZATION;
                case 7: return PAYLOAD_DECRYPT;
                case 8: return BUSINESS_DEX_DECOMPRESS;
                case 9: return BUSINESS_CLASSLOADER_INJECT;
                case 10: return REAL_APPLICATION_ATTACH;
                case 11: return REAL_APPLICATION_ON_CREATE;
                case 12: return FIRST_ACTIVITY_FIRST_FRAME;
                default: throw new IllegalArgumentException("Unknown startup stage id: " + id);
            }
        }
    }

    public enum Status {
        STARTED,
        SUCCEEDED,
        FAILED,
        SKIPPED,
        BLOCKED;

        static Status fromOrdinal(int ordinal) {
            switch (ordinal) {
                case 0: return STARTED;
                case 1: return SUCCEEDED;
                case 2: return FAILED;
                case 3: return SKIPPED;
                case 4: return BLOCKED;
                default: throw new IllegalArgumentException(
                        "Unknown startup status ordinal: " + ordinal);
            }
        }
    }

    /** Where this startup obtained the authorization material used for decryption. */
    public enum AuthorizationSource {
        /** The shell has not resolved authorization yet. */
        UNKNOWN(false),
        /** A valid signed grant and wrapped payload key were read from local storage. */
        LOCAL_AUTHORIZATION_CACHE(false),
        /** First-time device bootstrap obtained authorization from the network. */
        NETWORK_BOOTSTRAP(true),
        /** A cached credential existed, but authorization was refreshed over the network. */
        NETWORK_AUTHORIZE(true),
        /** Legacy enroll followed by authorize was used after bootstrap was unavailable. */
        NETWORK_LEGACY_ENROLL_AUTHORIZE(true);

        private final boolean networkRequired;

        AuthorizationSource(boolean networkRequired) {
            this.networkRequired = networkRequired;
        }

        public boolean isNetworkRequired() {
            return networkRequired;
        }
    }

    private final Stage stage;
    private final Status status;
    private final String resultCode;
    private final String failureClass;
    private final String sessionId;
    private final String startupInstanceId;
    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final long occurredAtMillis;
    private final long elapsedSinceStartMs;
    private final long stageDurationMs;
    private final AuthorizationSource authorizationSource;
    private final String processName;
    private final boolean mainProcess;
    private final boolean firstLaunch;
    private final String activityName;
    private final long activityResumedElapsedMs;

    JiaguStartupEvent(Stage stage, Status status, String resultCode, String failureClass,
                      String sessionId, String startupInstanceId, String packageName,
                      String versionName, long versionCode,
                      long occurredAtMillis, long elapsedSinceStartMs, long stageDurationMs,
                      AuthorizationSource authorizationSource, String processName,
                      boolean mainProcess, boolean firstLaunch, String activityName,
                      long activityResumedElapsedMs) {
        this.stage = stage;
        this.status = status;
        this.resultCode = resultCode;
        this.failureClass = failureClass;
        this.sessionId = sessionId;
        this.startupInstanceId = startupInstanceId;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.occurredAtMillis = occurredAtMillis;
        this.elapsedSinceStartMs = elapsedSinceStartMs;
        this.stageDurationMs = stageDurationMs;
        this.authorizationSource = authorizationSource;
        this.processName = processName;
        this.mainProcess = mainProcess;
        this.firstLaunch = firstLaunch;
        this.activityName = activityName;
        this.activityResumedElapsedMs = activityResumedElapsedMs;
    }

    public int getStageId() {
        return stage.getId();
    }

    public Stage getStage() {
        return stage;
    }

    public Status getStatus() {
        return status;
    }

    /** Result of a terminal stage: a success path, failure, or block reason. */
    public String getResultCode() {
        return resultCode;
    }

    /** Optional exception class name; never contains an exception message or stack trace. */
    public String getFailureClass() {
        return failureClass;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Stable across retries and process restarts; distinct from the legacy stage ID. */
    public String getTelemetryEventId() {
        return sessionId + ":" + getStageId() + ":" + status.name();
    }

    /** Stable random identifier for this app-data installation. */
    public String getStartupInstanceId() {
        return startupInstanceId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getVersionName() {
        return versionName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public long getOccurredAtMillis() {
        return occurredAtMillis;
    }

    /** Milliseconds since shell core loading started. */
    public long getElapsedMs() {
        return elapsedSinceStartMs;
    }

    public long getElapsedSinceStartMs() {
        return elapsedSinceStartMs;
    }

    /** Duration of this stage; zero for STARTED events. */
    public long getStageDurationMs() {
        return stageDurationMs;
    }

    /**
     * Returns how the payload-key authorization was obtained. It is UNKNOWN on
     * DECRYPT_STARTED and resolved on STARTUP_COMPLETED.
     */
    public AuthorizationSource getAuthorizationSource() {
        return authorizationSource;
    }

    /** Convenience flag for telemetry systems that only need the network/cache split. */
    public boolean isNetworkAuthorizationRequired() {
        return authorizationSource.isNetworkRequired();
    }

    /** Null while authorization has not been resolved. */
    public Boolean getNetworkAuthorizationRequired() {
        return authorizationSource == AuthorizationSource.UNKNOWN
                ? null : authorizationSource.isNetworkRequired();
    }

    public String getProcessName() {
        return processName;
    }

    /** Whether this event came from the application's default (main) process. */
    public boolean isMainProcess() {
        return mainProcess;
    }

    /**
     * Whether this is the first main-process shell startup attempt recorded in
     * the current app-data lifetime. The value is stable for every event in a
     * session, including DECRYPT_STARTED and STARTUP_COMPLETED.
     */
    public boolean isFirstLaunch() {
        return firstLaunch;
    }

    /** Class name of the first resumed Activity; null for earlier shell stages. */
    public String getActivityName() {
        return activityName;
    }

    /** Elapsed time at first Activity.onResume(), or -1 before it occurs. */
    public long getActivityResumedElapsedMs() {
        return activityResumedElapsedMs;
    }

    @NonNull
    @Override
    public String toString() {
        return "stageId=" + getStageId()
                + " stage=" + stage
                + " status=" + status
                + " resultCode=" + resultCode
                + " failureClass=" + failureClass
                + " sessionId=" + sessionId
                + " startupInstanceId=" + startupInstanceId
                + " packageName=" + packageName
                + " versionName=" + versionName
                + " versionCode=" + versionCode
                + " occurredAtMillis=" + occurredAtMillis
                + " elapsedSinceStartMs=" + elapsedSinceStartMs
                + " stageDurationMs=" + stageDurationMs
                + " authorizationSource=" + authorizationSource
                + " processName=" + processName
                + " mainProcess=" + mainProcess
                + " firstLaunch=" + firstLaunch
                + " activityName=" + activityName
                + " activityResumedElapsedMs=" + activityResumedElapsedMs;
    }
}
