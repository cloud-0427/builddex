package lows.dgeon.ightr.jiagu;

import android.content.Context;
import android.util.Log;
import io.github.xjc.jiagu.local.*;
import java.util.*;

/** Debug-only transport fixture: never sends network requests. */
public final class LocalProbeUploader implements LocalEventUploader {
    private final Set<String> retried = new HashSet<>();
    public LocalProbeUploader() { Log.i("LocalProbe", "WORKER=" + Thread.currentThread().getName()); }
    @Override public void upload(Context c, LocalStartupEvent e) throws Exception {
        Log.i("LocalProbe", "EVENT=" + e.stage + " ID=" + e.eventId + " THREAD=" + Thread.currentThread().getName());
        if (e.getStage() == LocalStartupStage.PAYLOAD_DECRYPT && retried.add(e.eventId)) {
            Log.i("LocalProbe", "INJECT_RETRY=" + e.eventId);
            throw new LocalUploadException("TEST_TIMEOUT", true, null);
        }
        if (e.getStage() == LocalStartupStage.LOCAL_PAYLOAD_READ) throw new LocalUploadException("TEST_REJECTED", false, null);
    }
}
