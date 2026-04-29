from __future__ import annotations

import os
from contextlib import contextmanager
from typing import Any
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from lynxus_extension_sdk.common import canonical_bytes
from lynxus_extension_sdk.registration import (
    CORE_AGENT_RUNTIME_REGISTRATION_ID,
    ExtensionRegistration,
    ExtensionRegistrationSet,
    RegistrationAuth,
    RegistrationAuthType,
    RegistrationExposes,
    RegistrationSource,
)
from lynxus_extension_sdk.tool import tool_connector_definition_digest

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.descriptor_provider import (  # noqa: E402
    BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS,
    DescriptorProvider,
)
from lynxus_agent_runtime.extension_protocol import validate_manifest_against_protocol_schema  # noqa: E402
from lynxus_agent_runtime.extension_registry import load_tool_connector_registry, validate_tool_connector_registry  # noqa: E402
from lynxus_agent_runtime.extension_registry import fetch_remote_manifest  # noqa: E402
from lynxus_agent_runtime.main import app  # noqa: E402


class FakeRedisClient:
    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


@contextmanager
def agent_runtime_client():
    with patch("lynxus_agent_runtime.main.create_redis_client", return_value=FakeRedisClient()):
        with TestClient(app) as client:
            yield client


def test_internal_provider_manifest_is_valid_and_canonical() -> None:
    provider = DescriptorProvider()
    manifest = provider.service_manifest()

    assert provider.canonical_manifest_bytes() == canonical_bytes(manifest)
    assert validate_manifest_against_protocol_schema(manifest).valid
    assert manifest["extensionApiVersion"] == 1
    assert manifest["coreMinVersion"] == "0.8.0"
    assert manifest["coreMaxVersion"] == "0.9.x"
    assert manifest["descriptors"]["channelProviders"] == []
    assert [descriptor["connectorType"] for descriptor in manifest["descriptors"]["toolConnectors"]] == list(
        BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS
    )


def test_manifest_endpoint_returns_internal_provider_bytes() -> None:
    provider = DescriptorProvider()

    with agent_runtime_client() as client:
        response = client.get(
            "/extension/manifest",
            headers={"Authorization": "Bearer test-internal-token"},
        )

    assert response.status_code == 200
    assert response.content == provider.canonical_manifest_bytes()
    assert validate_manifest_against_protocol_schema(response.json()).valid


def test_manifest_endpoint_requires_internal_auth() -> None:
    with agent_runtime_client() as client:
        missing = client.get("/extension/manifest")
        invalid = client.get("/extension/manifest", headers={"Authorization": "Bearer wrong-token"})

    assert missing.status_code == 401
    assert invalid.status_code == 401


def test_core_preset_validation_uses_provider_without_self_http() -> None:
    provider = DescriptorProvider()
    fetcher = Mock(side_effect=AssertionError("core preset must not fetch its own HTTP manifest"))

    result = validate_tool_connector_registry(
        _registration_set(
            [
                _registration(
                    CORE_AGENT_RUNTIME_REGISTRATION_ID,
                    tool_connector_types=BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS,
                    source=RegistrationSource.CORE_PRESET,
                ),
                _registration("channel-only", channel_provider_types=("enterprise.channel",)),
            ]
        ),
        descriptor_provider=provider,
        manifest_fetcher=fetcher,
    )

    assert result["status"] == "READY"
    assert result["service"] == "agent-runtime"
    assert result["registryType"] == "TOOL_CONNECTOR"
    assert result["expectedDescriptorIds"] == list(BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS)
    assert result["loadedDescriptorIds"] == list(BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS)
    assert result["missingDescriptorIds"] == []
    assert result["unexpectedDescriptorIds"] == []
    assert result["duplicateDescriptorIds"] == []
    assert result["manifestErrors"] == []
    assert result["descriptorDefinitionDigests"] == provider.tool_connector_definition_digests()
    fetcher.assert_not_called()


