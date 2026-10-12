package io.github.xjc.jiagu.local;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LocalStartupEventProtocolTest {
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
