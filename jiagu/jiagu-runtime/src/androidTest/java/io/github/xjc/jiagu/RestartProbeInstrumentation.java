package io.github.xjc.jiagu;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Two separate instrumentation processes prove recovery without Java static state. */
public final class RestartProbeInstrumentation extends Instrumentation {
    private String phase;
    @Override public void onCreate(Bundle args) { super.onCreate(args); phase = args.getString("phase"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            File file = new File(getTargetContext().getNoBackupFilesDir(), "jiagu-startup-v1.db");
            if ("seed".equals(phase)) android.database.sqlite.SQLiteDatabase.deleteDatabase(file);
            if ("seed".equals(phase)) {
                JiaguStartupReporter.initialize(getTargetContext());
                JiaguStartupReporter.startupAttemptStarted();
                JiaguStartupReporter.stageSucceeded(JiaguStartupEvent.Stage.FIRST_ACTIVITY_FIRST_FRAME,
                        "FIRST_FRAME_DRAWN", 5);
            }
            try (StartupEventStore store = new StartupEventStore(file)) {
                if ("seed".equals(phase)) {
                    if (!store.completed() || store.next(System.currentTimeMillis()) == null)
                        throw new AssertionError("Reporter did not commit events and completion");
                } else if ("replay".equals(phase)) {
                    if (!store.completed()) throw new AssertionError("Completion marker lost");
                    StartupEventStore.Entry row = store.next(System.currentTimeMillis());
                    if (row == null) throw new AssertionError("Pending event lost across process death");
                    String originalSession = StartupEventCodec.decode(row.payload).getSessionId();
                    CountDownLatch sent = new CountDownLatch(2);
                    AtomicReference<String> failure = new AtomicReference<>();
                    JiaguStartupReporter.setUploader((context, event) -> {
                        if (!originalSession.equals(event.getSessionId()) || !event.isFirstLaunch())
                            failure.set("Historical metadata overwritten");
                        sent.countDown();
                    });
                    JiaguStartupReporter.initialize(getTargetContext());
                    JiaguStartupReporter.startupAttemptStarted(); // Must not collect a new non-first session.
                    if (!sent.await(10, TimeUnit.SECONDS)) throw new AssertionError("Reporter did not replay");
                    long deadline = System.currentTimeMillis() + 5000;
                    while (store.nextDelay(System.currentTimeMillis()) != -1 && System.currentTimeMillis() < deadline)
                        Thread.sleep(10);
                    if (failure.get() != null) throw new AssertionError(failure.get());
                    if (store.nextDelay(System.currentTimeMillis()) != -1) throw new AssertionError("ACK not applied");
                } else throw new AssertionError("Specify seed or replay");
            }
            result.putString("stream", "PASS " + phase + " pid=" + android.os.Process.myPid());
            finish(-1, result);
        } catch (Throwable e) { result.putString("stream", "FAIL " + e); finish(1, result); }
    }
}
