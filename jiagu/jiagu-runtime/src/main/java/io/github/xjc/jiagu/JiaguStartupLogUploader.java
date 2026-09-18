package io.github.xjc.jiagu;

import android.content.Context;

/**
 * Receives lifecycle events from the shell startup path.
 *
 * <p>The implementation must be safe to call from a background thread and
 * must not assume that the business DEX has been loaded.</p>
 */
public interface JiaguStartupLogUploader {
    /**
     * Returning normally acknowledges durable remote receipt and removes the local event.
     * Throw on delivery failure; retryable failures survive process restarts. Implementations
     * must deduplicate by event.getTelemetryEventId() and use the event's original metadata.
     * @param appContext application Context, safe to retain for queueing or SDK initialization
     * @param event non-sensitive shell startup event
     */
    void upload(Context appContext, JiaguStartupEvent event);
}
