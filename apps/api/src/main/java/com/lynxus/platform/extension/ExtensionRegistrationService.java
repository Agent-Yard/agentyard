package com.lynxus.platform.extension;

import com.lynxus.extension.sdk.registration.ExtensionRegistration;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ExtensionRegistrationService {
    private final ExtensionRegistrationSet registrationSet;

    @Autowired
    public ExtensionRegistrationService(
        ExtensionRegistrationProperties properties,
        @Value("${lynxus.channel-gateway.base-url}") String channelGatewayBaseUrl,
        @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl
    ) {
        this(properties, channelGatewayBaseUrl, agentRuntimeBaseUrl, System::getenv);
    }

    ExtensionRegistrationService(
        ExtensionRegistrationProperties properties,
        String channelGatewayBaseUrl,
        String agentRuntimeBaseUrl,
        Function<String, String> environment
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        String normalizedChannelGatewayBaseUrl = requireBaseUrl(channelGatewayBaseUrl, "channelGatewayBaseUrl");
        String normalizedAgentRuntimeBaseUrl = requireBaseUrl(agentRuntimeBaseUrl, "agentRuntimeBaseUrl");
        Function<String, String> loaderEnvironment = key -> environmentValue(
            key,
            normalizedChannelGatewayBaseUrl,
            normalizedAgentRuntimeBaseUrl,
            environment
        );
        Path registrationFile = properties.registrationFilePath();
        this.registrationSet = registrationFile == null
            ? ExtensionRegistrationLoader.loadYaml("", loaderEnvironment)
            : ExtensionRegistrationLoader.load(registrationFile, loaderEnvironment);
    }

    public ExtensionRegistrationSet registrationSet() {
        return registrationSet;
    }

    public List<ExtensionRegistration> services() {
        return registrationSet.services();
    }

    public String registrationConfigDigest() {
        return registrationSet.registrationConfigDigest();
    }

    private static String environmentValue(
        String key,
        String channelGatewayBaseUrl,
        String agentRuntimeBaseUrl,
        Function<String, String> fallbackEnvironment
    ) {
        if (ExtensionRegistrationLoader.CHANNEL_GATEWAY_BASE_URL_ENV.equals(key)) {
            return channelGatewayBaseUrl;
        }
        if (ExtensionRegistrationLoader.AGENT_RUNTIME_BASE_URL_ENV.equals(key)) {
            return agentRuntimeBaseUrl;
        }
        return ExtensionRegistrationProperties.blankToNull(fallbackEnvironment.apply(key));
    }

    private static String requireBaseUrl(String value, String label) {
        String normalized = ExtensionRegistrationProperties.blankToNull(value);
        if (normalized == null) {
            throw new IllegalStateException(label + " must be configured");
        }
        return normalized;
    }
}
