package io.github.xjc.jiagu.local;

import android.content.Context;

/** Called only on the event worker. Return after acknowledgement; throw on failure.
 * Implementations must bound each delivery to 10s and deduplicate by eventId. */
public interface LocalEventUploader {
    void upload(Context context, LocalStartupEvent event) throws Exception;
}
