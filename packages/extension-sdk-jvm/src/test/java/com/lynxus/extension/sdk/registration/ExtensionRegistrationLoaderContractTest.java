package com.lynxus.extension.sdk.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ExtensionRegistrationLoaderContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void registrationLoaderFixturesMatchProtocolContract() throws IOException {
        Path fixturesDir = Path.of(System.getProperty("lynxus.repo.root"))
            .resolve("packages/extension-protocol/contract-tests/fixtures/registration-loader");

        List<Path> fixtureFiles;
        try (Stream<Path> files = Files.list(fixturesDir)) {
            fixtureFiles = files
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }

        assertFalse(fixtureFiles.isEmpty(), "registration loader protocol fixtures must exist");
        for (Path fixtureFile : fixtureFiles) {
            assertFixture(fixtureFile);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertFixture(Path fixtureFile) throws IOException {
        Map<String, Object> fixture = JSON.readValue(Files.readString(fixtureFile), new TypeReference<>() {});
        String expectedErrorCode = (String) fixture.get("expectedErrorCode");
        if (expectedErrorCode == null) {
            ExtensionRegistrationSet loaded = ExtensionRegistrationLoader.loadYaml(
                (String) fixture.get("operatorYaml"),
                (Map<String, String>) fixture.get("environment")
            );
            Map<String, Object> expected = (Map<String, Object>) fixture.get("expected");
            assertLoadedMatchesExpected(fixtureFile, loaded, expected);

            ExtensionRegistrationSet alternate = ExtensionRegistrationLoader.loadYaml(
                (String) fixture.get("operatorYaml"),
                (Map<String, String>) fixture.get("alternateEnvironment")
            );
            assertLoadedMatchesExpected(fixtureFile, alternate, expected);

            ExtensionRegistrationSet variant = ExtensionRegistrationLoader.loadYaml(
                (String) fixture.get("variantOperatorYaml"),
                (Map<String, String>) fixture.get("alternateEnvironment")
            );
            assertLoadedMatchesExpected(fixtureFile, variant, expected);
            return;
        }

        RegistrationConfigException exception = assertThrows(
            RegistrationConfigException.class,
            () -> ExtensionRegistrationLoader.loadYaml(
                (String) fixture.get("operatorYaml"),
                (Map<String, String>) fixture.get("environment")
            ),
            fixtureFile.toString()
        );
        assertEquals(expectedErrorCode, exception.code().name(), fixtureFile.toString());
    }

    @SuppressWarnings("unchecked")
    private static void assertLoadedMatchesExpected(
        Path fixtureFile,
        ExtensionRegistrationSet loaded,
        Map<String, Object> expected
    ) {
        assertEquals(expected.get("registrationConfigDigest"), loaded.registrationConfigDigest(), fixtureFile + " digest");
        assertEquals(expected.get("canonicalInput"), loaded.canonicalInput(), fixtureFile + " canonical input");

        List<Map<String, Object>> expectedServices = (List<Map<String, Object>>) ((Map<String, Object>) expected.get(
            "canonicalInput"
        )).get("services");
        assertEquals(expectedServices.size(), loaded.services().size(), fixtureFile + " service count");
        assertEquals(
            expectedServices.stream().map(service -> service.get("registrationId")).toList(),
            loaded.services().stream().map(ExtensionRegistration::registrationId).toList(),
            fixtureFile + " service ordering"
        );
    }
}
