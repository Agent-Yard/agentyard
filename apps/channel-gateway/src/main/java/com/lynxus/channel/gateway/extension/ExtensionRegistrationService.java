package com.lynxus.channel.gateway.extension;

import com.lynxus.extension.sdk.registration.ExtensionRegistration;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ExtensionRegistrationService {
    private final ExtensionRegistrationSet registrationSet;

    @Autowired
    public ExtensionRegistrationService(ExtensionRegistrationProperties properties) {
        this(properties, System::getenv);
    }

    ExtensionRegistrationService(ExtensionRegistrationProperties properties, Function<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Function<String, String> loaderEnvironment = key -> properties.environmentValue(key, environment);
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
}
