package com.lynxus.platform.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.extension.sdk.registration.ExtensionRegistration;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

final class ExtensionRegistrationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REGISTRATION_FIXTURE = Path.of(
        "packages/extension-protocol/contract-tests/fixtures/registration-loader/valid-operator-with-presets.json"
    );
    private static final Map<String, String> CORE_PRESET_ENVIRONMENT = Map.of(
        ExtensionRegistrationLoader.CHANNEL_GATEWAY_URL_ENV,
        "HTTP://Channel-Gateway.Example.COM:80/core/",
        ExtensionRegistrationLoader.AGENT_RUNTIME_URL_ENV,
        "https://Agent-Runtime.Example.COM:443/runtime/"
    );
    private static final ApplicationContextRunner CONTEXT_RUNNER = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(RegistrationBeanTestConfiguration.class);

    @Test
    void bindsConfigurationAndCreatesRegistrationBean() {
        CONTEXT_RUNNER
            .withPropertyValues(
                "lynxus.extensions.channel-gateway-url=" +
                CORE_PRESET_ENVIRONMENT.get(ExtensionRegistrationLoader.CHANNEL_GATEWAY_URL_ENV),
                "lynxus.extensions.agent-runtime-url=" +
                CORE_PRESET_ENVIRONMENT.get(ExtensionRegistrationLoader.AGENT_RUNTIME_URL_ENV)
            )
            .run(context -> {
                assertTrue(context.containsBean("extensionRegistrationService"));
                ExtensionRegistrationService service = context.getBean(ExtensionRegistrationService.class);
                ExtensionRegistrationSet sdkLoaded = ExtensionRegistrationLoader.loadYaml("", CORE_PRESET_ENVIRONMENT);

                assertEquals(sdkLoaded.registrationConfigDigest(), service.registrationConfigDigest());
                assertEquals(sdkLoaded.canonicalInput(), service.registrationSet().canonicalInput());
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsConfiguredOperatorFileWithSharedSdkDigest(@TempDir Path tempDir) throws IOException {
        Map<String, Object> fixture = readRegistrationFixture();
        Map<String, String> environment = (Map<String, String>) fixture.get("environment");
        Path registrationFile = tempDir.resolve("extensions.yaml");
        Files.writeString(registrationFile, (String) fixture.get("operatorYaml"));

        ExtensionRegistrationProperties properties = new ExtensionRegistrationProperties(
            registrationFile.toString(),
            environment.get(ExtensionRegistrationLoader.CHANNEL_GATEWAY_URL_ENV),
            environment.get(ExtensionRegistrationLoader.AGENT_RUNTIME_URL_ENV)
        );
        ExtensionRegistrationService service = new ExtensionRegistrationService(properties, environment::get);
        ExtensionRegistrationSet sdkLoaded = ExtensionRegistrationLoader.load(registrationFile, environment);
        Map<String, Object> expected = (Map<String, Object>) fixture.get("expected");

        assertEquals(expected.get("registrationConfigDigest"), service.registrationConfigDigest());
        assertEquals(expected.get("canonicalInput"), service.registrationSet().canonicalInput());
        assertEquals(sdkLoaded.registrationConfigDigest(), service.registrationConfigDigest());
    }

    @Test
    void usesCorePresetsWhenOperatorFileIsUnset() {
        ExtensionRegistrationProperties properties = new ExtensionRegistrationProperties(
            null,
            CORE_PRESET_ENVIRONMENT.get(ExtensionRegistrationLoader.CHANNEL_GATEWAY_URL_ENV),
            CORE_PRESET_ENVIRONMENT.get(ExtensionRegistrationLoader.AGENT_RUNTIME_URL_ENV)
        );
        ExtensionRegistrationService service = new ExtensionRegistrationService(properties, key -> null);
        ExtensionRegistrationSet sdkLoaded = ExtensionRegistrationLoader.loadYaml("", CORE_PRESET_ENVIRONMENT);

        assertEquals(sdkLoaded.registrationConfigDigest(), service.registrationConfigDigest());
        assertEquals(sdkLoaded.canonicalInput(), service.registrationSet().canonicalInput());
        assertEquals(
            List.of(
                ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID,
                ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID
            ),
            service.registrationSet().services().stream().map(ExtensionRegistration::registrationId).toList()
        );
    }

    private static Map<String, Object> readRegistrationFixture() throws IOException {
        Path fixturePath = repoRoot().resolve(REGISTRATION_FIXTURE);
        return JSON.readValue(Files.readString(fixturePath), new TypeReference<>() {});
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(REGISTRATION_FIXTURE))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("unable to locate repository root from " + Path.of("").toAbsolutePath());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExtensionRegistrationProperties.class)
    static class RegistrationBeanTestConfiguration {
        @Bean
        ExtensionRegistrationService extensionRegistrationService(ExtensionRegistrationProperties properties) {
            return new ExtensionRegistrationService(properties, key -> null);
        }
    }
}
