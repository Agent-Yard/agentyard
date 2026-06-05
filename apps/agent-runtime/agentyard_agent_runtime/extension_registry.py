from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from copy import deepcopy
import json
import os
from typing import Any, Callable, Protocol

import httpx
from agentyard_extension_sdk.protocol import build_manifest_url, build_service_level_headers
from agentyard_extension_sdk.registration import CORE_AGENT_RUNTIME_REGISTRATION_ID, ExtensionRegistration, ExtensionRegistrationSet
from agentyard_extension_sdk.tool import tool_connector_definition_digest

from .descriptor_provider import (
    BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS,
    DescriptorProvider,
    default_descriptor_provider,
)
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
    credential_lifecycle_endpoint_profiles: dict[str, Any]
    errors: tuple[dict[str, Any], ...] = ()


@dataclass(frozen=True)
class ToolConnectorRegistryEntry:
    connector_type: str
    registration_id: str
    base_url: str
    descriptor: dict[str, Any]
    invoke_path: str
    in_process: bool = False


class ToolConnectorRegistry:
    def __init__(
        self,
        entries: tuple[ToolConnectorRegistryEntry, ...] | list[ToolConnectorRegistryEntry],
        *,
        validation_result: dict[str, Any] | None = None,
    ) -> None:
        entries_by_type: dict[str, ToolConnectorRegistryEntry] = {}
        for entry in entries:
            connector_type = entry.connector_type.strip()
            if not connector_type:
                raise ValueError("tool connector registry entry connector_type must not be blank")
            if connector_type in entries_by_type:
                raise ValueError(f"duplicate tool connector registry entry: {connector_type}")
            entries_by_type[connector_type] = ToolConnectorRegistryEntry(
                connector_type=connector_type,
                registration_id=entry.registration_id,
                base_url=entry.base_url,
                descriptor=deepcopy(entry.descriptor),
                invoke_path=entry.invoke_path,
                in_process=entry.in_process,
            )
        self._entries_by_type = entries_by_type
        self.validation_result = deepcopy(validation_result) if validation_result is not None else None

    def get(self, connector_type: str) -> ToolConnectorRegistryEntry | None:
        return self._entries_by_type.get(connector_type.strip())

    def require(self, connector_type: str) -> ToolConnectorRegistryEntry:
        entry = self.get(connector_type)
        if entry is None:
            raise ValueError(f"unsupported tool connector: {connector_type}")
        return entry

    def descriptor_ids(self) -> list[str]:
        return _sorted_unique(self._entries_by_type)


class ToolConnectorRegistryLoadError(RuntimeError):
    def __init__(self, validation_result: dict[str, Any]) -> None:
        self.validation_result = validation_result
        super().__init__(validation_result.get("summary") or "Tool connector registry is not ready")


@dataclass(frozen=True)
class _LoadedToolConnectorRegistrySnapshot:
    registration_set: ExtensionRegistrationSet
    registrations: tuple[ExtensionRegistration, ...]
    loaded_descriptor_entries: tuple[tuple[ExtensionRegistration, dict[str, Any], dict[str, Any]], ...]
    manifest_errors: tuple[dict[str, Any], ...]


def validate_tool_connector_registry(
    registration_set: ExtensionRegistrationSet | None = None,
    *,
    descriptor_provider: DescriptorProvider | None = None,
    manifest_fetcher: ManifestFetcher | None = None,
) -> dict[str, Any]:
    registration_set = registration_set or load_extension_registration()
    descriptor_provider = descriptor_provider or default_descriptor_provider()
    manifest_fetcher = manifest_fetcher or fetch_remote_manifest
    snapshot = _load_tool_connector_registry_snapshot(registration_set, descriptor_provider, manifest_fetcher)
    return _validation_result_from_snapshot(snapshot)


def load_tool_connector_registry(
    registration_set: ExtensionRegistrationSet | None = None,
    *,
    descriptor_provider: DescriptorProvider | None = None,
    manifest_fetcher: ManifestFetcher | None = None,
) -> ToolConnectorRegistry:
    registration_set = registration_set or load_extension_registration()
    descriptor_provider = descriptor_provider or default_descriptor_provider()
    manifest_fetcher = manifest_fetcher or fetch_remote_manifest
    snapshot = _load_tool_connector_registry_snapshot(registration_set, descriptor_provider, manifest_fetcher)
    validation_result = _validation_result_from_snapshot(snapshot)
    if validation_result.get("status") != "READY":
        raise ToolConnectorRegistryLoadError(validation_result)
    entries = tuple(
        _registry_entry(registration, descriptor)
        for registration, descriptor, _ in snapshot.loaded_descriptor_entries
    )
    return ToolConnectorRegistry(entries, validation_result=validation_result)


