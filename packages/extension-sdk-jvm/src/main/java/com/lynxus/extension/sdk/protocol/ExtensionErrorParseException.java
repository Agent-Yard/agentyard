package com.lynxus.extension.sdk.protocol;

public final class ExtensionErrorParseException extends IllegalArgumentException {
    public ExtensionErrorParseException(String message) {
        super(message);
    }

    public ExtensionErrorParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
