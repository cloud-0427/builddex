package io.github.xjc.jiagu.local;

import org.junit.Test;
import java.util.HashSet;
import static org.junit.Assert.*;

public final class LocalStartupStageTest {
    @Test public void protocolIdsAndNamesAreStable() {
        LocalStartupStage[] stages = {LocalStartupStage.STARTUP_ATTEMPT, LocalStartupStage.PAYLOAD_DECRYPT,
                LocalStartupStage.LOCAL_PAYLOAD_READ, LocalStartupStage.BUSINESS_DEX_PARSE,
                LocalStartupStage.BUSINESS_CLASSLOADER_CREATE, LocalStartupStage.REAL_APPLICATION_CREATE,
                LocalStartupStage.EVENT_REPORTER_READY, LocalStartupStage.FIRST_ACTIVITY_RESUMED};
        int[] ids = {1, 7, 1001, 1002, 1003, 1004, 1005, 1006};
        HashSet<Integer> unique = new HashSet<>();
        for (int i = 0; i < stages.length; i++) {
            assertEquals(ids[i], stages[i].getId());
            assertTrue(unique.add(stages[i].getId()));
            assertSame(stages[i], LocalStartupStage.fromId(ids[i]));
            assertSame(stages[i], LocalStartupStage.fromWireName(stages[i].name()));
        }
        assertEquals(stages.length, LocalStartupStage.values().length);
        assertThrows(IllegalArgumentException.class, () -> LocalStartupStage.fromId(0));
    }
    @Test public void declarationsFollowDisplayOrderWithoutRenumberingProtocol() {
        int[] ids = {1, 1001, 7, 1002, 1003, 1004, 1005, 1006};
        LocalStartupStage[] stages = LocalStartupStage.values();
        int previous = 0;
        for (int i = 0; i < stages.length; i++) {
            assertEquals(ids[i], stages[i].getId());
            assertTrue(stages[i].getDisplayOrder() > previous);
            assertFalse(stages[i].getDisplayName().isEmpty());
            assertEquals(stages[i] == LocalStartupStage.EVENT_REPORTER_READY, stages[i].isAsynchronous());
            previous = stages[i].getDisplayOrder();
        }
    }
    @Test public void diagnosticsAndCheckpointsKeepStableWireNames() {
        for (LocalStartupDiagnosticCode code : LocalStartupDiagnosticCode.values()) {
            assertEquals(code.name(), code.getWireName());
            assertSame(code, LocalStartupDiagnosticCode.fromWireName(code.getWireName()));
        }
        for (LocalStartupCheckpoint step : LocalStartupCheckpoint.values())
            assertSame(step, LocalStartupCheckpoint.fromWireName(step.getWireName()));
        for (LocalStartupRecordType type : LocalStartupRecordType.values())
            assertSame(type, LocalStartupRecordType.fromWireName(type.getWireName()));
        for (LocalStartupInternalEvent event : LocalStartupInternalEvent.values())
            assertSame(event, LocalStartupInternalEvent.fromWireName(event.getWireName()));
        assertThrows(IllegalArgumentException.class, () -> LocalStartupDiagnosticCode.fromWireName("FUTURE_CUSTOM"));
    }
}
