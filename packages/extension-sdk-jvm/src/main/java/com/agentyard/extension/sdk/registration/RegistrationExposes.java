package com.agentyard.extension.sdk.registration;

import java.util.List;
import java.util.Objects;

public record RegistrationExposes(
    List<String> channelProviderTypes,
    List<String> toolConnectorTypes
) {
    public RegistrationExposes {
        Objects.requireNonNull(channelProviderTypes, "channelProviderTypes");
        Objects.requireNonNull(toolConnectorTypes, "toolConnectorTypes");
        channelProviderTypes = List.copyOf(channelProviderTypes);
        toolConnectorTypes = List.copyOf(toolConnectorTypes);
    }

    public boolean isEmpty() {
        return channelProviderTypes.isEmpty() && toolConnectorTypes.isEmpty();
    }
}
