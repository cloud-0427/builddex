package io.github.xjc.jiagu.local;

public final class LocalUploadException extends Exception {
    public final boolean retryable;
    public final String code;
    public final boolean targetPause;
    public final long retryAfterMillis;
    public LocalUploadException(String code, boolean retryable, Throwable cause) {
        this(code, retryable, false, 0, cause);
    }
    public LocalUploadException(String code, boolean retryable, boolean targetPause, long retryAfterMillis, Throwable cause) {
        super(code, cause); this.retryable = retryable;
        this.code = code != null && code.matches("[A-Z_0-9]{1,96}") ? code : "UPLOAD_FAILED";
        this.targetPause = targetPause;
        this.retryAfterMillis = Math.max(0, Math.min(7L * 86400000, retryAfterMillis));
    }
}
