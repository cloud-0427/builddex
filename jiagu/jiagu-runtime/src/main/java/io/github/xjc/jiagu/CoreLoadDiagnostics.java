package io.github.xjc.jiagu;

/** Non-sensitive classification of failures raised while the system loads jiagu-core. */
final class CoreLoadDiagnostics {
    final String resultCode;
    final String failureClass;

    private CoreLoadDiagnostics(String resultCode, Throwable error) {
        this.resultCode = resultCode;
        this.failureClass = error == null ? null : error.getClass().getName();
    }

    static CoreLoadDiagnostics from(Throwable error) {
        if (!(error instanceof LinkageError)) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_UNEXPECTED_THROWABLE", error);
        }
        if (!(error instanceof UnsatisfiedLinkError)) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_LINKAGE_ERROR", error);
        }
        // Linker text is deliberately only used locally for categorisation. Never upload it:
        // it can contain package-private paths and ROM-specific implementation details.
        String message = error.getMessage();
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("alignment") || value.contains("page size")
                || value.contains("load segment")) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_ELF_ALIGNMENT_INVALID", error);
        }
        if (value.contains("cannot locate") || value.contains("needed by")
                || value.contains("dependency")) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_DEPENDENCY_MISSING", error);
        }
        if (value.contains("jni_onload") || value.contains("jni onload")
                || value.contains("register natives")) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_JNI_ONLOAD_FAILED", error);
        }
        if (value.contains("libjiagu-core.so") && (value.contains("couldn't find")
                || value.contains("could not find") || value.contains("not found"))) {
            return new CoreLoadDiagnostics("CORE_LIBRARY_ABI_NOT_PACKAGED", error);
        }
        return new CoreLoadDiagnostics("CORE_LIBRARY_DLOPEN_FAILED", error);
    }
}
