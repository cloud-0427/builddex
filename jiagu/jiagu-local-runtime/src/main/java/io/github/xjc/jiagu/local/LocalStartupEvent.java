package io.github.xjc.jiagu.local;

import org.json.JSONObject;

public final class LocalStartupEvent {
    public final String sessionId, eventId, stage, status, resultCode, packageName, processName;
    public final long occurredAtMillis, elapsedSinceStartMs, stageDurationMs, versionCode;
    long sequence;
    String recordType = LocalStartupRecordType.STAGE.getWireName(), buildId = "", relatedSessionId = "", checkpoint = "";
    String versionName = "";
    String mainProcessName;
    private JSONObject environment;
    private String frozenJson;
    private final LocalStartupStage stageValue;
    private final LocalStartupStatus statusValue;
    private final LocalStartupResultCode resultValue;
    /** Null for custom stages accepted by the string compatibility API. */
    public LocalStartupStage getStage() { return stageValue; }
    public LocalStartupStatus getStatus() { return statusValue; }
    /** Null for custom result codes accepted by the string compatibility API. */
    public LocalStartupResultCode getResultCode() { return resultValue; }
    /** Unknown future/custom record categories remain available through their raw JSON. */
    public LocalStartupRecordType getRecordType() {
        try { return LocalStartupRecordType.fromWireName(recordType); } catch (IllegalArgumentException unknown) { return null; }
    }
    public LocalStartupDiagnosticCode getDiagnosticCode() {
        if (getRecordType() != LocalStartupRecordType.DIAGNOSTIC) return null;
        try { return LocalStartupDiagnosticCode.fromWireName(resultCode); } catch (IllegalArgumentException custom) { return null; }
    }
    public int getStageId() { return stageValue == null ? 0 : stageValue.getId(); }
    LocalStartupEvent(String session, String id, LocalStartupStage stage, LocalStartupStatus status, LocalStartupResultCode code,
                      long wall, long elapsed, long duration, String pkg, long version, String process) {
        this(session, id, stage.getWireName(), status.getWireName(), code.getWireName(), wall, elapsed, duration, pkg, version, process);
    }
    // Retained for protocol fixtures and compatibility with custom event names.
    LocalStartupEvent(String session, String id, String stage, String status, String code,
                      long wall, long elapsed, long duration, String pkg, long version, String process) {
        LocalStartupStage parsedStage = null;
        LocalStartupStatus parsedStatus = null;
        LocalStartupResultCode parsedCode = null;
        try { parsedStage = LocalStartupStage.fromWireName(stage); } catch (IllegalArgumentException ignored) { }
        try { parsedStatus = LocalStartupStatus.fromWireName(status); } catch (IllegalArgumentException ignored) { }
        try { parsedCode = LocalStartupResultCode.fromWireName(code); } catch (IllegalArgumentException ignored) { }
        stageValue = parsedStage; statusValue = parsedStatus; resultValue = parsedCode;
        this.sessionId = session; this.eventId = id; this.stage = stage; this.status = status;
        this.resultCode = code; this.occurredAtMillis = wall; this.elapsedSinceStartMs = elapsed;
        this.stageDurationMs = duration; this.packageName = pkg; this.versionCode = version; this.processName = process == null ? "UNKNOWN" : process;
        this.mainProcessName = pkg;
        try {
            java.util.Locale locale = java.util.Locale.getDefault();
            environment = new JSONObject().put("brand", android.os.Build.BRAND).put("model", android.os.Build.MODEL)
                    .put("language", locale.toLanguageTag()).put("region", locale.getCountry().toLowerCase(java.util.Locale.ROOT))
                    .put("system", "android:" + android.os.Build.VERSION.RELEASE + ",api:" + android.os.Build.VERSION.SDK_INT);
        } catch (Exception ignored) { environment = new JSONObject(); }
    }
    public JSONObject toJson() throws org.json.JSONException {
        if (frozenJson != null) return new JSONObject(frozenJson);
        // Legacy user_event data contract: eventId is a stage number, never a UUID.
        return new JSONObject().put("schemaVersion", 4).put("mode", "local")
                .put("sessionId", sessionId).put("telemetryEventId", eventId)
                .put("event", stage)
                .put("eventId", Integer.toString(getStageId()))
                .put("itemName", status + (resultCode == null || resultCode.trim().isEmpty() ? "" : "_" + resultCode))
                .put("occurredAtMillis", occurredAtMillis)
                .put("elapsedMs", elapsedSinceStartMs).put("stageDurationMs", stageDurationMs)
                .put("packageName", packageName).put("versionCode", versionCode).put("processName", processName)
                .put("status", status).put("resultCode", resultCode).put("sequence", sequence)
                .put("recordType", recordType).put("buildId", buildId).put("runtimeVersion", "local-p0-v1")
                .put("relatedSessionId", relatedSessionId).put("checkpoint", checkpoint)
                .put("versionName", versionName).put("environment", environment)
                .put("isMainProcess", mainProcessName.equals(processName))
                .put("launchContext", getStage() == LocalStartupStage.FIRST_ACTIVITY_RESUMED ? "FOREGROUND_CONFIRMED" : "UNKNOWN");
    }
    static LocalStartupEvent decode(String payload) throws org.json.JSONException {
        JSONObject j = new JSONObject(payload);
        LocalStartupEvent e = new LocalStartupEvent(j.getString("sessionId"), j.getString("telemetryEventId"),
                j.getString("event"), j.getString("status"), j.getString("resultCode"),
                j.getLong("occurredAtMillis"), j.getLong("elapsedMs"), j.getLong("stageDurationMs"),
                j.getString("packageName"), j.getLong("versionCode"), j.getString("processName"));
        e.sequence = j.getLong("sequence"); e.recordType = j.getString("recordType");
        e.buildId = j.getString("buildId"); e.relatedSessionId = j.optString("relatedSessionId");
        e.checkpoint = j.optString("checkpoint");
        e.versionName = j.optString("versionName"); e.environment = j.optJSONObject("environment");
        if (e.environment == null) e.environment = new JSONObject();
        e.frozenJson = payload;
        return e;
    }
}
