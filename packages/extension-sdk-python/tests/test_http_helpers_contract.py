from __future__ import annotations

import json
from pathlib import Path

import pytest

from lynxus_extension_sdk.protocol import (
    DESCRIPTOR_ID_HEADER,
    DESCRIPTOR_TYPE_HEADER,
    IDEMPOTENCY_KEY_HEADER,
    REGISTRATION_ID_HEADER,
    DescriptorType,
    ExtensionErrorCategory,
    ExtensionErrorParseError,
    build_credential_lifecycle_headers,
    build_descriptor_level_headers,
    build_manifest_url,
    build_service_level_headers,
    parse_non_2xx_extension_error,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
OPENAPI_PATH = REPO_ROOT / "packages/extension-protocol/openapi/extension-boundary.openapi.json"
EXTENSION_ERROR_EXAMPLE_PATH = (
    REPO_ROOT / "packages/extension-protocol/examples/extension-error.remote-auth-failed.json"
)


def test_http_helper_headers_match_openapi_operation_groups() -> None:
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))

    service_headers = build_service_level_headers(
        authorization="Bearer service-token",
        registration_id="registration-1",
    )
    assert service_headers.keys() == _operation_header_names(openapi, "getExtensionManifest")
    assert service_headers["Authorization"] == "Bearer service-token"
    assert service_headers[REGISTRATION_ID_HEADER] == "registration-1"

    service_headers_without_registration = build_service_level_headers(
        authorization="Bearer service-token",
        registration_id=None,
    )
    assert set(service_headers_without_registration) == {"Authorization"}

    descriptor_headers = build_descriptor_level_headers(
        authorization="Bearer descriptor-token",
        registration_id="registration-1",
        descriptor_type=DescriptorType.TOOL_CONNECTOR,
        descriptor_id="enterprise.acme.crm",
        trace_id="trace-1",
        request_id="request-1",
        idempotency_key="idempotency-1",
    )
    assert descriptor_headers.keys() == _operation_header_names(openapi, "invokeToolConnector")
    assert descriptor_headers[DESCRIPTOR_TYPE_HEADER] == "TOOL_CONNECTOR"
    assert descriptor_headers[DESCRIPTOR_ID_HEADER] == "enterprise.acme.crm"

    credential_headers = build_credential_lifecycle_headers(
        authorization="Bearer credential-token",
        trace_id="trace-1",
        request_id="request-1",
    )
    assert credential_headers.keys() == _operation_header_names(openapi, "createCredential")
    assert IDEMPOTENCY_KEY_HEADER not in credential_headers
    assert REGISTRATION_ID_HEADER not in credential_headers
    assert DESCRIPTOR_TYPE_HEADER not in credential_headers
    assert DESCRIPTOR_ID_HEADER not in credential_headers


def test_http_helper_builds_manifest_url_from_protocol_path_constant() -> None:
    assert build_manifest_url("https://extension.example.com") == "https://extension.example.com/extension/manifest"
    assert (
        build_manifest_url("https://extension.example.com/tenant-a/")
        == "https://extension.example.com/tenant-a/extension/manifest"
    )
    assert build_manifest_url("https://extension.example.com").endswith("/extension/manifest")
    with pytest.raises(ValueError):
        build_manifest_url(" ")


def test_http_helper_parses_non_2xx_extension_error_bodies() -> None:
    error = parse_non_2xx_extension_error(
        401,
        EXTENSION_ERROR_EXAMPLE_PATH.read_text(encoding="utf-8"),
    )

    assert error.error_code == "REMOTE_AUTH_FAILED"
    assert error.category is ExtensionErrorCategory.AUTH
    assert error.retryable is False

    with pytest.raises(ExtensionErrorParseError):
        parse_non_2xx_extension_error(200, EXTENSION_ERROR_EXAMPLE_PATH.read_text(encoding="utf-8"))


def test_openapi_default_error_responses_use_extension_error_schema() -> None:
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
    for operation_id in {
        "getExtensionManifest",
        "getExtensionHealth",
        "invokeToolConnector",
        "runChannelProviderJob",
        "createCredential",
        "rotateCredential",
        "revokeCredential",
        "validateCredential",
        "ingestNormalizedChannelEvent",
    }:
        assert _default_response_ref(openapi, operation_id) == "#/components/responses/ExtensionErrorResponse"

    extension_error_response = openapi["components"]["responses"]["ExtensionErrorResponse"]
    application_json = extension_error_response["content"]["application/json"]
    assert application_json["schema"]["$ref"] == "#/components/schemas/ExtensionError"


def _operation_header_names(openapi: dict[str, object], operation_id: str) -> set[str]:
    for path_item in openapi["paths"].values():  # type: ignore[union-attr]
        for operation in path_item.values():
            if operation.get("operationId") != operation_id:
                continue
            names = []
            for parameter in operation.get("parameters", []):
                ref = parameter.get("$ref")
                if ref is None:
                    names.append(parameter["name"])
                else:
                    key = ref.rsplit("/", 1)[-1]
                    names.append(openapi["components"]["parameters"][key]["name"])  # type: ignore[index]
            return set(names)
    raise AssertionError(f"OpenAPI operation not found: {operation_id}")


def _default_response_ref(openapi: dict[str, object], operation_id: str) -> str:
    for path_item in openapi["paths"].values():  # type: ignore[union-attr]
        for operation in path_item.values():
            if operation.get("operationId") == operation_id:
                return operation["responses"]["default"]["$ref"]
    raise AssertionError(f"OpenAPI operation not found: {operation_id}")
