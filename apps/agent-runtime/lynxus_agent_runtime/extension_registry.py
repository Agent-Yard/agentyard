from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import json
import os
from typing import Any, Callable, Protocol

import httpx
from lynxus_extension_sdk.protocol import build_manifest_url, build_service_level_headers
from lynxus_extension_sdk.registration import CORE_AGENT_RUNTIME_REGISTRATION_ID, ExtensionRegistration, ExtensionRegistrationSet
from lynxus_extension_sdk.tool import tool_connector_definition_digest

from .descriptor_provider import DescriptorProvider, default_descriptor_provider
from .extension_protocol import validate_manifest_against_protocol_schema
from .extension_registration import load_extension_registration
from .http_clients import shared_http_client_for_url

TOOL_CONNECTOR_REGISTRY_TYPE = "TOOL_CONNECTOR"
_SERVICE = "agent-runtime"
_COMPONENT = "EXTENSION_REGISTRY"


class ManifestFetcher(Protocol):
    def __call__(self, registration: ExtensionRegistration) -> bytes:
        ...


@dataclass(frozen=True)
class LoadedToolConnectorManifest:
    registration_id: str
    descriptors: tuple[dict[str, Any], ...]
    errors: tuple[dict[str, Any], ...] = ()


def validate_tool_connector_registry(
    registration_set: ExtensionRegistrationSet | None = None,
    *,
    descriptor_provider: DescriptorProvider | None = None,
    manifest_fetcher: ManifestFetcher | None = None,
) -> dict[str, Any]:
    registration_set = registration_set or load_extension_registration()
    descriptor_provider = descriptor_provider or default_descriptor_provider()
    manifest_fetcher = manifest_fetcher or fetch_remote_manifest

    registrations = _tool_connector_registrations(registration_set)
    expected_descriptor_ids = _sorted_unique(
        descriptor_id
        for registration in registrations
        for descriptor_id in registration.exposes.tool_connector_types
    )

    loaded_descriptor_entries: list[tuple[str, dict[str, Any]]] = []
    manifest_errors: list[dict[str, Any]] = []

    for registration in registrations:
        loaded = _load_tool_connector_manifest(registration, descriptor_provider, manifest_fetcher)
        loaded_descriptor_entries.extend((loaded.registration_id, descriptor) for descriptor in loaded.descriptors)
        manifest_errors.extend(loaded.errors)

    loaded_descriptors = [descriptor for _, descriptor in loaded_descriptor_entries]
    loaded_descriptor_ids_in_order = [
        descriptor["connectorType"]
        for descriptor in loaded_descriptors
        if isinstance(descriptor.get("connectorType"), str)
    ]
    loaded_descriptor_ids = _sorted_unique(loaded_descriptor_ids_in_order)
    duplicate_descriptor_ids = _duplicates(loaded_descriptor_ids_in_order)
    expected_set = set(expected_descriptor_ids)
    loaded_set = set(loaded_descriptor_ids)
    missing_descriptor_ids = _sorted_unique(expected_set - loaded_set)
    unexpected_descriptor_ids = _sorted_unique(loaded_set - expected_set)
    descriptor_definition_digests = _definition_digests(loaded_descriptors, manifest_errors)

    registry_errors = _registry_descriptor_errors(
        registrations=registrations,
        loaded_descriptor_entries=loaded_descriptor_entries,
        missing_descriptor_ids=missing_descriptor_ids,
        unexpected_descriptor_ids=unexpected_descriptor_ids,
        duplicate_descriptor_ids=duplicate_descriptor_ids,
    )
    errors = registry_errors + manifest_errors
    ready = not (
        missing_descriptor_ids
        or unexpected_descriptor_ids
        or duplicate_descriptor_ids
        or manifest_errors
    )
    return {
        "status": "READY" if ready else "NOT_READY",
        "service": _SERVICE,
        "component": _COMPONENT,
        "registryType": TOOL_CONNECTOR_REGISTRY_TYPE,
        "registrationConfigDigest": registration_set.registration_config_digest,
        "summary": "Tool connector registry is ready" if ready else "Tool connector registry is not ready",
        "errors": errors,
        "expectedDescriptorIds": expected_descriptor_ids,
        "loadedDescriptorIds": loaded_descriptor_ids,
        "descriptorDefinitionDigests": descriptor_definition_digests,
        "missingDescriptorIds": missing_descriptor_ids,
        "unexpectedDescriptorIds": unexpected_descriptor_ids,
        "duplicateDescriptorIds": duplicate_descriptor_ids,
        "manifestErrors": manifest_errors,
    }


