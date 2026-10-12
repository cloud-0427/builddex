#pragma once

// Diagnostic substeps have no telemetry stage ID.
enum class StartupTimingStep {
    JAVA_AUTHORIZATION_AND_PAYLOAD_DECRYPT,
    ALL_DEX_DECOMPRESS,
    DEX_CLASSLOADER_CREATE_AND_INJECT,
    REAL_APPLICATION_CREATE_BIND_AND_ATTACH
};
inline const char* startup_timing_log_name(StartupTimingStep step) {
    switch (step) {
        case StartupTimingStep::JAVA_AUTHORIZATION_AND_PAYLOAD_DECRYPT: return "java-authorization-and-payload-decrypt";
        case StartupTimingStep::ALL_DEX_DECOMPRESS: return "native-all-dex-decompress";
        case StartupTimingStep::DEX_CLASSLOADER_CREATE_AND_INJECT: return "native-dex-classloader-create-and-inject";
        case StartupTimingStep::REAL_APPLICATION_CREATE_BIND_AND_ATTACH: return "native-real-application-create-bind-and-attach";
    }
    return "";
}

// IDs and status values are the stable Java/JNI startup protocol.
enum class StartupStage : int {
    STARTUP_ATTEMPT = 1,
    SHELL_CORE_LOAD = 2,
    SHELL_ATTACH = 3,
    RUNTIME_PROTECTION_CHECK = 4,
    RUNTIME_BUNDLE_LOAD = 5,
    DEVICE_AUTHORIZATION = 6,
    PAYLOAD_DECRYPT = 7,
    BUSINESS_DEX_DECOMPRESS = 8,
    BUSINESS_CLASSLOADER_INJECT = 9,
    REAL_APPLICATION_ATTACH = 10,
    REAL_APPLICATION_ON_CREATE = 11,
    FIRST_ACTIVITY_FIRST_FRAME = 12
};
enum class StartupStatus : int { STARTED = 0, SUCCEEDED = 1, FAILED = 2, SKIPPED = 3, BLOCKED = 4 };
enum class StartupResultCode {
    CHECK_PASSED,
    DEBUGGER_DETECTED,
    DEX_CLASSLOADER_INJECTED,
    DEX_DECOMPRESSED,
    DEX_DECOMPRESS_FAILED,
    DEX_ENTRY_INVALID,
    DEX_PAYLOAD_INVALID,
    HOOK_FRAMEWORK_DETECTED,
    PAYLOAD_BUFFER_INVALID,
    REAL_APPLICATION_ATTACHED,
    REAL_APPLICATION_ATTACH_FAILED,
    REAL_APPLICATION_BIND_FAILED,
    REAL_APPLICATION_CREATE_FAILED,
    REAL_APPLICATION_ON_CREATE_COMPLETED,
    REAL_APPLICATION_ON_CREATE_FAILED,
    RUNTIME_BUNDLE_EXPORTS_MISSING,
    RUNTIME_BUNDLE_INVALID,
    RUNTIME_BUNDLE_LOAD_FAILED,
    RUNTIME_BUNDLE_READY,
    SIGNATURE_MISMATCH
};
inline const char* startup_result_wire_name(StartupResultCode code) {
    switch (code) {
        case StartupResultCode::CHECK_PASSED: return "CHECK_PASSED";
        case StartupResultCode::DEBUGGER_DETECTED: return "DEBUGGER_DETECTED";
        case StartupResultCode::DEX_CLASSLOADER_INJECTED: return "DEX_CLASSLOADER_INJECTED";
        case StartupResultCode::DEX_DECOMPRESSED: return "DEX_DECOMPRESSED";
        case StartupResultCode::DEX_DECOMPRESS_FAILED: return "DEX_DECOMPRESS_FAILED";
        case StartupResultCode::DEX_ENTRY_INVALID: return "DEX_ENTRY_INVALID";
        case StartupResultCode::DEX_PAYLOAD_INVALID: return "DEX_PAYLOAD_INVALID";
        case StartupResultCode::HOOK_FRAMEWORK_DETECTED: return "HOOK_FRAMEWORK_DETECTED";
        case StartupResultCode::PAYLOAD_BUFFER_INVALID: return "PAYLOAD_BUFFER_INVALID";
        case StartupResultCode::REAL_APPLICATION_ATTACHED: return "REAL_APPLICATION_ATTACHED";
        case StartupResultCode::REAL_APPLICATION_ATTACH_FAILED: return "REAL_APPLICATION_ATTACH_FAILED";
        case StartupResultCode::REAL_APPLICATION_BIND_FAILED: return "REAL_APPLICATION_BIND_FAILED";
        case StartupResultCode::REAL_APPLICATION_CREATE_FAILED: return "REAL_APPLICATION_CREATE_FAILED";
        case StartupResultCode::REAL_APPLICATION_ON_CREATE_COMPLETED: return "REAL_APPLICATION_ON_CREATE_COMPLETED";
        case StartupResultCode::REAL_APPLICATION_ON_CREATE_FAILED: return "REAL_APPLICATION_ON_CREATE_FAILED";
        case StartupResultCode::RUNTIME_BUNDLE_EXPORTS_MISSING: return "RUNTIME_BUNDLE_EXPORTS_MISSING";
        case StartupResultCode::RUNTIME_BUNDLE_INVALID: return "RUNTIME_BUNDLE_INVALID";
        case StartupResultCode::RUNTIME_BUNDLE_LOAD_FAILED: return "RUNTIME_BUNDLE_LOAD_FAILED";
        case StartupResultCode::RUNTIME_BUNDLE_READY: return "RUNTIME_BUNDLE_READY";
        case StartupResultCode::SIGNATURE_MISMATCH: return "SIGNATURE_MISMATCH";
    }
    return "";
}
