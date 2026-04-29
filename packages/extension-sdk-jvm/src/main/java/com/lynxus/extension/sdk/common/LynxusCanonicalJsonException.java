package com.lynxus.extension.sdk.common;

public final class LynxusCanonicalJsonException extends RuntimeException {
    private final LynxusCanonicalJsonErrorCode errorCode;

    public LynxusCanonicalJsonException(LynxusCanonicalJsonErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LynxusCanonicalJsonErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.name();
    }
}
