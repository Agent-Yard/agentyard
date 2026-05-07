"""Descriptor definition digest helpers."""

from __future__ import annotations

from typing import Any

from lynxus_extension_sdk.common.canonical_json import canonical_bytes, canonicalize, sha256_digest

CHANNEL_PROVIDER_DESCRIPTOR_TYPE = "CHANNEL_PROVIDER"
TOOL_CONNECTOR_DESCRIPTOR_TYPE = "TOOL_CONNECTOR"
_SCHEMA_ANNOTATION_KEYWORDS = {"title", "description", "default"}
_SCHEMA_MAP_KEYWORDS = {
    "properties",
    "$defs",
    "definitions",
    "patternProperties",
    "dependentSchemas",
}


def channel_provider_definition_digest_input(
    descriptor: dict[str, Any],
    credential_lifecycle_endpoint_profiles: dict[str, Any] | None = None,
) -> dict[str, Any]:
    job_definitions = [
        {
            "jobType": job.get("jobType"),
            "jobConfigSchema": validation_only_schema(job.get("jobConfigSchema")),
        }
        for job in descriptor.get("jobDefinitions") or []
    ]
    job_definitions.sort(key=lambda job: _utf16_sort_key(job["jobType"]))

    endpoints = descriptor.get("endpoints") or {}
    outbound = descriptor.get("outbound") or {}
    return {
        "descriptorType": CHANNEL_PROVIDER_DESCRIPTOR_TYPE,
        "providerType": descriptor.get("providerType"),
        "accountConfigSchema": validation_only_schema(descriptor.get("accountConfigSchema")),
        "credentialSchema": validation_only_schema(descriptor.get("credentialSchema")),
        "outbound": {
            "mode": outbound.get("mode"),
            "requiresIdempotentFinalDelivery": outbound.get("requiresIdempotentFinalDelivery") is True,
            "supportsDraftUpdate": outbound.get("supportsDraftUpdate") is True,
            "supportsFinalDelivery": outbound.get("supportsFinalDelivery") is True,
            "supportsTyping": outbound.get("supportsTyping") is True,
        },
        "endpoints": {
            "runJob": endpoints.get("runJob"),
        },
        "credentialLifecycleEndpointProfile": descriptor.get("credentialLifecycleEndpointProfile"),
        "credentialLifecycleEndpoints": _credential_lifecycle_endpoints(
            descriptor,
            credential_lifecycle_endpoint_profiles or {},
        ),
        "configSchema": validation_only_schema(descriptor.get("configSchema")),
        "jobDefinitions": job_definitions,
    }


def channel_provider_definition_digest(
    descriptor: dict[str, Any],
    credential_lifecycle_endpoint_profiles: dict[str, Any] | None = None,
) -> str:
    return sha256_digest(channel_provider_definition_digest_input(descriptor, credential_lifecycle_endpoint_profiles))


def channel_provider_definition_canonical_json(descriptor: dict[str, Any]) -> str:
    return canonicalize(channel_provider_definition_digest_input(descriptor))


def channel_provider_definition_canonical_bytes(descriptor: dict[str, Any]) -> bytes:
    return canonical_bytes(channel_provider_definition_digest_input(descriptor))


def tool_connector_definition_digest_input(
    descriptor: dict[str, Any],
    credential_lifecycle_endpoint_profiles: dict[str, Any] | None = None,
) -> dict[str, Any]:
    endpoints = descriptor.get("endpoints") or {}
    return {
        "descriptorType": TOOL_CONNECTOR_DESCRIPTOR_TYPE,
        "connectorType": descriptor.get("connectorType"),
        "accountConfigSchema": validation_only_schema(descriptor.get("accountConfigSchema")),
        "credentialSchema": validation_only_schema(descriptor.get("credentialSchema")),
        "configSchema": validation_only_schema(descriptor.get("configSchema")),
        "operationMappingSchema": validation_only_schema(descriptor.get("operationMappingSchema")),
        "endpoints": {
            "invoke": endpoints.get("invoke"),
        },
        "credentialLifecycleEndpointProfile": descriptor.get("credentialLifecycleEndpointProfile"),
        "credentialLifecycleEndpoints": _credential_lifecycle_endpoints(
            descriptor,
            credential_lifecycle_endpoint_profiles or {},
        ),
    }


def tool_connector_definition_digest(
    descriptor: dict[str, Any],
    credential_lifecycle_endpoint_profiles: dict[str, Any] | None = None,
) -> str:
    return sha256_digest(tool_connector_definition_digest_input(descriptor, credential_lifecycle_endpoint_profiles))


def tool_connector_definition_canonical_json(descriptor: dict[str, Any]) -> str:
    return canonicalize(tool_connector_definition_digest_input(descriptor))


def tool_connector_definition_canonical_bytes(descriptor: dict[str, Any]) -> bytes:
    return canonical_bytes(tool_connector_definition_digest_input(descriptor))


def validation_only_schema(value: Any, *, _schema_map_entries: bool = False) -> Any:
    if isinstance(value, list):
        return [validation_only_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    return {
        key: validation_only_schema(
            nested,
            _schema_map_entries=not _schema_map_entries and key in _SCHEMA_MAP_KEYWORDS,
        )
        for key, nested in value.items()
        if _schema_map_entries or key not in _SCHEMA_ANNOTATION_KEYWORDS
    }


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be")


def _credential_lifecycle_endpoints(
    descriptor: dict[str, Any],
    credential_lifecycle_endpoint_profiles: dict[str, Any],
) -> dict[str, Any]:
    profile_name = descriptor.get("credentialLifecycleEndpointProfile")
    raw_profile = credential_lifecycle_endpoint_profiles.get(profile_name) if isinstance(profile_name, str) else None
    profile = raw_profile if isinstance(raw_profile, dict) else {}
    return {
        "createCredential": profile.get("createCredential"),
        "rotateCredential": profile.get("rotateCredential"),
        "revokeCredential": profile.get("revokeCredential"),
        "validateCredential": profile.get("validateCredential"),
    }
