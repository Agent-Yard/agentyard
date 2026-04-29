from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

from lynxus_extension_sdk.common import canonical_bytes
from lynxus_extension_sdk.protocol import EXTENSION_API_VERSION
from lynxus_extension_sdk.tool import tool_connector_definition_digest

from .extension_protocol import validate_manifest_against_protocol_schema

AGENT_RUNTIME_SERVICE = "agent-runtime"
CORE_MIN_VERSION = "0.8.0"
CORE_MAX_VERSION = "0.9.x"

BUSINESS_CODE_SECRET_HTTP_DESCRIPTOR_ID = "business-code-secret-http"
MCP_DESCRIPTOR_ID = "mcp"
SIMPLE_HTTP_DESCRIPTOR_ID = "simple-http"
BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS = (
    BUSINESS_CODE_SECRET_HTTP_DESCRIPTOR_ID,
    MCP_DESCRIPTOR_ID,
    SIMPLE_HTTP_DESCRIPTOR_ID,
)

_EMPTY_OBJECT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {},
    "additionalProperties": False,
}


class DescriptorProvider:
    def service_manifest(self) -> dict[str, Any]:
        manifest = {
            "extensionApiVersion": EXTENSION_API_VERSION,
            "coreMinVersion": CORE_MIN_VERSION,
            "coreMaxVersion": CORE_MAX_VERSION,
            "descriptors": {
                "channelProviders": [],
                "toolConnectors": [
                    _business_code_secret_http_descriptor(),
                    _mcp_descriptor(),
                    _simple_http_descriptor(),
                ],
            },
        }
        result = validate_manifest_against_protocol_schema(manifest)
        if not result.valid:
            messages = "; ".join(f"{error.code} {error.path}: {error.message}" for error in result.errors)
            raise RuntimeError(f"agent-runtime built-in extension manifest is invalid: {messages}")
        return manifest

    def canonical_manifest_bytes(self) -> bytes:
        return canonical_bytes(self.service_manifest())

    def manifest_json(self) -> str:
        return self.canonical_manifest_bytes().decode("utf-8")

    def tool_connector_descriptors(self) -> tuple[dict[str, Any], ...]:
        return tuple(deepcopy(descriptor) for descriptor in self.service_manifest()["descriptors"]["toolConnectors"])

    def tool_connector_definition_digests(self) -> dict[str, str]:
        return {
            descriptor["connectorType"]: tool_connector_definition_digest(descriptor)
            for descriptor in self.service_manifest()["descriptors"]["toolConnectors"]
        }


def default_descriptor_provider() -> DescriptorProvider:
    return DescriptorProvider()


def _business_code_secret_http_descriptor() -> dict[str, Any]:
    return {
        "connectorType": BUSINESS_CODE_SECRET_HTTP_DESCRIPTOR_ID,
        "title": "Business Code Secret HTTP",
        "description": "Invokes an HTTP endpoint with a business code and HMAC signature.",
        "accountConfigSchema": deepcopy(_EMPTY_OBJECT_SCHEMA),
        "accountConfigUiSchema": [],
        "credentialSchema": _object_schema(
            {
                "businessCode": {"type": "string"},
                "secretKey": {"type": "string"},
            },
            required=["businessCode", "secretKey"],
        ),
        "credentialUiSchema": [],
        "configSchema": _object_schema(
            {
                "baseUrl": {"type": "string"},
                "businessCodeField": {"type": "string"},
                "encryptedField": {"type": "string"},
            }
        ),
        "configUiSchema": [],
        "operationMappingSchema": _http_operation_mapping_schema(),
        "operationMappingUiSchema": [],
        "endpoints": {
            "invoke": "/tools/business-code-secret-http/invoke",
        },
    }


def _mcp_descriptor() -> dict[str, Any]:
    return {
        "connectorType": MCP_DESCRIPTOR_ID,
        "title": "MCP",
        "description": "Invokes a tool exposed through an MCP-compatible HTTP endpoint.",
        "accountConfigSchema": deepcopy(_EMPTY_OBJECT_SCHEMA),
        "accountConfigUiSchema": [],
        "configSchema": _object_schema(
            {
                "connectionUri": {"type": "string"},
                "serverName": {"type": "string"},
                "namespace": {"type": "string"},
                "transport": {"type": "string", "enum": ["STREAMABLE_HTTP"]},
                "internalAuthEnabled": {"type": "boolean"},
            },
            required=["connectionUri"],
        ),
        "configUiSchema": [],
        "operationMappingSchema": _object_schema({"tool": {"type": "string"}}),
        "operationMappingUiSchema": [],
        "endpoints": {
            "invoke": "/tools/mcp/invoke",
        },
    }


def _simple_http_descriptor() -> dict[str, Any]:
    return {
        "connectorType": SIMPLE_HTTP_DESCRIPTOR_ID,
        "title": "Simple HTTP",
        "description": "Invokes an HTTP endpoint with operation arguments as query parameters or JSON body.",
        "accountConfigSchema": deepcopy(_EMPTY_OBJECT_SCHEMA),
        "accountConfigUiSchema": [],
        "credentialSchema": _object_schema(
            {
                "bearerToken": {"type": "string"},
                "token": {"type": "string"},
                "accessToken": {"type": "string"},
            }
        ),
        "credentialUiSchema": [],
        "configSchema": _object_schema(
            {
                "baseUrl": {"type": "string"},
                "authorizationHeader": {"type": "string"},
            }
        ),
        "configUiSchema": [],
        "operationMappingSchema": _http_operation_mapping_schema(),
        "operationMappingUiSchema": [],
        "endpoints": {
            "invoke": "/tools/simple-http/invoke",
        },
    }


def _http_operation_mapping_schema() -> dict[str, Any]:
    return _object_schema(
        {
            "endpoint": {"type": "string"},
            "path": {"type": "string"},
            "method": {"type": "string", "enum": ["GET", "POST", "PUT", "PATCH", "DELETE"]},
            "requestPlacement": {"type": "string", "enum": ["QUERY", "JSON_BODY"]},
        }
    )


def _object_schema(properties: dict[str, Any], *, required: list[str] | None = None) -> dict[str, Any]:
    schema: dict[str, Any] = {
        "type": "object",
        "properties": properties,
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return schema


def manifest_object_from_canonical_bytes(value: bytes) -> dict[str, Any]:
    manifest = json.loads(value.decode("utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest canonical bytes must decode to a JSON object")
    return manifest


__all__ = [
    "AGENT_RUNTIME_SERVICE",
    "BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS",
    "BUSINESS_CODE_SECRET_HTTP_DESCRIPTOR_ID",
    "CORE_MAX_VERSION",
    "CORE_MIN_VERSION",
    "DescriptorProvider",
    "MCP_DESCRIPTOR_ID",
    "SIMPLE_HTTP_DESCRIPTOR_ID",
    "default_descriptor_provider",
    "manifest_object_from_canonical_bytes",
]