def fetch_remote_manifest(registration: ExtensionRegistration) -> bytes:
    token = (os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN") or "").strip()
    headers = build_service_level_headers(
        authorization=f"Bearer {token}",
        registration_id=registration.registration_id,
    )
    url = build_manifest_url(registration.base_url)
    client = shared_http_client_for_url(url)
    response = client.get(url, headers=headers, timeout=5)
    response.raise_for_status()
    return response.content


def _load_tool_connector_manifest(
    registration: ExtensionRegistration,
    descriptor_provider: DescriptorProvider,
    manifest_fetcher: ManifestFetcher,
) -> LoadedToolConnectorManifest:
    if registration.registration_id == CORE_AGENT_RUNTIME_REGISTRATION_ID:
        manifest_bytes = descriptor_provider.canonical_manifest_bytes()
    else:
        try:
            manifest_bytes = manifest_fetcher(registration)
        except httpx.HTTPStatusError as error:
            return LoadedToolConnectorManifest(
                registration_id=registration.registration_id,
                descriptors=(),
                errors=(
                    _manifest_error(
                        code="MANIFEST_FETCH_FAILED",
                        registration_id=registration.registration_id,
                        message="Failed to fetch extension manifest",
                        details={
                            "phase": "MANIFEST_FETCH",
                            "httpStatus": error.response.status_code,
                            "failureReason": "non-2xx response",
                        },
                    ),
                ),
            )
        except Exception as error:
            return LoadedToolConnectorManifest(
                registration_id=registration.registration_id,
                descriptors=(),
                errors=(
                    _manifest_error(
                        code="MANIFEST_FETCH_FAILED",
                        registration_id=registration.registration_id,
                        message="Failed to fetch extension manifest",
                        details={
                            "phase": "MANIFEST_FETCH",
                            "httpStatus": None,
                            "failureReason": error.__class__.__name__,
                        },
                    ),
                ),
            )

    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return LoadedToolConnectorManifest(
            registration_id=registration.registration_id,
            descriptors=(),
            errors=(
                _manifest_error(
                    code="MANIFEST_SCHEMA_INVALID",
                    registration_id=registration.registration_id,
                    message="Extension manifest is not valid JSON",
                    details={"phase": "MANIFEST_FETCH", "schemaPath": "", "violation": "invalid JSON"},
                ),
            ),
        )

    validation = validate_manifest_against_protocol_schema(manifest)
    errors = [
        _manifest_error(
            code="MANIFEST_SCHEMA_INVALID",
            registration_id=registration.registration_id,
            message="Extension manifest failed protocol validation",
            details={
                "phase": "MANIFEST_FETCH",
                "schemaPath": error.path,
                "violation": error.code,
            },
        )
        for error in validation.errors
    ]
    if errors:
        return LoadedToolConnectorManifest(registration.registration_id, (), tuple(errors))

    descriptors = tuple(
        descriptor
        for descriptor in manifest["descriptors"]["toolConnectors"]
        if isinstance(descriptor, dict)
    )
    return LoadedToolConnectorManifest(registration.registration_id, descriptors, ())


def _registry_descriptor_errors(
    *,
    registrations: tuple[ExtensionRegistration, ...],
    loaded_descriptor_entries: list[tuple[str, dict[str, Any]]],
    missing_descriptor_ids: list[str],
    unexpected_descriptor_ids: list[str],
    duplicate_descriptor_ids: list[str],
) -> list[dict[str, Any]]:
    expected_registration_ids = _expected_descriptor_registration_ids(registrations)
    loaded_registration_ids = _loaded_descriptor_registration_ids(loaded_descriptor_entries)
    errors: list[dict[str, Any]] = []
    errors.extend(
        _registry_descriptor_error(
            code="MISSING_DESCRIPTOR",
            registration_id=_first_or_none(expected_registration_ids.get(descriptor_id)),
            descriptor_id=descriptor_id,
            message="Descriptor declared in registration config was not loaded from manifest",
        )
        for descriptor_id in missing_descriptor_ids
    )
    errors.extend(
        _registry_descriptor_error(
            code="UNEXPECTED_DESCRIPTOR",
            registration_id=_first_or_none(loaded_registration_ids.get(descriptor_id)),
            descriptor_id=descriptor_id,
            message="Descriptor loaded from manifest was not declared in registration config",
        )
        for descriptor_id in unexpected_descriptor_ids
    )
    errors.extend(
        _registry_descriptor_error(
            code="DUPLICATE_DESCRIPTOR",
            registration_id=_first_or_none(loaded_registration_ids.get(descriptor_id)),
            descriptor_id=descriptor_id,
            message="Descriptor was loaded more than once from tool connector manifests",
        )
        for descriptor_id in duplicate_descriptor_ids
    )
    return errors


def _expected_descriptor_registration_ids(
    registrations: tuple[ExtensionRegistration, ...],
) -> dict[str, list[str]]:
    registration_ids_by_descriptor: dict[str, list[str]] = {}
    for registration in registrations:
        for descriptor_id in registration.exposes.tool_connector_types:
            registration_ids_by_descriptor.setdefault(descriptor_id, []).append(registration.registration_id)
    return registration_ids_by_descriptor


def _loaded_descriptor_registration_ids(
    loaded_descriptor_entries: list[tuple[str, dict[str, Any]]],
) -> dict[str, list[str]]:
    registration_ids_by_descriptor: dict[str, list[str]] = {}
    for registration_id, descriptor in loaded_descriptor_entries:
        descriptor_id = descriptor.get("connectorType")
        if isinstance(descriptor_id, str):
            registration_ids_by_descriptor.setdefault(descriptor_id, []).append(registration_id)
    return registration_ids_by_descriptor


def _registry_descriptor_error(
    *,
    code: str,
    registration_id: str | None,
    descriptor_id: str,
    message: str,
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": "ERROR",
        "registrationId": registration_id,
        "descriptorType": TOOL_CONNECTOR_REGISTRY_TYPE,
        "descriptorId": descriptor_id,
        "message": message,
        "retryable": False,
        "details": {"phase": "REGISTRY_LOAD"},
    }


def _tool_connector_registrations(registration_set: ExtensionRegistrationSet) -> tuple[ExtensionRegistration, ...]:
    return tuple(
        registration
        for registration in registration_set.services
        if registration.exposes.tool_connector_types
    )


def _definition_digests(
    descriptors: list[dict[str, Any]],
    manifest_errors: list[dict[str, Any]],
) -> dict[str, str]:
    digests: dict[str, str] = {}
    for descriptor in descriptors:
        descriptor_id = descriptor.get("connectorType")
        if not isinstance(descriptor_id, str):
            continue
        try:
            digests[descriptor_id] = tool_connector_definition_digest(descriptor)
        except Exception as error:
            manifest_errors.append(
                _manifest_error(
                    code="MANIFEST_SCHEMA_INVALID",
                    registration_id=None,
                    descriptor_id=descriptor_id,
                    message="Tool connector definition digest could not be calculated",
                    details={
                        "phase": "MANIFEST_FETCH",
                        "schemaPath": f"/descriptors/toolConnectors/{descriptor_id}",
                        "violation": error.__class__.__name__,
                    },
                )
            )
    return {key: digests[key] for key in _sorted_unique(digests)}


def _manifest_error(
    *,
    code: str,
    registration_id: str | None,
    message: str,
    details: dict[str, Any],
    descriptor_id: str | None = None,
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": "ERROR",
        "registrationId": registration_id,
        "descriptorType": TOOL_CONNECTOR_REGISTRY_TYPE,
        "descriptorId": descriptor_id,
        "message": message,
        "retryable": code == "MANIFEST_FETCH_FAILED",
        "details": details,
    }


def _duplicates(values: list[str]) -> list[str]:
    counts = Counter(values)
    return _sorted_unique(value for value, count in counts.items() if count > 1)


def _sorted_unique(values: Any) -> list[str]:
    return sorted(set(values), key=lambda value: value.encode("utf-16-be"))


def _first_or_none(values: list[str] | None) -> str | None:
    if not values:
        return None
    return values[0]


__all__ = [
    "ManifestFetcher",
    "TOOL_CONNECTOR_REGISTRY_TYPE",
    "fetch_remote_manifest",
    "validate_tool_connector_registry",
]