def test_core_preset_registry_load_uses_provider_without_self_http() -> None:
    provider = DescriptorProvider()
    fetcher = Mock(side_effect=AssertionError("core preset must not fetch its own HTTP manifest"))

    registry = load_tool_connector_registry(
        _registration_set(
            [
                _registration(
                    CORE_AGENT_RUNTIME_REGISTRATION_ID,
                    tool_connector_types=BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS,
                    source=RegistrationSource.CORE_PRESET,
                ),
                _registration("channel-only", channel_provider_types=("enterprise.channel",)),
            ]
        ),
        descriptor_provider=provider,
        manifest_fetcher=fetcher,
    )

    assert registry.descriptor_ids() == list(BUILT_IN_TOOL_CONNECTOR_DESCRIPTOR_IDS)
    assert registry.require("simple-http").in_process is True
    assert registry.require("mcp").registration_id == CORE_AGENT_RUNTIME_REGISTRATION_ID
    fetcher.assert_not_called()


def test_non_core_validation_filters_to_tool_descriptors_and_calculates_digests() -> None:
    remote_tool = _remote_tool_descriptor("enterprise.acme.crm")
    channel_descriptor = {
        "providerType": "enterprise.acme.im",
        "title": "Acme IM",
        "accountConfigSchema": _empty_object_schema(),
        "accountConfigUiSchema": [],
        "configSchema": _empty_object_schema(),
        "configUiSchema": [],
        "endpoints": {"sendOutbound": "/channel/send-outbound"},
    }
    remote_manifest = {
        "extensionApiVersion": 1,
        "coreMinVersion": "0.8.0",
        "coreMaxVersion": "0.9.x",
        "descriptors": {
            "channelProviders": [channel_descriptor],
            "toolConnectors": [remote_tool],
        },
    }
    calls: list[str] = []

    def fetcher(registration: ExtensionRegistration) -> bytes:
        calls.append(registration.registration_id)
        return canonical_bytes(remote_manifest)

    result = validate_tool_connector_registry(
        _registration_set(
            [
                _registration(
                    "enterprise-combo",
                    channel_provider_types=("enterprise.acme.im",),
                    tool_connector_types=("enterprise.acme.crm",),
                ),
                _registration("channel-only", channel_provider_types=("ignored.channel",)),
            ]
        ),
        manifest_fetcher=fetcher,
    )

    assert calls == ["enterprise-combo"]
    assert result["status"] == "READY"
    assert result["expectedDescriptorIds"] == ["enterprise.acme.crm"]
    assert result["loadedDescriptorIds"] == ["enterprise.acme.crm"]
    assert result["descriptorDefinitionDigests"] == {
        "enterprise.acme.crm": tool_connector_definition_digest(remote_tool)
    }
    assert result["manifestErrors"] == []


def test_non_core_registry_records_remote_invoke_metadata_and_ignores_channel_only_registration() -> None:
    remote_tool = _remote_tool_descriptor("enterprise.acme.crm")
    remote_manifest = {
        "extensionApiVersion": 1,
        "coreMinVersion": "0.8.0",
        "coreMaxVersion": "0.9.x",
        "descriptors": {
            "channelProviders": [],
            "toolConnectors": [remote_tool],
        },
    }
    calls: list[str] = []

    def fetcher(registration: ExtensionRegistration) -> bytes:
        calls.append(registration.registration_id)
        return canonical_bytes(remote_manifest)

    registry = load_tool_connector_registry(
        _registration_set(
            [
                _registration("enterprise-tools", tool_connector_types=("enterprise.acme.crm",)),
                _registration("channel-only", channel_provider_types=("ignored.channel",)),
            ]
        ),
        manifest_fetcher=fetcher,
    )

    entry = registry.require("enterprise.acme.crm")
    assert calls == ["enterprise-tools"]
    assert entry.in_process is False
    assert entry.registration_id == "enterprise-tools"
    assert entry.base_url == "https://extensions.example.test/enterprise-tools"
    assert entry.invoke_path == "/tools/invoke"
    assert entry.descriptor["connectorType"] == "enterprise.acme.crm"


