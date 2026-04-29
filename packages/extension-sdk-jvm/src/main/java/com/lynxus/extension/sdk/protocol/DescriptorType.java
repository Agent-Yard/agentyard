package com.lynxus.extension.sdk.protocol;

import java.util.Arrays;
import java.util.Optional;

public enum DescriptorType implements WireEnum {
    TOOL_CONNECTOR("TOOL_CONNECTOR"),
    CHANNEL_PROVIDER("CHANNEL_PROVIDER");

    private final String wireValue;

    DescriptorType(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static Optional<DescriptorType> fromWireValue(String value) {
        return Arrays.stream(values()).filter(item -> item.wireValue.equals(value)).findFirst();
    }
}
