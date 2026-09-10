package io.github.xjc.jiagu;

/** Immutable, non-sensitive startup telemetry event. */
public final class JiaguStartupEvent {
    public enum Type {
        DECRYPT_STARTED,
        STARTUP_COMPLETED
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

    private final Type type;
    private final String sessionId;
    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final long occurredAtMillis;
    private final long elapsedMs;
    private final AuthorizationSource authorizationSource;
    private final String processName;
    private final boolean mainProcess;
    private final boolean firstLaunch;

    JiaguStartupEvent(Type type, String sessionId, String packageName,
                      String versionName, long versionCode,
                      long occurredAtMillis, long elapsedMs,
                      AuthorizationSource authorizationSource, String processName,
                      boolean mainProcess, boolean firstLaunch) {
        this.type = type;
        this.sessionId = sessionId;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.occurredAtMillis = occurredAtMillis;
        this.elapsedMs = elapsedMs;
        this.authorizationSource = authorizationSource;
        this.processName = processName;
        this.mainProcess = mainProcess;
        this.firstLaunch = firstLaunch;
    }

    public Type getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
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

    /** Milliseconds since shell startup. Zero for DECRYPT_STARTED. */
    public long getElapsedMs() {
        return elapsedMs;
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

    @Override
    public String toString() {
        return "type=" + type
                + " sessionId=" + sessionId
                + " packageName=" + packageName
                + " versionName=" + versionName
                + " versionCode=" + versionCode
                + " occurredAtMillis=" + occurredAtMillis
                + " elapsedMs=" + elapsedMs
                + " authorizationSource=" + authorizationSource
                + " processName=" + processName
                + " mainProcess=" + mainProcess
                + " firstLaunch=" + firstLaunch;
    }
}
