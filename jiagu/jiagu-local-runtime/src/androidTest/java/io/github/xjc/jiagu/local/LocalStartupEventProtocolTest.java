package io.github.xjc.jiagu.local;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LocalStartupEventProtocolTest {
    @Test public void diagnosticEnumsPreserveExistingJsonAndReplay() throws Exception {
        for (LocalStartupDiagnosticCode code : LocalStartupDiagnosticCode.values()) {
            LocalStartupEvent event = new LocalStartupEvent("session", "id", LocalStartupInternalEvent.STARTUP_DIAGNOSTIC.getWireName(),
                    LocalStartupStatus.SUCCEEDED.getWireName(), code.getWireName(), 1234, 15, 0, "example.app", 24, "example.app");
            event.recordType = LocalStartupRecordType.DIAGNOSTIC.getWireName();
            String payload = event.toJson().toString();
            assertEquals("0", event.toJson().getString("eventId"));
            assertEquals("STARTUP_DIAGNOSTIC", event.toJson().getString("event"));
            assertEquals("SUCCEEDED_" + code.getWireName(), event.toJson().getString("itemName"));
            assertSame(code, event.getDiagnosticCode());
            assertSame(LocalStartupRecordType.DIAGNOSTIC, LocalStartupEvent.decode(payload).getRecordType());
            assertEquals(payload, LocalStartupEvent.decode(payload).toJson().toString());
        }
    }
    @Test public void eventIsStageNameAndEventIdIsStableStageNumber() throws Exception {
        for (LocalStartupStage stage : LocalStartupStage.values()) {
            LocalStartupEvent event = new LocalStartupEvent("session", "unique-id", stage,
                    LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.NONE,
                    1234, 15, 5, "example.app", 24, "process");
            JSONObject json = event.toJson();
            assertEquals(stage.getWireName(), json.getString("event"));
            assertEquals(Integer.toString(stage.getId()), json.get("eventId"));
            assertEquals("unique-id", json.getString("telemetryEventId"));
            assertEquals("SUCCEEDED", json.getString("itemName"));
            assertEquals(5, json.getLong("stageDurationMs"));
        }
    }

    @Test public void stringCompatibilityPreservesCustomStagesAndResults() throws Exception {
        LocalStartupEvent event = new LocalStartupEvent("session", "unique-id", "CUSTOM_STAGE", "FAILED",
                "CUSTOM_REASON", 1234, 15, 5, "example.app", 24, "process");
        JSONObject json = event.toJson();
        assertEquals("CUSTOM_STAGE", json.getString("event"));
        assertEquals("0", json.getString("eventId"));
        assertEquals("FAILED_CUSTOM_REASON", json.getString("itemName"));
    }
}
