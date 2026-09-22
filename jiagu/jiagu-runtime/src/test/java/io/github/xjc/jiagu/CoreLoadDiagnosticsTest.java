package io.github.xjc.jiagu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CoreLoadDiagnosticsTest {
    @Test public void classifiesMissingAbiWithoutRetainingLinkerMessage() {
        CoreLoadDiagnostics value = CoreLoadDiagnostics.from(new UnsatisfiedLinkError(
                "couldn't find libjiagu-core.so for ABI arm64-v8a"));
        assertEquals("CORE_LIBRARY_ABI_NOT_PACKAGED", value.resultCode);
        assertEquals("java.lang.UnsatisfiedLinkError", value.failureClass);
    }

    @Test public void classifiesAlignmentAndJniFailures() {
        assertEquals("CORE_LIBRARY_ELF_ALIGNMENT_INVALID", CoreLoadDiagnostics.from(
                new UnsatisfiedLinkError("ELF load segment has wrong alignment")).resultCode);
        assertEquals("CORE_LIBRARY_JNI_ONLOAD_FAILED", CoreLoadDiagnostics.from(
                new UnsatisfiedLinkError("JNI_OnLoad failed")).resultCode);
    }
}
