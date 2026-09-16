package io.github.xjc.jiagu;

/** Failure classification returned by shell-side uploaders. */
public final class JiaguStartupUploadException extends RuntimeException {
    private final boolean retryable;
    private final long retryAfterMillis;

    public JiaguStartupUploadException(String message, boolean retryable, long retryAfterMillis,
                                       Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public boolean isRetryable() { return retryable; }
    public long getRetryAfterMillis() { return retryAfterMillis; }
}
