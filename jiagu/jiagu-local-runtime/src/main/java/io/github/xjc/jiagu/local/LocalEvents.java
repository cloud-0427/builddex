package io.github.xjc.jiagu.local;

/** Isolates even reporter class-initialization errors from bootstrap. */
final class LocalEvents {
    static void emit(String stage, String status, String code, long duration) {
        try { LocalEventReporter.emit(stage, status, code, duration); } catch (Throwable ignored) { }
    }
    static void metadata(String name, long code) {
        try { LocalEventReporter.metadata(name, code); } catch (Throwable ignored) { }
    }
}
