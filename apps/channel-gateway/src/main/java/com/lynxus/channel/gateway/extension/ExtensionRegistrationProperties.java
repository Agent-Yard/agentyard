package com.lynxus.channel.gateway.extension;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.extensions")
public record ExtensionRegistrationProperties(String registrationFile) {
    public ExtensionRegistrationProperties {
        registrationFile = blankToNull(registrationFile);
    }

    Path registrationFilePath() {
        return registrationFile == null ? null : Path.of(registrationFile);
    }

    static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
