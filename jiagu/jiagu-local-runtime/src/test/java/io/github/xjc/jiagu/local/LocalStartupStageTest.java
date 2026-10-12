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
}
