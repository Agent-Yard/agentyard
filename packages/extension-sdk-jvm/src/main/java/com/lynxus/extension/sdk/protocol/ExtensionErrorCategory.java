package com.lynxus.extension.sdk.protocol;

import java.util.Arrays;
import java.util.Optional;

public enum ExtensionErrorCategory implements WireEnum {
    AUTH("AUTH"),
    BAD_REQUEST("BAD_REQUEST"),
    REMOTE_TIMEOUT("REMOTE_TIMEOUT"),
    REMOTE_UNAVAILABLE("REMOTE_UNAVAILABLE"),
    REMOTE_RATE_LIMITED("REMOTE_RATE_LIMITED"),
    REMOTE_BUSINESS_REJECTED("REMOTE_BUSINESS_REJECTED"),
    PROTOCOL_ERROR("PROTOCOL_ERROR"),
    CIRCUIT_OPEN("CIRCUIT_OPEN"),
    UNKNOWN("UNKNOWN");

    private final String wireValue;

    ExtensionErrorCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static Optional<ExtensionErrorCategory> fromWireValue(String value) {
        return Arrays.stream(values()).filter(item -> item.wireValue.equals(value)).findFirst();
    }
}
