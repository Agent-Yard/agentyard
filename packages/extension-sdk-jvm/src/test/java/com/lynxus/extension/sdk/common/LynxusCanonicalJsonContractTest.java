package com.lynxus.extension.sdk.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class LynxusCanonicalJsonContractTest {
    @Test
    void canonicalJsonFixturesMatchProtocolContract() throws IOException {
        Path fixturesDir = Path.of(System.getProperty("lynxus.repo.root"))
            .resolve("packages/extension-protocol/contract-tests/fixtures/canonical-json");

        List<Path> fixtureFiles;
        try (Stream<Path> files = Files.list(fixturesDir)) {
            fixtureFiles = files
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }

        assertFalse(fixtureFiles.isEmpty(), "canonical JSON protocol fixtures must exist");
        for (Path file : fixtureFiles) {
            assertFixture(file);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertFixture(Path file) throws IOException {
        Map<String, Object> fixture = (Map<String, Object>) LynxusCanonicalJson.parse(Files.readString(file));
        String expectedErrorCode = (String) fixture.get("expectedErrorCode");
        try {
            Object value = fixtureValue(fixture);
            String canonical = LynxusCanonicalJson.canonicalizeValue(value);
            if (expectedErrorCode != null) {
                fail(file + " expected " + expectedErrorCode + ", got valid canonical JSON");
            }
            assertEquals(fixture.get("expectedCanonicalUtf8Hex"), utf8Hex(canonical), file + " canonical hex");
            assertEquals(fixture.get("expectedDigest"), LynxusCanonicalJson.sha256ValueDigest(value), file + " digest");
            assertDescriptorDigestHelper(fixture, file);
        } catch (LynxusCanonicalJsonException exception) {
            if (expectedErrorCode == null) {
                fail(file + " unexpected canonical JSON error " + exception.code(), exception);
            }
            assertEquals(expectedErrorCode, exception.code(), file + " error code");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertDescriptorDigestHelper(Map<String, Object> fixture, Path file) {
        String type = (String) fixture.get("type");
        if ("channelProviderDefinitionDigest".equals(type)) {
            assertEquals(
                fixture.get("expectedDigest"),
                DescriptorDefinitionDigests.channelProviderDefinitionDigest((Map<String, Object>) fixture.get("input")),
                file + " channel provider helper digest"
            );
        }
        if ("toolConnectorDefinitionDigest".equals(type)) {
            assertEquals(
                fixture.get("expectedDigest"),
                DescriptorDefinitionDigests.toolConnectorDefinitionDigest((Map<String, Object>) fixture.get("input")),
                file + " tool connector helper digest"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Object fixtureValue(Map<String, Object> fixture) {
        String type = (String) fixture.get("type");
        if ("registrationConfigDigest".equals(type)) {
            return normalizeRegistrationConfig((Map<String, Object>) fixture.get("input"));
        }
        if ("channelProviderDefinitionDigest".equals(type)) {
            return DescriptorDefinitionDigests.channelProviderDefinitionDigestInput((Map<String, Object>) fixture.get("input"));
        }
        if ("toolConnectorDefinitionDigest".equals(type)) {
            return DescriptorDefinitionDigests.toolConnectorDefinitionDigestInput((Map<String, Object>) fixture.get("input"));
        }
        return LynxusCanonicalJson.parse((String) fixture.get("input"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeRegistrationConfig(Map<String, Object> input) {
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map<String, Object> service : (List<Map<String, Object>>) input.getOrDefault("services", List.of())) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("registrationId", service.get("registrationId"));
            normalized.put("source", service.get("source"));
            normalized.put("baseUrl", normalizeBaseUrl((String) service.get("baseUrl")));

            Map<String, Object> serviceExposes = (Map<String, Object>) service.getOrDefault("exposes", Map.of());
            Map<String, Object> exposes = new LinkedHashMap<>();
            exposes.put("channelProviderTypes", sortedStrings(serviceExposes.get("channelProviderTypes")));
            exposes.put("toolConnectorTypes", sortedStrings(serviceExposes.get("toolConnectorTypes")));
            normalized.put("exposes", exposes);

            Map<String, Object> serviceAuth = (Map<String, Object>) service.getOrDefault("auth", Map.of());
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("type", serviceAuth.get("type"));
            normalized.put("auth", auth);
            services.add(normalized);
        }
        services.sort(Comparator.comparing(service -> (String) service.get("registrationId")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        return result;
    }

    private static String normalizeBaseUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("REGISTRATION_CONFIG_INVALID");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null) {
                throw new IllegalArgumentException("REGISTRATION_CONFIG_INVALID");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            String authority = port < 0 || defaultPort ? host : host + ":" + port;
            String path = uri.getPath() == null || uri.getPath().equals("/") ? "" : uri.getPath().replaceFirst("/+$", "");
            return scheme + "://" + authority + path;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("REGISTRATION_CONFIG_INVALID", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> sortedStrings(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> strings = new ArrayList<>((List<String>) value);
        strings.sort(Comparator.naturalOrder());
        return strings;
    }

    private static String utf8Hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
}
