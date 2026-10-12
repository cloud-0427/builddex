package io.github.xjc.jiagu.local;

import org.json.JSONObject;

public final class LocalStartupEvent {
    public final String sessionId, eventId, stage, status, resultCode, packageName, processName;
    public final long occurredAtMillis, elapsedSinceStartMs, stageDurationMs, versionCode;
    private final LocalStartupStage stageValue;
    private final LocalStartupStatus statusValue;
    private final LocalStartupResultCode resultValue;
    /** Null for custom stages accepted by the string compatibility API. */
    public LocalStartupStage getStage() { return stageValue; }
    public LocalStartupStatus getStatus() { return statusValue; }
    /** Null for custom result codes accepted by the string compatibility API. */
    public LocalStartupResultCode getResultCode() { return resultValue; }
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
        this.stageDurationMs = duration; this.packageName = pkg; this.versionCode = version; this.processName = process;
    }
    public JSONObject toJson() throws org.json.JSONException {
        // Legacy user_event data contract: eventId is a stage number, never a UUID.
        return new JSONObject().put("schemaVersion", 3).put("mode", "local")
                .put("sessionId", sessionId).put("telemetryEventId", eventId)
                .put("event", stage)
                .put("eventId", Integer.toString(getStageId()))
                .put("itemName", status + (resultCode == null || resultCode.trim().isEmpty() ? "" : "_" + resultCode))
                .put("occurredAtMillis", occurredAtMillis)
                .put("elapsedMs", elapsedSinceStartMs).put("stageDurationMs", stageDurationMs)
                .put("packageName", packageName).put("versionCode", versionCode).put("processName", processName);
    }
}
