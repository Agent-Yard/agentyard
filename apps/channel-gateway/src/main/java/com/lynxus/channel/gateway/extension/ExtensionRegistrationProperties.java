package com.lynxus.channel.gateway.extension;

import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import java.nio.file.Path;
import java.util.function.Function;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.extensions")
public record ExtensionRegistrationProperties(
    String registrationFile,
    String channelGatewayUrl,
    String agentRuntimeUrl
) {
    private static final String DEFAULT_CHANNEL_GATEWAY_URL = "http://127.0.0.1:8082";
    private static final String DEFAULT_AGENT_RUNTIME_URL = "http://127.0.0.1:8090";

    public ExtensionRegistrationProperties {
        registrationFile = blankToNull(registrationFile);
        channelGatewayUrl = valueOrDefault(channelGatewayUrl, DEFAULT_CHANNEL_GATEWAY_URL);
        agentRuntimeUrl = valueOrDefault(agentRuntimeUrl, DEFAULT_AGENT_RUNTIME_URL);
    }

    Path registrationFilePath() {
        return registrationFile == null ? null : Path.of(registrationFile);
    }

    String environmentValue(String key, Function<String, String> fallbackEnvironment) {
        if (ExtensionRegistrationLoader.CHANNEL_GATEWAY_URL_ENV.equals(key)) {
            return channelGatewayUrl;
        }
        if (ExtensionRegistrationLoader.AGENT_RUNTIME_URL_ENV.equals(key)) {
            return agentRuntimeUrl;
        }
        String value = fallbackEnvironment.apply(key);
        return blankToNull(value);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
