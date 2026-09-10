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
                JiaguStartupEvent.Type.DECRYPT_STARTED, "session", "package", "1.0", 1L,
                1L, 0L, JiaguStartupEvent.AuthorizationSource.UNKNOWN,
                "package", true, true);

        assertTrue(event.isMainProcess());
        assertTrue(event.isFirstLaunch());
    }
}
