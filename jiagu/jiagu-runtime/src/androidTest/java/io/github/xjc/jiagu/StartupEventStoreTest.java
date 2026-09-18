package io.github.xjc.jiagu;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;

/** Run on a device: exercises actual SQLite persistence, not Android mock stubs. */
public class StartupEventStoreTest {
    @Test public void testRestartAfterFirstFrameBeforeAck() throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "outbox-" + System.nanoTime() + ".db");
        JiaguStartupEvent event = new JiaguStartupEvent(
                JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME,
                JiaguStartupEvent.Status.SUCCEEDED, "FIRST_FRAME_DRAWN", "old-session",
                "install", "pkg", "1", 1, 100, 10, 5,
                JiaguStartupEvent.AuthorizationSource.UNKNOWN, "pkg", true, true, null, 5);
        try {
            try (StartupEventStore first = new StartupEventStore(file)) {
                first.append(event);
                assertTrue(first.completed());
                StartupEventStore.Entry row = first.next(System.currentTimeMillis());
                assertNotNull(row);
                first.retry(row, System.currentTimeMillis() + 60_000);
            }
            try (StartupEventStore restarted = new StartupEventStore(file)) {
                assertTrue(restarted.completed());
                assertNull(restarted.next(System.currentTimeMillis()));
                StartupEventStore.Entry row = restarted.next(System.currentTimeMillis() + 120_000);
                assertEquals(1, row.attempts);
                assertEquals(event.toString(), StartupEventCodec.decode(row.payload).toString());
                // Duplicate generation must not reset retry metadata or add another row.
                restarted.append(event);
                assertEquals(1, restarted.next(System.currentTimeMillis() + 120_000).attempts);
                restarted.acknowledge(row.id);
                assertEquals(-1L, restarted.nextDelay(System.currentTimeMillis()));
            }
        } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file); }
    }
}
