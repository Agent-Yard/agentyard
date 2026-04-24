package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.ToolConnectorType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolConnectorCatalog {
    private static final Map<ToolConnectorType, Definition> DEFINITIONS = Map.of(
        ToolConnectorType.SIMPLE_HTTP,
        new Definition(
            ToolConnectorType.SIMPLE_HTTP,
            AccountRequirement.OPTIONAL,
            Map.of("baseUrl", "http://localhost:8081"),
            ToolConnectorCatalog::httpOperationMapping
        ),
        ToolConnectorType.BUSINESS_CODE_SECRET_HTTP,
        new Definition(
            ToolConnectorType.BUSINESS_CODE_SECRET_HTTP,
            AccountRequirement.REQUIRED,
            Map.of("baseUrl", "http://localhost:8081"),
            ToolConnectorCatalog::httpOperationMapping
        ),
        ToolConnectorType.MCP,
        new Definition(
            ToolConnectorType.MCP,
            AccountRequirement.NONE,
            Map.of(
                "serverName", "default-mcp-server",
                "transport", "STREAMABLE_HTTP",
                "connectionUri", "http://localhost:8081/mcp",
                "namespace", "default.namespace",
                "heartbeatSeconds", 30,
                "internalAuthEnabled", false
            ),
            operationName -> Map.of("tool", operationName)
        )
    );

    private ToolConnectorCatalog() {
    }

    static ToolConnectorType defaultType() {
        return ToolConnectorType.SIMPLE_HTTP;
    }

    static Definition definition(ToolConnectorType connectorType) {
        Definition definition = DEFINITIONS.get(connectorType);
        if (definition == null) {
            throw new IllegalArgumentException("unsupported tool connector: " + connectorType);
        }
        return definition;
    }

    static ToolConnectorConfigDto defaultConnectorConfig(List<ToolOperationDto> operations) {
        Definition definition = definition(defaultType());
        Map<String, Map<String, Object>> operationMappings = new LinkedHashMap<>();
        for (ToolOperationDto operation : operations) {
            operationMappings.put(operation.name(), definition.defaultOperationMapping(operation.name()));
        }
        return new ToolConnectorConfigDto(
            definition.connectorType(),
            null,
            15,
            "NONE",
            definition.defaultConfig(),
            Map.copyOf(operationMappings)
        );
    }

    private static Map<String, Object> httpOperationMapping(String operationName) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("method", "POST");
        mapping.put("path", "/tools/" + operationName);
        mapping.put("requestPlacement", "JSON_BODY");
        return Map.copyOf(mapping);
    }

    enum AccountRequirement {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    record Definition(
        ToolConnectorType connectorType,
        AccountRequirement accountRequirement,
        Map<String, Object> defaultConfig,
        java.util.function.Function<String, Map<String, Object>> defaultOperationMapping
    ) {
        Definition {
            defaultConfig = Map.copyOf(defaultConfig);
        }

        boolean requiresAccount() {
            return accountRequirement == AccountRequirement.REQUIRED;
        }

        boolean usesAccount() {
            return accountRequirement != AccountRequirement.NONE;
        }

        Map<String, Object> defaultOperationMapping(String operationName) {
            return Map.copyOf(defaultOperationMapping.apply(operationName));
        }
    }
}
