package com.lynxus.extension.sdk.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManifestValidator {
    private static final String MANIFEST_SCHEMA_INVALID = "MANIFEST_SCHEMA_INVALID";
    private static final String SERVICE_MANIFEST_SCHEMA_ID =
        "https://lynxus.dev/schemas/extension-protocol/service-manifest.schema.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CREDENTIAL_ENDPOINTS = Set.of(
        LynxusExtensionProtocol.CREATE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.ROTATE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.REVOKE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.VALIDATE_CREDENTIAL_ENDPOINT
    );
    private static final Set<String> OPTION_COMPONENTS = Set.of("select", "multiSelect", "radio", "checkboxGroup");
    private static final Set<String> VISIBILITY_OPERATORS = Set.of("equals", "notEquals", "in", "notIn", "exists", "notExists");
    private static final List<String> PROTOCOL_SCHEMA_FILES = List.of(
        "assistant-binding.schema.json",
        "channel-outbound-frame.schema.json",
        "channel-outbound-frame-ack.schema.json",
        "channel-outbound-frame-subscription.schema.json",
        "channel-provider-descriptor.schema.json",
        "extension-error.schema.json",
        "external-template-binding.schema.json",
        "job-definition.schema.json",
        "schedule-config.schema.json",
        "service-manifest.schema.json",
        "tool-connector-descriptor.schema.json",
        "ui-field.schema.json"
    );

    private ManifestValidator() {}

    public static ManifestValidationResult validateJson(String rawJson) {
        return validate(JsonDocuments.parse(rawJson), null);
    }

    public static ManifestValidationResult validateJson(String rawJson, Path protocolSchemaDir) {
        return validate(JsonDocuments.parse(rawJson), protocolSchemaDir);
    }

    public static ManifestValidationResult validate(Object manifest) {
        return validate(manifest, null);
    }

    public static ManifestValidationResult validate(Object manifest, Path protocolSchemaDir) {
        List<ManifestValidationError> errors = new ArrayList<>();
        if (protocolSchemaDir != null) {
            Map<String, String> schemas = loadProtocolSchemaAssets(protocolSchemaDir, errors);
            if (schemas != null) {
                validateAgainstServiceManifestSchema(manifest, schemas, errors);
            }
        }
        validateManifest(manifest, errors);
        return new ManifestValidationResult(errors);
    }

    private static Map<String, String> loadProtocolSchemaAssets(Path schemaDir, List<ManifestValidationError> errors) {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (String fileName : PROTOCOL_SCHEMA_FILES) {
            Path schemaFile = schemaDir.resolve(fileName);
            if (!Files.isRegularFile(schemaFile)) {
                add(errors, "PROTOCOL_SCHEMA_ASSET_MISSING", "/schemas/" + fileName, "Protocol JSON Schema asset is missing");
                return null;
            }
            try {
                String rawSchema = Files.readString(schemaFile);
                Map<String, Object> schema = JsonDocuments.parseObject(rawSchema);
                Object schemaId = schema.get("$id");
                if (!(schemaId instanceof String id) || id.isBlank()) {
                    add(errors, "PROTOCOL_SCHEMA_ASSET_INVALID", "/schemas/" + fileName, "Protocol JSON Schema asset is missing $id");
                    return null;
                }
                schemas.put(id, rawSchema);
            } catch (IOException | RuntimeException exception) {
                add(errors, "PROTOCOL_SCHEMA_ASSET_INVALID", "/schemas/" + fileName, "Protocol JSON Schema asset is invalid");
                return null;
            }
        }
        return schemas;
    }

    private static void validateAgainstServiceManifestSchema(
        Object manifest,
        Map<String, String> schemas,
        List<ManifestValidationError> errors
    ) {
        if (!schemas.containsKey(SERVICE_MANIFEST_SCHEMA_ID)) {
            add(
                errors,
                "PROTOCOL_SCHEMA_ASSET_MISSING",
                "/schemas/service-manifest.schema.json",
                "Service manifest schema is missing"
            );
            return;
        }

        try {
            SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(schemas)
            );
            Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SERVICE_MANIFEST_SCHEMA_ID));
            for (com.networknt.schema.Error error : schema.validate(JSON.writeValueAsString(manifest), InputFormat.JSON)) {
                add(errors, MANIFEST_SCHEMA_INVALID, error.getInstanceLocation().toString(), error.getMessage());
            }
        } catch (JsonProcessingException exception) {
            add(errors, MANIFEST_SCHEMA_INVALID, "", "Manifest must be JSON serializable");
        } catch (RuntimeException exception) {
            add(
                errors,
                "PROTOCOL_SCHEMA_ASSET_INVALID",
                "/schemas/service-manifest.schema.json",
                "Service manifest schema could not be evaluated"
            );
        }
    }

    private static void validateManifest(Object value, List<ManifestValidationError> errors) {
        Map<String, Object> manifest = object(value, "", errors);
        if (manifest.isEmpty() && !(value instanceof Map<?, ?>)) {
            return;
        }
        requireIntegerConst(manifest, "extensionApiVersion", LynxusExtensionProtocol.EXTENSION_API_VERSION, errors);
        requireString(manifest, "coreMinVersion", 1, 64, errors);
        requireString(manifest, "coreMaxVersion", 1, 64, errors);

        Map<String, Object> descriptors = object(manifest.get("descriptors"), "/descriptors", errors);
        List<Object> channelProviders = array(descriptors.get("channelProviders"), "/descriptors/channelProviders", errors);
        List<Object> toolConnectors = array(descriptors.get("toolConnectors"), "/descriptors/toolConnectors", errors);
        if (channelProviders.isEmpty() && toolConnectors.isEmpty()) {
            add(errors, "MANIFEST_EMPTY", "/descriptors", "Manifest must expose at least one descriptor");
        }

        for (int index = 0; index < channelProviders.size(); index += 1) {
            validateChannelProvider(
                object(channelProviders.get(index), "/descriptors/channelProviders/" + index, errors),
                "/descriptors/channelProviders/" + index,
                errors
            );
        }
        for (int index = 0; index < toolConnectors.size(); index += 1) {
            validateToolConnector(
                object(toolConnectors.get(index), "/descriptors/toolConnectors/" + index, errors),
                "/descriptors/toolConnectors/" + index,
                errors
            );
        }
    }

    private static void validateChannelProvider(Map<String, Object> descriptor, String path, List<ManifestValidationError> errors) {
        requireString(descriptor, "providerType", 1, 128, errorsAt(path, errors));
        requireString(descriptor, "title", 1, 120, errorsAt(path, errors));
        requireObjectField(descriptor, "accountConfigSchema", path, errors);
        requireArrayField(descriptor, "accountConfigUiSchema", path, errors);
        requireObjectField(descriptor, "configSchema", path, errors);
        requireArrayField(descriptor, "configUiSchema", path, errors);
        validateChannelProviderOutbound(requireObjectField(descriptor, "outbound", path, errors), path + "/outbound", errors);

        Map<String, Object> endpoints = requireObjectField(descriptor, "endpoints", path, errors);
        validateCredentialEndpointCompleteness(descriptor, endpoints, path, errors);

        validateUiPair(
            objectOrEmpty(descriptor.get("accountConfigSchema")),
            arrayOrEmpty(descriptor.get("accountConfigUiSchema")),
            false,
            path + "/accountConfigUiSchema",
            errors
        );
        validateUiPair(
            objectOrEmpty(descriptor.get("configSchema")),
            arrayOrEmpty(descriptor.get("configUiSchema")),
            false,
            path + "/configUiSchema",
            errors
        );
        if (descriptor.containsKey("credentialUiSchema")) {
            validateUiPair(
                objectOrEmpty(descriptor.get("credentialSchema")),
                arrayOrEmpty(descriptor.get("credentialUiSchema")),
                true,
                path + "/credentialUiSchema",
                errors
            );
        }
        if (containsSecretMarker(descriptor.get("defaultConfig"))) {
            add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", path + "/defaultConfig", "Normal config defaults must not contain secret=true");
        }

        List<Object> jobs = arrayOrEmpty(descriptor.get("jobDefinitions"));
        for (int index = 0; index < jobs.size(); index += 1) {
            Map<String, Object> job = object(jobs.get(index), path + "/jobDefinitions/" + index, errors);
            requireString(job, "jobType", 1, 128, errorsAt(path + "/jobDefinitions/" + index, errors));
            requireString(job, "title", 1, 120, errorsAt(path + "/jobDefinitions/" + index, errors));
            validateUiPair(
                objectOrEmpty(job.get("jobConfigSchema")),
                arrayOrEmpty(job.get("jobConfigUiSchema")),
                false,
                path + "/jobDefinitions/" + index + "/jobConfigUiSchema",
                errors
            );
            Map<String, Object> defaultSchedule = objectOrEmpty(job.get("defaultSchedule"));
            if (containsSecretMarker(defaultSchedule.get("jobConfig"))) {
                add(
                    errors,
                    "UI_SCHEMA_SECRET_NOT_ALLOWED",
                    path + "/jobDefinitions/" + index + "/defaultSchedule/jobConfig",
                    "Job config defaults must not contain secret=true"
                );
            }
        }
    }

    private static void validateChannelProviderOutbound(Map<String, Object> outbound, String path, List<ManifestValidationError> errors) {
        requireString(outbound, "mode", 1, 64, errorsAt(path, errors));
        for (String field : Set.of(
            "supportsTyping",
            "supportsDraftUpdate",
            "supportsFinalDelivery",
            "supportsCredentialRef",
            "requiresIdempotentFinalDelivery"
        )) {
            if (!(outbound.get(field) instanceof Boolean)) {
                add(errors, MANIFEST_SCHEMA_INVALID, path + "/" + field, "Outbound capability field must be boolean");
            }
        }
        if (!"FRAME_STREAM".equals(outbound.get("mode"))) {
            add(errors, MANIFEST_SCHEMA_INVALID, path + "/mode", "outbound.mode must be FRAME_STREAM");
        }
        if (!Boolean.TRUE.equals(outbound.get("supportsFinalDelivery"))) {
            add(errors, MANIFEST_SCHEMA_INVALID, path + "/supportsFinalDelivery", "outbound.supportsFinalDelivery must be true");
        }
        if (!Boolean.TRUE.equals(outbound.get("requiresIdempotentFinalDelivery"))) {
            add(
                errors,
                MANIFEST_SCHEMA_INVALID,
                path + "/requiresIdempotentFinalDelivery",
                "outbound.requiresIdempotentFinalDelivery must be true"
            );
        }
    }

    private static void validateToolConnector(Map<String, Object> descriptor, String path, List<ManifestValidationError> errors) {
        requireString(descriptor, "connectorType", 1, 128, errorsAt(path, errors));
        requireString(descriptor, "title", 1, 120, errorsAt(path, errors));
        requireObjectField(descriptor, "accountConfigSchema", path, errors);
        requireArrayField(descriptor, "accountConfigUiSchema", path, errors);
        requireObjectField(descriptor, "configSchema", path, errors);
        requireArrayField(descriptor, "configUiSchema", path, errors);
        requireObjectField(descriptor, "operationMappingSchema", path, errors);
        requireArrayField(descriptor, "operationMappingUiSchema", path, errors);

        Map<String, Object> endpoints = requireObjectField(descriptor, "endpoints", path, errors);
        validateDeclaredEndpoint(endpoints, LynxusExtensionProtocol.TOOL_CONNECTOR_INVOKE_ENDPOINT, path + "/endpoints", errors);
        validateCredentialEndpointCompleteness(descriptor, endpoints, path, errors);

        validateUiPair(
            objectOrEmpty(descriptor.get("accountConfigSchema")),
            arrayOrEmpty(descriptor.get("accountConfigUiSchema")),
            false,
            path + "/accountConfigUiSchema",
            errors
        );
        validateUiPair(
            objectOrEmpty(descriptor.get("configSchema")),
            arrayOrEmpty(descriptor.get("configUiSchema")),
            false,
            path + "/configUiSchema",
            errors
        );
        validateUiPair(
            objectOrEmpty(descriptor.get("operationMappingSchema")),
            arrayOrEmpty(descriptor.get("operationMappingUiSchema")),
            false,
            path + "/operationMappingUiSchema",
            errors
        );
        if (descriptor.containsKey("credentialUiSchema")) {
            validateUiPair(
                objectOrEmpty(descriptor.get("credentialSchema")),
                arrayOrEmpty(descriptor.get("credentialUiSchema")),
                true,
                path + "/credentialUiSchema",
                errors
            );
        }
    }

    private static void validateCredentialEndpointCompleteness(
        Map<String, Object> descriptor,
        Map<String, Object> endpoints,
        String path,
        List<ManifestValidationError> errors
    ) {
        boolean hasAnyCredentialEndpoint = CREDENTIAL_ENDPOINTS.stream().anyMatch(endpoints::containsKey);
        if (hasAnyCredentialEndpoint && (!endpoints.keySet().containsAll(CREDENTIAL_ENDPOINTS) || !descriptor.containsKey("credentialSchema"))) {
            add(
                errors,
                "CREDENTIAL_ENDPOINTS_INCOMPLETE",
                path + "/endpoints",
                "Credential endpoints require credentialSchema and all four lifecycle endpoint paths"
            );
        }
        for (String endpoint : CREDENTIAL_ENDPOINTS) {
            if (endpoints.containsKey(endpoint)) {
                validateDeclaredEndpoint(endpoints, endpoint, path + "/endpoints", errors);
            }
        }
    }

    private static void validateDeclaredEndpoint(
        Map<String, Object> endpoints,
        String key,
        String path,
        List<ManifestValidationError> errors
    ) {
        Object raw = endpoints.get(key);
        if (!(raw instanceof String value) || !value.startsWith("/") || value.contains("?") || value.contains("#")) {
            add(errors, MANIFEST_SCHEMA_INVALID, path + "/" + key, "Endpoint path must match ^/[^?#]*$");
        }
    }

    private static void validateUiPair(
        Map<String, Object> dataSchema,
        List<Object> uiSchema,
        boolean credentialUi,
        String path,
        List<ManifestValidationError> errors
    ) {
        if (!credentialUi && containsSecretMarker(dataSchema)) {
            add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", path.replace("UiSchema", "Schema"), "Normal config schema must not contain secret=true");
        }

        Set<String> seenKeys = new HashSet<>();
        for (int index = 0; index < uiSchema.size(); index += 1) {
            String fieldPath = path + "/" + index;
            Map<String, Object> field = object(uiSchema.get(index), fieldPath, errors);
            Object rawKey = field.get("key");
            if (!(rawKey instanceof String key)) {
                add(errors, MANIFEST_SCHEMA_INVALID, fieldPath + "/key", "UI field key is required");
                continue;
            }
            if (!seenKeys.add(key)) {
                add(errors, MANIFEST_SCHEMA_INVALID, fieldPath + "/key", "UI field key must be unique");
            }

            PointerTarget target = resolveSchemaPointer(dataSchema, key);
            if (target == null) {
                add(errors, "UI_SCHEMA_PROPERTY_NOT_FOUND", fieldPath + "/key", "UI field key must reference a JSON Schema property");
                continue;
            }

            if (!credentialUi && (Boolean.TRUE.equals(field.get("secret")) || "password".equals(field.get("component")))) {
                add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", fieldPath, "Secret UI controls are only allowed for credentialUiSchema");
            }

            if (Boolean.TRUE.equals(field.get("required")) && !isRequired(target.parentSchema(), target.propertyName())) {
                add(
                    errors,
                    "UI_SCHEMA_REQUIRED_NOT_AUTHORITATIVE",
                    fieldPath + "/required",
                    "UI required=true must repeat JSON Schema required"
                );
            }

            if (field.containsKey("visibilityCondition")) {
                validateVisibilityCondition(field.get("visibilityCondition"), dataSchema, target, fieldPath, errors);
            }

            if (field.containsKey("options") || OPTION_COMPONENTS.contains(field.get("component"))) {
                validateOptions(field, target.propertySchema(), fieldPath, errors);
            }
        }
    }

    private static void validateVisibilityCondition(
        Object value,
        Map<String, Object> dataSchema,
        PointerTarget target,
        String fieldPath,
        List<ManifestValidationError> errors
    ) {
        Map<String, Object> condition = object(value, fieldPath + "/visibilityCondition", errors);
        Object rawField = condition.get("field");
        if (!(rawField instanceof String conditionField) || resolveSchemaPointer(dataSchema, conditionField) == null) {
            add(
                errors,
                "UI_SCHEMA_PROPERTY_NOT_FOUND",
                fieldPath + "/visibilityCondition/field",
                "visibilityCondition.field must reference a JSON Schema property"
            );
        }
        Object operator = condition.get("operator");
        if (!(operator instanceof String operatorValue) || !VISIBILITY_OPERATORS.contains(operatorValue)) {
            add(errors, MANIFEST_SCHEMA_INVALID, fieldPath + "/visibilityCondition/operator", "Unsupported visibility operator");
        } else if (("in".equals(operatorValue) || "notIn".equals(operatorValue)) && arrayOrEmpty(condition.get("value")).isEmpty()) {
            add(errors, MANIFEST_SCHEMA_INVALID, fieldPath + "/visibilityCondition/value", "in/notIn visibility value must be non-empty array");
        }
        if (isRequired(target.parentSchema(), target.propertyName())) {
            add(
                errors,
                "UI_SCHEMA_VISIBILITY_REQUIRED_CONFLICT",
                fieldPath + "/visibilityCondition",
                "Unconditionally required fields must not be hidden by UI visibility"
            );
        }
    }

    private static void validateOptions(
        Map<String, Object> field,
        Map<String, Object> propertySchema,
        String fieldPath,
        List<ManifestValidationError> errors
    ) {
        List<Object> expectedValues = staticEnumValues(propertySchema);
        List<Object> optionValues = new ArrayList<>();
        for (Object optionValue : arrayOrEmpty(field.get("options"))) {
            Map<String, Object> option = object(optionValue, fieldPath + "/options", errors);
            optionValues.add(option.get("value"));
        }
        if (expectedValues == null || !expectedValues.equals(optionValues)) {
            add(errors, "UI_SCHEMA_OPTIONS_DRIFT", fieldPath + "/options", "UI options must match JSON Schema static enum values");
        }
    }

    private static List<Object> staticEnumValues(Map<String, Object> propertySchema) {
        if (propertySchema.get("enum") instanceof List<?> enumValues) {
            return new ArrayList<>(enumValues);
        }
        if (propertySchema.get("oneOf") instanceof List<?> oneOf) {
            List<Object> values = new ArrayList<>();
            for (Object item : oneOf) {
                Map<String, Object> branch = objectOrEmpty(item);
                if (!branch.containsKey("const")) {
                    return null;
                }
                values.add(branch.get("const"));
            }
            return values;
        }
        return null;
    }

    private static PointerTarget resolveSchemaPointer(Map<String, Object> schema, String pointer) {
        if (pointer == null || !pointer.startsWith("/") || pointer.equals("/")) {
            return null;
        }
        Map<String, Object> current = schema;
        String[] rawParts = pointer.substring(1).split("/", -1);
        for (int index = 0; index < rawParts.length; index += 1) {
            String rawPart = rawParts[index];
            String part = decodePointerPart(rawPart);
            Map<String, Object> properties = objectOrEmpty(current.get("properties"));
            Object next = properties.get(part);
            if (!(next instanceof Map<?, ?> nextSchemaRaw)) {
                return null;
            }
            Map<String, Object> nextSchema = JsonDocuments.asObject(nextSchemaRaw, pointer);
            if (index == rawParts.length - 1) {
                return new PointerTarget(current, part, nextSchema);
            }
            current = nextSchema;
        }
        return null;
    }

    private static String decodePointerPart(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static boolean isRequired(Map<String, Object> schema, String propertyName) {
        return arrayOrEmpty(schema.get("required")).stream().anyMatch(item -> Objects.equals(item, propertyName));
    }

    private static boolean containsSecretMarker(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("secret".equals(entry.getKey()) && Boolean.TRUE.equals(entry.getValue())) {
                    return true;
                }
                if (containsSecretMarker(entry.getValue())) {
                    return true;
                }
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(ManifestValidator::containsSecretMarker);
        }
        return false;
    }

    private static Map<String, Object> requireObjectField(
        Map<String, Object> value,
        String field,
        String path,
        List<ManifestValidationError> errors
    ) {
        if (!value.containsKey(field)) {
            add(errors, MANIFEST_SCHEMA_INVALID, path + "/" + field, "Required object field is missing");
            return Map.of();
        }
        return object(value.get(field), path + "/" + field, errors);
    }

    private static List<Object> requireArrayField(
        Map<String, Object> value,
        String field,
        String path,
        List<ManifestValidationError> errors
    ) {
        if (!value.containsKey(field)) {
            add(errors, MANIFEST_SCHEMA_INVALID, path + "/" + field, "Required array field is missing");
            return List.of();
        }
        return array(value.get(field), path + "/" + field, errors);
    }

    private static void requireString(
        Map<String, Object> value,
        String field,
        int minLength,
        int maxLength,
        List<ManifestValidationError> errors
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof String stringValue) || stringValue.length() < minLength || stringValue.length() > maxLength) {
            add(errors, MANIFEST_SCHEMA_INVALID, "/" + field, "Required string field is invalid");
        }
    }

    private static void requireIntegerConst(
        Map<String, Object> value,
        String field,
        int expected,
        List<ManifestValidationError> errors
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof Number numberValue) || numberValue.intValue() != expected) {
            add(errors, MANIFEST_SCHEMA_INVALID, "/" + field, "Required integer const is invalid");
        }
    }

    private static List<ManifestValidationError> errorsAt(String path, List<ManifestValidationError> errors) {
        return new PathAwareErrors(path, errors);
    }

    private static Map<String, Object> object(Object value, String path, List<ManifestValidationError> errors) {
        if (value instanceof Map<?, ?> map) {
            return JsonDocuments.asObject(map, path);
        }
        add(errors, MANIFEST_SCHEMA_INVALID, path, "Expected object");
        return Map.of();
    }

    private static List<Object> array(Object value, String path, List<ManifestValidationError> errors) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        add(errors, MANIFEST_SCHEMA_INVALID, path, "Expected array");
        return List.of();
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        if (value instanceof Map<?, ?> map) {
            return JsonDocuments.asObject(map, "");
        }
        return Map.of();
    }

    private static List<Object> arrayOrEmpty(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static void add(List<ManifestValidationError> errors, String code, String path, String message) {
        errors.add(new ManifestValidationError(code, path, message));
    }

    private record PointerTarget(
        Map<String, Object> parentSchema,
        String propertyName,
        Map<String, Object> propertySchema
    ) {}

    private static final class PathAwareErrors extends ArrayList<ManifestValidationError> {
        private final String prefix;
        private final List<ManifestValidationError> delegate;

        private PathAwareErrors(String prefix, List<ManifestValidationError> delegate) {
            this.prefix = prefix;
            this.delegate = delegate;
        }

        @Override
        public boolean add(ManifestValidationError error) {
            return delegate.add(new ManifestValidationError(error.code(), prefix + error.path(), error.message()));
        }
    }
}
