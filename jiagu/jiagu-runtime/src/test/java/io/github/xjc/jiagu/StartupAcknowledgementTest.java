package io.github.xjc.jiagu;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StartupAcknowledgementTest {
    @Test public void deletionFailureRetriesOnlyLocalAcknowledgement() {
        StartupAcknowledgement ack = new StartupAcknowledgement();
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                // Same ordering as the single worker: pending ACK precedes row selection.
                ack.flush(id -> {
                    assertEquals("event-6", id);
                    if (deletes.incrementAndGet() < 3) throw new IllegalStateException("disk unavailable");
                });
                if (uploads.get() == 0) {
                    uploads.incrementAndGet();
                    ack.uploaded("event-6");
                    ack.flush(id -> {
                        deletes.incrementAndGet();
                        throw new IllegalStateException("disk unavailable");
                    });
                }
            } catch (IllegalStateException expected) {
                assertEquals("disk unavailable", expected.getMessage());
            }
        }
        assertEquals(1, uploads.get());
        assertEquals(3, deletes.get());
        ack.flush(id -> fail("Already acknowledged; nothing to delete"));
    }

    @Test public void successfulDeleteClearsImmediately() {
        StartupAcknowledgement ack = new StartupAcknowledgement();
        ack.uploaded("first");
        ack.flush(id -> assertEquals("first", id));
        ack.uploaded("second");
        ack.flush(id -> assertEquals("second", id));
        ack.flush(id -> fail("Unexpected repeated deletion"));
    }
}
