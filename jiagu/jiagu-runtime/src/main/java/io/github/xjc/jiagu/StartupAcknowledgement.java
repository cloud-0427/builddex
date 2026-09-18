package io.github.xjc.jiagu;

import java.util.function.Consumer;

/** Used only by the single upload worker. Failed local deletion never repeats HTTP. */
final class StartupAcknowledgement {
    private String pendingId;

    void uploaded(String id) {
        if (pendingId != null) throw new IllegalStateException("Previous acknowledgement pending");
        pendingId = id;
    }

    void flush(Consumer<String> delete) {
        if (pendingId == null) return;
        delete.accept(pendingId);
        // Keep the ID if deletion throws, including an ambiguous commit failure.
        pendingId = null;
    }
}
