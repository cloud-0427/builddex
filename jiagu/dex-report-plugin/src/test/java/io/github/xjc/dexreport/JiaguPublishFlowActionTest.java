package io.github.xjc.dexreport;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JiaguPublishFlowActionTest {
    @Test
    public void skipsPublishedReleaseReturnedByPrepare() {
        String metadata = "{\"releaseId\":\"release-1\",\"status\":\"PUBLISHED\"}";
        assertFalse(JiaguPublishFlowAction.shouldPublish(metadata, true));
    }

    @Test
    public void publishesDraftOnlyWhenRequested() {
        String metadata = "{\"releaseId\":\"release-1\",\"status\":\"DRAFT\"}";
        assertTrue(JiaguPublishFlowAction.shouldPublish(metadata, true));
        assertFalse(JiaguPublishFlowAction.shouldPublish(metadata, false));
    }
}