def _load_tool_connector_registry_snapshot(
    registration_set: ExtensionRegistrationSet,
    descriptor_provider: DescriptorProvider,
    manifest_fetcher: ManifestFetcher,
) -> _LoadedToolConnectorRegistrySnapshot:
    registrations = _tool_connector_registrations(registration_set)
    loaded_descriptor_entries: list[tuple[ExtensionRegistration, dict[str, Any], dict[str, Any]]] = []
    manifest_errors: list[dict[str, Any]] = []

    for registration in registrations:
        loaded = _load_tool_connector_manifest(registration, descriptor_provider, manifest_fetcher)
        loaded_descriptor_entries.extend(
            (registration, descriptor, loaded.credential_lifecycle_endpoint_profiles)
            for descriptor in loaded.descriptors
        )
        manifest_errors.extend(loaded.errors)

    return _LoadedToolConnectorRegistrySnapshot(
        registration_set=registration_set,
        registrations=registrations,
        loaded_descriptor_entries=tuple(loaded_descriptor_entries),
        manifest_errors=tuple(manifest_errors),
    )


def _validation_result_from_snapshot(snapshot: _LoadedToolConnectorRegistrySnapshot) -> dict[str, Any]:
    registrations = snapshot.registrations
    expected_descriptor_ids = _sorted_unique(
        descriptor_id
        for registration in registrations
        for descriptor_id in registration.exposes.tool_connector_types
    )

    loaded_descriptor_entries = [
        (registration.registration_id, descriptor)
        for registration, descriptor, _ in snapshot.loaded_descriptor_entries
    ]
    loaded_digest_entries = [
        (descriptor, credential_lifecycle_endpoint_profiles)
        for _, descriptor, credential_lifecycle_endpoint_profiles in snapshot.loaded_descriptor_entries
    ]
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
    manifest_errors = list(snapshot.manifest_errors)
    descriptor_definition_digests = _definition_digests(loaded_digest_entries, manifest_errors)

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
        "registrationConfigDigest": snapshot.registration_set.registration_config_digest,
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


def _registry_entry(registration: ExtensionRegistration, descriptor: dict[str, Any]) -> ToolConnectorRegistryEntry:
    connector_type = str(descriptor.get("connectorType") or "").strip()
    endpoints = descriptor.get("endpoints")
    invoke_path = ""
    if isinstance(endpoints, dict):
        invoke_path = str(endpoints.get("invoke") or "").strip()
    if not connector_type or not invoke_path:
        raise ValueError(f"tool connector descriptor {connector_type or '<unknown>'} is missing endpoints.invoke")
    return ToolConnectorRegistryEntry(
        connector_type=connector_type,
        registration_id=registration.registration_id,
        base_url=registration.base_url,
        descriptor=deepcopy(descriptor),
        invoke_path=invoke_path,
        in_process=connector_type in BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS,
    )


def fetch_remote_manifest(registration: ExtensionRegistration) -> bytes:
    token = (os.getenv("AGENTYARD_INTERNAL_AUTH_TOKEN") or "").strip()
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
                credential_lifecycle_endpoint_profiles={},
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
                credential_lifecycle_endpoint_profiles={},
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
            credential_lifecycle_endpoint_profiles={},
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
        return LoadedToolConnectorManifest(registration.registration_id, (), {}, tuple(errors))

    descriptors = tuple(
        descriptor
        for descriptor in manifest["descriptors"]["toolConnectors"]
        if isinstance(descriptor, dict)
    )
    raw_profiles = manifest.get("credentialLifecycleEndpointProfiles")
    profiles = raw_profiles if isinstance(raw_profiles, dict) else {}
    return LoadedToolConnectorManifest(registration.registration_id, descriptors, profiles, ())


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
    descriptor_entries: list[tuple[dict[str, Any], dict[str, Any]]],
    manifest_errors: list[dict[str, Any]],
) -> dict[str, str]:
    digests: dict[str, str] = {}
    for descriptor, credential_lifecycle_endpoint_profiles in descriptor_entries:
        descriptor_id = descriptor.get("connectorType")
        if not isinstance(descriptor_id, str):
            continue
        try:
            digests[descriptor_id] = tool_connector_definition_digest(
                descriptor,
                credential_lifecycle_endpoint_profiles,
            )
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
    "ToolConnectorRegistry",
    "ToolConnectorRegistryEntry",
    "ToolConnectorRegistryLoadError",
    "fetch_remote_manifest",
    "load_tool_connector_registry",
    "validate_tool_connector_registry",
]