def test_validation_reports_structured_descriptor_registry_errors() -> None:
    loaded_descriptor = _remote_tool_descriptor("enterprise.acme.loaded")
    unexpected_descriptor = _remote_tool_descriptor("enterprise.acme.unexpected")
    remote_manifest = {
        "extensionApiVersion": 1,
        "coreMinVersion": "0.8.0",
        "coreMaxVersion": "0.9.x",
        "descriptors": {
            "channelProviders": [],
            "toolConnectors": [loaded_descriptor, unexpected_descriptor, unexpected_descriptor],
        },
    }

    def fetcher(_: ExtensionRegistration) -> bytes:
        return canonical_bytes(remote_manifest)

    result = validate_tool_connector_registry(
        _registration_set(
            [
                _registration(
                    "enterprise-tools",
                    tool_connector_types=("enterprise.acme.loaded", "enterprise.acme.missing"),
                )
            ]
        ),
        manifest_fetcher=fetcher,
    )

    assert result["status"] == "NOT_READY"
    assert result["summary"] == "Tool connector registry is not ready"
    assert result["missingDescriptorIds"] == ["enterprise.acme.missing"]
    assert result["unexpectedDescriptorIds"] == ["enterprise.acme.unexpected"]
    assert result["duplicateDescriptorIds"] == ["enterprise.acme.unexpected"]
    assert result["errors"] == [
        {
            "code": "MISSING_DESCRIPTOR",
            "severity": "ERROR",
            "registrationId": "enterprise-tools",
            "descriptorType": "TOOL_CONNECTOR",
            "descriptorId": "enterprise.acme.missing",
            "message": "Descriptor declared in registration config was not loaded from manifest",
            "retryable": False,
            "details": {"phase": "REGISTRY_LOAD"},
        },
        {
            "code": "UNEXPECTED_DESCRIPTOR",
            "severity": "ERROR",
            "registrationId": "enterprise-tools",
            "descriptorType": "TOOL_CONNECTOR",
            "descriptorId": "enterprise.acme.unexpected",
            "message": "Descriptor loaded from manifest was not declared in registration config",
            "retryable": False,
            "details": {"phase": "REGISTRY_LOAD"},
        },
        {
            "code": "DUPLICATE_DESCRIPTOR",
            "severity": "ERROR",
            "registrationId": "enterprise-tools",
            "descriptorType": "TOOL_CONNECTOR",
            "descriptorId": "enterprise.acme.unexpected",
            "message": "Descriptor was loaded more than once from tool connector manifests",
            "retryable": False,
            "details": {"phase": "REGISTRY_LOAD"},
        },
    ]


def test_manifest_validation_errors_use_registry_schema_code_and_preserve_sdk_violation() -> None:
    remote_manifest = {
        "extensionApiVersion": 1,
        "coreMinVersion": "0.8.0",
        "coreMaxVersion": "0.9.x",
        "descriptors": {
            "channelProviders": [],
            "toolConnectors": [],
        },
    }

    def fetcher(_: ExtensionRegistration) -> bytes:
        return canonical_bytes(remote_manifest)

    result = validate_tool_connector_registry(
        _registration_set([_registration("enterprise-tools", tool_connector_types=("enterprise.acme.crm",))]),
        manifest_fetcher=fetcher,
    )

    assert result["status"] == "NOT_READY"
    assert result["manifestErrors"]
    assert {error["code"] for error in result["manifestErrors"]} == {"MANIFEST_SCHEMA_INVALID"}
    assert {error["details"]["phase"] for error in result["manifestErrors"]} == {"MANIFEST_FETCH"}
    assert {error["details"]["violation"] for error in result["manifestErrors"]} >= {"MANIFEST_EMPTY"}
    assert any(error["details"]["schemaPath"] == "/descriptors" for error in result["manifestErrors"])
    assert {error["code"] for error in result["errors"]} == {"MISSING_DESCRIPTOR", "MANIFEST_SCHEMA_INVALID"}


