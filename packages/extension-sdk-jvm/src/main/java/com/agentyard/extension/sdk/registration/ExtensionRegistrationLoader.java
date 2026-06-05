package com.agentyard.extension.sdk.registration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.agentyard.extension.sdk.common.AgentYardCanonicalJson;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtensionRegistrationLoader {
    public static final String CORE_CHANNEL_GATEWAY_REGISTRATION_ID = "core-channel-gateway";
    public static final String CORE_AGENT_RUNTIME_REGISTRATION_ID = "core-agent-runtime";
    public static final String CHANNEL_GATEWAY_BASE_URL_ENV = "AGENTYARD_CHANNEL_GATEWAY_BASE_URL";
    public static final String AGENT_RUNTIME_BASE_URL_ENV = "AGENTYARD_AGENT_RUNTIME_BASE_URL";

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory().enable(
        JsonParser.Feature.STRICT_DUPLICATE_DETECTION
    ));
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    private ExtensionRegistrationLoader() {}

    public static ExtensionRegistrationSet load(Path path) {
        return load(path, System::getenv);
    }

    public static ExtensionRegistrationSet load(Path path, Map<String, String> environment) {
        return load(path, environment::get);
    }

    public static ExtensionRegistrationSet load(Path path, Function<String, String> environmentResolver) {
        try {
            return loadYaml(Files.readString(path), environmentResolver);
        } catch (IOException exception) {
            throw unavailable("Registration config is unavailable: " + path, exception);
        }
    }

    public static ExtensionRegistrationSet loadYaml(String yaml, Map<String, String> environment) {
        return loadYaml(yaml, environment::get);
    }

    public static ExtensionRegistrationSet loadYaml(String yaml, Function<String, String> environmentResolver) {
        Objects.requireNonNull(environmentResolver, "environmentResolver");
        List<ExtensionRegistration> services = new ArrayList<>();
        services.addAll(operatorRegistrations(yaml, environmentResolver));
        services.add(coreChannelGatewayPreset(environmentResolver));
        services.add(coreAgentRuntimePreset(environmentResolver));

        services.sort(Comparator.comparing(ExtensionRegistration::registrationId));
        Map<String, Object> canonicalInput = registrationConfigDigestInput(services);
        return new ExtensionRegistrationSet(
            services,
            AgentYardCanonicalJson.sha256ValueDigest(canonicalInput),
            canonicalInput
        );
    }

    public static Map<String, Object> registrationConfigDigestInput(List<ExtensionRegistration> registrations) {
        List<ExtensionRegistration> sorted = registrations.stream()
            .sorted(Comparator.comparing(ExtensionRegistration::registrationId))
            .toList();
        List<Map<String, Object>> services = new ArrayList<>();
        for (ExtensionRegistration registration : sorted) {
            Map<String, Object> service = new LinkedHashMap<>();
            service.put("registrationId", registration.registrationId());
            service.put("source", registration.source().name());
            service.put("baseUrl", registration.baseUrl());

            Map<String, Object> exposes = new LinkedHashMap<>();
            exposes.put("channelProviderTypes", sortedUnique(registration.exposes().channelProviderTypes()));
            exposes.put("toolConnectorTypes", sortedUnique(registration.exposes().toolConnectorTypes()));
            service.put("exposes", exposes);

            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("type", registration.auth().type().name());
            service.put("auth", auth);
            services.add(service);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        return result;
    }

    public static String registrationConfigDigest(List<ExtensionRegistration> registrations) {
        return AgentYardCanonicalJson.sha256ValueDigest(registrationConfigDigestInput(registrations));
    }

    public static String normalizeBaseUrl(String value) {
        return normalizeBaseUrl(value, name -> null);
    }

    public static String normalizeBaseUrl(String value, Map<String, String> environment) {
        return normalizeBaseUrl(value, environment::get);
    }

    public static String normalizeBaseUrl(String value, Function<String, String> environmentResolver) {
        String resolved = resolvePlaceholder(requiredText(value, "baseUrl"), environmentResolver);
        try {
            URI uri = new URI(resolved);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw invalid("baseUrl scheme must be http or https");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getHost() == null) {
                throw invalid("baseUrl must not contain userinfo, query, or fragment");
            }

            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String hostForAuthority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
            int port = uri.getPort();
            boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            String authority = port < 0 || defaultPort ? hostForAuthority : hostForAuthority + ":" + port;
            String rawPath = uri.getRawPath();
            String path = rawPath == null || rawPath.equals("/") ? "" : rawPath.replaceFirst("/+$", "");
            return scheme + "://" + authority + path;
        } catch (URISyntaxException exception) {
            throw invalid("Invalid baseUrl " + value, exception);
        }
    }

    private static List<ExtensionRegistration> operatorRegistrations(String yaml, Function<String, String> environmentResolver) {
        JsonNode root = parseYaml(yaml);
        if (root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        requireObject(root, "root");
        JsonNode agentyardNode = root.path("agentyard");
        if (agentyardNode.isMissingNode() || agentyardNode.isNull()) {
            return List.of();
        }
        requireObject(agentyardNode, "agentyard");
        JsonNode extensionsNode = agentyardNode.path("extensions");
        if (extensionsNode.isMissingNode() || extensionsNode.isNull()) {
            return List.of();
        }
        requireObject(extensionsNode, "agentyard.extensions");
        JsonNode servicesNode = extensionsNode.path("services");
        if (servicesNode.isMissingNode() || servicesNode.isNull()) {
            return List.of();
        }
        if (!servicesNode.isArray()) {
            throw invalid("agentyard.extensions.services must be a list");
        }

        List<ExtensionRegistration> registrations = new ArrayList<>();
        Set<String> registrationIds = new HashSet<>();
        for (JsonNode serviceNode : servicesNode) {
            ExtensionRegistration registration = operatorRegistration(serviceNode, environmentResolver);
            if (!registrationIds.add(registration.registrationId())) {
                throw invalid("Duplicate registrationId " + registration.registrationId());
            }
            registrations.add(registration);
        }
        return registrations;
    }

    private static ExtensionRegistration operatorRegistration(JsonNode serviceNode, Function<String, String> environmentResolver) {
        requireObject(serviceNode, "service");
        requireOnlyFields(serviceNode, Set.of("registrationId", "baseUrl", "exposes", "auth"), "service");
        String registrationId = requiredText(serviceNode.path("registrationId"), "registrationId");
        if (registrationId.startsWith("core-")) {
            throw invalid("Operator registrationId must not use reserved core- prefix: " + registrationId);
        }

        RegistrationExposes exposes = exposes(serviceNode.path("exposes"));
        if (exposes.isEmpty()) {
            throw invalid("Registration must expose at least one descriptor type: " + registrationId);
        }
        return new ExtensionRegistration(
            registrationId,
            RegistrationSource.OPERATOR_YAML,
            normalizeBaseUrl(requiredText(serviceNode.path("baseUrl"), "baseUrl"), environmentResolver),
            exposes,
            auth(serviceNode.path("auth"))
        );
    }

    private static ExtensionRegistration coreChannelGatewayPreset(Function<String, String> environmentResolver) {
        return new ExtensionRegistration(
            CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
            RegistrationSource.CORE_PRESET,
            normalizeBaseUrl("${" + CHANNEL_GATEWAY_BASE_URL_ENV + "}", environmentResolver),
            new RegistrationExposes(List.of("feishu"), List.of()),
            new RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN)
        );
    }

    private static ExtensionRegistration coreAgentRuntimePreset(Function<String, String> environmentResolver) {
        return new ExtensionRegistration(
            CORE_AGENT_RUNTIME_REGISTRATION_ID,
            RegistrationSource.CORE_PRESET,
            normalizeBaseUrl("${" + AGENT_RUNTIME_BASE_URL_ENV + "}", environmentResolver),
            new RegistrationExposes(List.of(), List.of("business-code-secret-http", "mcp", "simple-http")),
            new RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN)
        );
    }

    private static JsonNode parseYaml(String yaml) {
        try {
            JsonNode root = YAML_MAPPER.readTree(yaml == null ? "" : yaml);
            return root == null ? YAML_MAPPER.nullNode() : root;
        } catch (IOException exception) {
            throw invalid("Registration YAML is invalid", exception);
        }
    }

    private static RegistrationExposes exposes(JsonNode exposesNode) {
        if (exposesNode.isMissingNode() || exposesNode.isNull()) {
            return new RegistrationExposes(List.of(), List.of());
        }
        requireObject(exposesNode, "exposes");
        requireOnlyFields(exposesNode, Set.of("channelProviderTypes", "toolConnectorTypes"), "exposes");
        return new RegistrationExposes(
            stringList(exposesNode.path("channelProviderTypes"), "exposes.channelProviderTypes"),
            stringList(exposesNode.path("toolConnectorTypes"), "exposes.toolConnectorTypes")
        );
    }

    private static RegistrationAuth auth(JsonNode authNode) {
        requireObject(authNode, "auth");
        requireOnlyFields(authNode, Set.of("type"), "auth");
        String type = requiredText(authNode.path("type"), "auth.type");
        if (!RegistrationAuthType.INTERNAL_TOKEN.name().equals(type)) {
            throw invalid("Unsupported auth.type " + type);
        }
        return new RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN);
    }

    private static List<String> stringList(JsonNode node, String label) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalid(label + " must be a list");
        }
        TreeSet<String> values = new TreeSet<>();
        for (JsonNode item : node) {
            values.add(requiredText(item, label + "[]"));
        }
        return List.copyOf(values);
    }

    private static List<String> sortedUnique(List<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static String resolvePlaceholder(String value, Function<String, String> environmentResolver) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        if (!matcher.matches()) {
            if (value.contains("${")) {
                throw invalid("baseUrl placeholder must occupy the full value");
            }
            return value;
        }
        String key = matcher.group(1);
        String resolved = environmentResolver.apply(key);
        if (resolved == null || resolved.isBlank()) {
            throw invalid("Missing environment value for " + key);
        }
        return resolved.trim();
    }

    private static void requireObject(JsonNode node, String label) {
        if (!node.isObject()) {
            throw invalid(label + " must be an object");
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowedFields, String label) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw invalid(label + " contains unsupported field " + field);
            }
        });
    }

    private static String requiredText(JsonNode node, String label) {
        if (!node.isTextual()) {
            throw invalid(label + " must be a string");
        }
        return requiredText(node.textValue(), label);
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + " must not be blank");
        }
        return value.trim();
    }

    private static RegistrationConfigException unavailable(String message, Throwable cause) {
        return new RegistrationConfigException(RegistrationConfigErrorCode.REGISTRATION_CONFIG_UNAVAILABLE, message, cause);
    }

    private static RegistrationConfigException invalid(String message) {
        return new RegistrationConfigException(RegistrationConfigErrorCode.REGISTRATION_CONFIG_INVALID, message);
    }

    private static RegistrationConfigException invalid(String message, Throwable cause) {
        return new RegistrationConfigException(RegistrationConfigErrorCode.REGISTRATION_CONFIG_INVALID, message, cause);
    }
}
