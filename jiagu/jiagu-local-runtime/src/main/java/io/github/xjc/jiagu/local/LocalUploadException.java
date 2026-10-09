package io.github.xjc.jiagu.local;

public final class LocalUploadException extends Exception {
    public final boolean retryable;
    public LocalUploadException(String code, boolean retryable, Throwable cause) {
        super(code, cause); this.retryable = retryable;
    }
}
