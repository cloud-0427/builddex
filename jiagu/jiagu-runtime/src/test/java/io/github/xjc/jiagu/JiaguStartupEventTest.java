package io.github.xjc.jiagu;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JiaguStartupEventTest {
    @Test
    public void authorizationSourceExposesNetworkRequirement() {
        assertFalse(JiaguStartupEvent.AuthorizationSource.LOCAL_AUTHORIZATION_CACHE
                .isNetworkRequired());
        assertTrue(JiaguStartupEvent.AuthorizationSource.NETWORK_BOOTSTRAP
                .isNetworkRequired());
        assertTrue(JiaguStartupEvent.AuthorizationSource.NETWORK_AUTHORIZE
                .isNetworkRequired());
        assertTrue(JiaguStartupEvent.AuthorizationSource.NETWORK_LEGACY_ENROLL_AUTHORIZE
                .isNetworkRequired());
    }

    @Test
    public void firstLaunchIsStableEventMetadata() {
        JiaguStartupEvent event = new JiaguStartupEvent(
                JiaguStartupEvent.Stage.SHELL_ATTACH, JiaguStartupEvent.Status.STARTED,
                null, "session", "instance", "package", "1.0", 1L, 1L, 0L, 0L,
                JiaguStartupEvent.AuthorizationSource.UNKNOWN,
                "package", true, true, null, -1L);

        assertTrue(event.getStageId() == 3);
        assertTrue(event.isMainProcess());
        assertTrue(event.isFirstLaunch());
        assertTrue("instance".equals(event.getStartupInstanceId()));
    }

    @Test
    public void uploadFailurePreservesRetryClassification() {
        JiaguStartupUploadException retryable = new JiaguStartupUploadException(
                "HTTP_429", true, 2_000L, null);
        JiaguStartupUploadException permanent = new JiaguStartupUploadException(
                "HTTP_400", false, 0L, null);

        assertTrue(retryable.isRetryable());
        assertTrue(retryable.getRetryAfterMillis() == 2_000L);
        assertFalse(permanent.isRetryable());
    }
}