def test_validation_endpoint_returns_503_when_registry_is_not_ready() -> None:
    validation_result = {
        "status": "NOT_READY",
        "service": "agent-runtime",
        "component": "EXTENSION_REGISTRY",
        "registryType": "TOOL_CONNECTOR",
        "registrationConfigDigest": "sha256:test",
        "summary": "Tool connector registry is not ready",
        "errors": [
            {
                "code": "MISSING_DESCRIPTOR",
                "severity": "ERROR",
                "registrationId": None,
                "descriptorType": "TOOL_CONNECTOR",
                "descriptorId": "enterprise.acme.missing",
                "message": "Descriptor declared in registration config was not loaded from manifest",
                "retryable": False,
                "details": {"phase": "REGISTRY_LOAD"},
            }
        ],
        "expectedDescriptorIds": ["enterprise.acme.missing"],
        "loadedDescriptorIds": [],
        "descriptorDefinitionDigests": {},
        "missingDescriptorIds": ["enterprise.acme.missing"],
        "unexpectedDescriptorIds": [],
        "duplicateDescriptorIds": [],
        "manifestErrors": [],
    }

    with patch("lynxus_agent_runtime.main.validate_tool_connector_registry", return_value=validation_result):
        with agent_runtime_client() as client:
            client.app.state.tool_connector_registry = None
            response = client.get(
                "/internal/extension-registry/tool-connectors/validation",
                headers={"Authorization": "Bearer test-internal-token"},
            )

    assert response.status_code == 503
    assert response.json() == validation_result


def test_remote_manifest_fetch_uses_sdk_manifest_url_and_service_headers() -> None:
    registration = _registration("enterprise-tools")
    observed: dict[str, Any] = {}

    class FakeResponse:
        content = b'{"ok":true}'

        def raise_for_status(self) -> None:
            return None

    class FakeClient:
        def get(self, url: str, *, headers: dict[str, str], timeout: int) -> FakeResponse:
            observed["url"] = url
            observed["headers"] = headers
            observed["timeout"] = timeout
            return FakeResponse()

    with patch("lynxus_agent_runtime.extension_registry.shared_http_client_for_url", return_value=FakeClient()):
        content = fetch_remote_manifest(registration)

    assert content == b'{"ok":true}'
    assert observed == {
        "url": "https://extensions.example.test/enterprise-tools/extension/manifest",
        "headers": {
            "Authorization": "Bearer test-internal-token",
            "X-Lynxus-Extension-Registration-Id": "enterprise-tools",
        },
        "timeout": 5,
    }


def _registration_set(registrations: list[ExtensionRegistration]) -> ExtensionRegistrationSet:
    return ExtensionRegistrationSet(
        services=tuple(registrations),
        registration_config_digest="sha256:test",
        canonical_input={"services": []},
    )


def _registration(
    registration_id: str,
    *,
    source: RegistrationSource = RegistrationSource.OPERATOR_YAML,
    channel_provider_types: tuple[str, ...] = (),
    tool_connector_types: tuple[str, ...] = (),
) -> ExtensionRegistration:
    return ExtensionRegistration(
        registration_id=registration_id,
        source=source,
        base_url=f"https://extensions.example.test/{registration_id}",
        exposes=RegistrationExposes(
            channel_provider_types=channel_provider_types,
            tool_connector_types=tool_connector_types,
        ),
        auth=RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN),
    )


def _remote_tool_descriptor(connector_type: str) -> dict[str, Any]:
    return {
        "connectorType": connector_type,
        "title": "Acme CRM",
        "accountConfigSchema": _empty_object_schema(),
        "accountConfigUiSchema": [],
        "configSchema": _empty_object_schema(),
        "configUiSchema": [],
        "operationMappingSchema": _empty_object_schema(),
        "operationMappingUiSchema": [],
        "endpoints": {"invoke": "/tools/invoke"},
    }


def _empty_object_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {},
        "additionalProperties": False,
    }
