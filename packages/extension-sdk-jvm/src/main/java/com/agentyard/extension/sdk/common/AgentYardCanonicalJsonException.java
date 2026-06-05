package com.agentyard.extension.sdk.common;

public final class AgentYardCanonicalJsonException extends RuntimeException {
    private final AgentYardCanonicalJsonErrorCode errorCode;

    public AgentYardCanonicalJsonException(AgentYardCanonicalJsonErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentYardCanonicalJsonErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.name();
    }
}
