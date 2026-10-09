package io.github.xjc.jiagu.local;

import org.json.JSONObject;

public final class LocalStartupEvent {
    public final String sessionId, eventId, stage, status, resultCode, packageName, processName;
    public final long occurredAtMillis, elapsedSinceStartMs, stageDurationMs, versionCode;
    LocalStartupEvent(String session, String id, String stage, String status, String code,
                      long wall, long elapsed, long duration, String pkg, long version, String process) {
        this.sessionId = session; this.eventId = id; this.stage = stage; this.status = status;
        this.resultCode = code; this.occurredAtMillis = wall; this.elapsedSinceStartMs = elapsed;
        this.stageDurationMs = duration; this.packageName = pkg; this.versionCode = version; this.processName = process;
    }
    public JSONObject toJson() throws org.json.JSONException {
        // Legacy user_event data contract: eventId is a stage number, never a UUID.
        return new JSONObject().put("schemaVersion", 3).put("mode", "local")
                .put("sessionId", sessionId).put("telemetryEventId", eventId).put("event", stage)
                .put("event", Integer.toString(stageId(stage)))
                .put("eventId", Integer.toString(stageId(stage)))
                .put("itemName", status + (resultCode == null || resultCode.trim().isEmpty() ? "" : "_" + resultCode))
                .put("occurredAtMillis", occurredAtMillis)
                .put("elapsedMs", elapsedSinceStartMs).put("stageDurationMs", stageDurationMs)
                .put("packageName", packageName).put("versionCode", versionCode).put("processName", processName);
    }
    private static int stageId(String stage) {
        // Reuse only stages whose observable meaning is unchanged. 1..12 are legacy IDs.
        switch (stage) {
            case "STARTUP_ATTEMPT": return 1;
            case "PAYLOAD_DECRYPT": return 7;
            case "LOCAL_PAYLOAD_READ": return 1001;
            case "BUSINESS_DEX_PARSE": return 1002;
            case "BUSINESS_CLASSLOADER_CREATE": return 1003;
            case "REAL_APPLICATION_CREATE": return 1004;
            case "EVENT_REPORTER_READY": return 1005;
            case "FIRST_ACTIVITY_RESUMED": return 1006;
            default: return 0; // Custom stages retain their name in event; no invented legacy meaning.
        }
    }
}
