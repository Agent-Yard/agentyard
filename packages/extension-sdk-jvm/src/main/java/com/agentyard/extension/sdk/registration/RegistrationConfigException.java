package com.agentyard.extension.sdk.registration;

public final class RegistrationConfigException extends RuntimeException {
    private final RegistrationConfigErrorCode code;

    public RegistrationConfigException(RegistrationConfigErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RegistrationConfigException(RegistrationConfigErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public RegistrationConfigErrorCode code() {
        return code;
    }
}
