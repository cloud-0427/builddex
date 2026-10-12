package io.github.xjc.jiagu;

/** Diagnostic substeps, separate from telemetry stages and their stable IDs. */
enum StartupTimingStep {
    SHELL_CORE_LOAD("load-jiagu-core-library"),
    DEVICE_BOOTSTRAP("device-bootstrap"),
    DEVICE_BOOTSTRAP_LEGACY_FALLBACK("device-bootstrap-legacy-fallback"),
    DEVICE_AUTHORIZE("device-authorize"),
    PAYLOAD_DECRYPT_AND_VERIFY("local-payload-aes-gcm-decrypt-and-verify");

    private final String logName;
    StartupTimingStep(String logName) { this.logName = logName; }
    String getLogName() { return logName; }
}
