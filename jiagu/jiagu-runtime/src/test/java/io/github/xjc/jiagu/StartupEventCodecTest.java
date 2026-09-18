package io.github.xjc.jiagu;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class StartupEventCodecTest {
    private JiaguStartupEvent event() {
        return new JiaguStartupEvent(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME,
                JiaguStartupEvent.Status.SUCCEEDED, "FIRST_FRAME_DRAWN", "old-session",
                "installation", "package", "old-version", 42, 1234, 123, 12,
                JiaguStartupEvent.AuthorizationSource.NETWORK_BOOTSTRAP,
                "package", true, true, null, 111);
    }
    @Test public void replayRetainsOriginalMetadataAndIdentity() throws Exception {
        JiaguStartupEvent original = event();
        JiaguStartupEvent restored = StartupEventCodec.decode(StartupEventCodec.encode(original));
        assertEquals(original.toString(), restored.toString());
        assertEquals(original.getTelemetryEventId(), restored.getTelemetryEventId());
        assertEquals("old-session:12:SUCCEEDED", restored.getTelemetryEventId());
    }
    @Test(expected = IOException.class) public void truncatedRecordIsRejected() throws Exception {
        byte[] bytes = StartupEventCodec.encode(event());
        StartupEventCodec.decode(Arrays.copyOf(bytes, bytes.length - 1));
    }
    @Test(expected = IOException.class) public void unknownVersionIsRejected() throws Exception {
        byte[] bytes = StartupEventCodec.encode(event()); bytes[3] = 2;
        StartupEventCodec.decode(bytes);
    }
}
