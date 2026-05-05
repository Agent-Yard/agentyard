from __future__ import annotations

import json
from pathlib import Path

import pytest

from lynxus_extension_sdk.protocol import (
    CHANNEL_OUTBOUND_FRAMES_ACK_PATH,
    CHANNEL_OUTBOUND_FRAMES_STREAM_PATH,
    CHANNEL_OUTBOUND_FRAME_SUBSCRIPTIONS_PATH,
    CHANNEL_PROVIDER_RUN_JOB_ENDPOINT,
    CREATE_CREDENTIAL_ENDPOINT,
    CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS,
    DESCRIPTOR_LEVEL_REQUIRED_HEADERS,
    EXTENSION_HEALTH_PATH,
    EXTENSION_MANIFEST_PATH,
    HEALTH_LIVE_PATH,
    HEALTH_READY_PATH,
    REVOKE_CREDENTIAL_ENDPOINT,
    ROTATE_CREDENTIAL_ENDPOINT,
    SERVICE_LEVEL_HEADERS,
    TOOL_CONNECTOR_INVOKE_ENDPOINT,
    VALIDATE_CREDENTIAL_ENDPOINT,
    DescriptorType,
    ExtensionErrorCategory,
    ExtensionErrorParseError,
    parse_extension_error_json,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
OPENAPI_PATH = REPO_ROOT / "packages/extension-protocol/openapi/extension-boundary.openapi.json"
EXTENSION_ERROR_SCHEMA_PATH = REPO_ROOT / "packages/extension-protocol/json-schema/extension-error.schema.json"
EXTENSION_ERROR_EXAMPLE_PATH = (
    REPO_ROOT / "packages/extension-protocol/examples/extension-error.remote-auth-failed.json"
)
TOOL_CONNECTOR_SCHEMA_PATH = REPO_ROOT / "packages/extension-protocol/json-schema/tool-connector-descriptor.schema.json"
CHANNEL_PROVIDER_SCHEMA_PATH = REPO_ROOT / "packages/extension-protocol/json-schema/channel-provider-descriptor.schema.json"
CHANNEL_OUTBOUND_FRAME_SCHEMA_PATH = REPO_ROOT / "packages/extension-protocol/json-schema/channel-outbound-frame.schema.json"
CHANNEL_OUTBOUND_FRAME_ACK_SCHEMA_PATH = (
    REPO_ROOT / "packages/extension-protocol/json-schema/channel-outbound-frame-ack.schema.json"
)


def test_path_constants_match_openapi_path_keys() -> None:
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
    openapi_paths = set(openapi["paths"])

    fixed_path_constants = {
        EXTENSION_MANIFEST_PATH,
        EXTENSION_HEALTH_PATH,
        HEALTH_LIVE_PATH,
        HEALTH_READY_PATH,
        CHANNEL_OUTBOUND_FRAME_SUBSCRIPTIONS_PATH,
        CHANNEL_OUTBOUND_FRAMES_STREAM_PATH,
        CHANNEL_OUTBOUND_FRAMES_ACK_PATH,
    }

    assert fixed_path_constants == {
        "/extension/manifest",
        "/extension/health",
        "/health/live",
        "/health/ready",
        "/extension/channel/outbound-frame-subscriptions",
        "/extension/channel/outbound-frames/stream",
        "/extension/channel/outbound-frames/ack",
    }
    assert fixed_path_constants <= openapi_paths


def test_endpoint_key_constants_match_json_schema_endpoint_properties() -> None:
    tool_descriptor_schema = json.loads(TOOL_CONNECTOR_SCHEMA_PATH.read_text(encoding="utf-8"))
    channel_descriptor_schema = json.loads(CHANNEL_PROVIDER_SCHEMA_PATH.read_text(encoding="utf-8"))

    tool_endpoint_constants = {
        TOOL_CONNECTOR_INVOKE_ENDPOINT,
        CREATE_CREDENTIAL_ENDPOINT,
        ROTATE_CREDENTIAL_ENDPOINT,
        REVOKE_CREDENTIAL_ENDPOINT,
        VALIDATE_CREDENTIAL_ENDPOINT,
    }
    channel_endpoint_constants = {
        CHANNEL_PROVIDER_RUN_JOB_ENDPOINT,
        CREATE_CREDENTIAL_ENDPOINT,
        ROTATE_CREDENTIAL_ENDPOINT,
        REVOKE_CREDENTIAL_ENDPOINT,
        VALIDATE_CREDENTIAL_ENDPOINT,
    }

    assert tool_endpoint_constants == {"invoke", "createCredential", "rotateCredential", "revokeCredential", "validateCredential"}
    assert channel_endpoint_constants == {"runJob", "createCredential", "rotateCredential", "revokeCredential", "validateCredential"}
    assert tool_endpoint_constants == _endpoint_property_keys(tool_descriptor_schema)
    assert channel_endpoint_constants == _endpoint_property_keys(channel_descriptor_schema)


def test_header_constants_match_openapi_operation_groups() -> None:
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))

    assert _operation_header_names(openapi, "getExtensionManifest") == SERVICE_LEVEL_HEADERS
    assert _operation_header_names(openapi, "getExtensionHealth") == SERVICE_LEVEL_HEADERS
    assert _operation_header_names(openapi, "getHealthLive") == frozenset()
    assert _operation_header_names(openapi, "getHealthReady") == frozenset()

    for operation_id in {
        "invokeToolConnector",
        "runChannelProviderJob",
        "ingestNormalizedChannelEvent",
    }:
        assert _operation_header_names(openapi, operation_id) == DESCRIPTOR_LEVEL_REQUIRED_HEADERS

    for operation_id in {
        "createCredential",
        "rotateCredential",
        "revokeCredential",
        "validateCredential",
    }:
        assert _operation_header_names(openapi, operation_id) == CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS


def test_facade_enums_match_openapi_and_json_schema_enums() -> None:
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
    extension_error_schema = json.loads(EXTENSION_ERROR_SCHEMA_PATH.read_text(encoding="utf-8"))

    descriptor_type_enum = openapi["components"]["parameters"]["DescriptorTypeHeader"]["schema"]["enum"]
    assert [item.value for item in DescriptorType] == descriptor_type_enum

    openapi_categories = openapi["components"]["schemas"]["ExtensionError"]["properties"]["category"]["enum"]
    json_schema_categories = extension_error_schema["properties"]["category"]["enum"]
    facade_categories = [item.value for item in ExtensionErrorCategory]
    assert facade_categories == openapi_categories
    assert facade_categories == json_schema_categories


def test_channel_outbound_frame_schema_rejects_illegal_kind_payload_combinations() -> None:
    from jsonschema import Draft202012Validator

    schema = json.loads(CHANNEL_OUTBOUND_FRAME_SCHEMA_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)

    draft_complete = _base_frame(
        kind="DRAFT_COMPLETE",
        turnId="turn-1",
        turnExecutionId="exec-1",
        sourceSeq=7,
        payload={
            "messageId": "draft-message-1",
            "blockId": "block-1",
            "blockType": "TEXT",
            "block": {"type": "TEXT", "text": "hello"},
        },
    )
    assert not list(validator.iter_errors(draft_complete))

    final_delivery = _base_frame(
        kind="FINAL_DELIVERY",
        finalSequence=100000042,
        payload={
            "sessionMessageId": "message-1",
            "messageSequence": 3,
            "messageBlocks": [{"type": "TEXT", "text": "hello"}],
        },
    )
    assert not list(validator.iter_errors(final_delivery))

    draft_with_final_sequence = dict(draft_complete, finalSequence=100000042)
    assert list(validator.iter_errors(draft_with_final_sequence))

    final_with_draft_payload = _base_frame(
        kind="FINAL_DELIVERY",
        finalSequence=100000043,
        payload={
            "messageId": "draft-message-1",
            "blockId": "block-1",
            "blockType": "TEXT",
            "block": {"type": "TEXT", "text": "hello"},
        },
    )
    assert list(validator.iter_errors(final_with_draft_payload))


def test_channel_outbound_ack_schema_forbids_provider_response_and_credential_fields() -> None:
    from jsonschema import Draft202012Validator

    schema = json.loads(CHANNEL_OUTBOUND_FRAME_ACK_SCHEMA_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)

    ack = {
        "protocol": "lynxus.channel-outbound-frame-ack.v1",
        "channelProfileId": "channel-profile-1",
        "providerType": "enterprise.acme.im",
        "frameId": "channel-profile-1:session-1:message-1:FINAL_DELIVERY",
        "finalSequence": 100000042,
        "sessionId": "session-1",
        "sessionMessageId": "message-1",
        "metadata": {"externalMessageIdHash": "hash-1"},
    }
    assert not list(validator.iter_errors(ack))

    for forbidden_field in {"providerResponse", "rawProviderResponse", "credential", "credentialRef", "externalSecretRef"}:
        invalid_ack = dict(ack, metadata={forbidden_field: "secret-or-raw-response"})
        assert list(validator.iter_errors(invalid_ack)), forbidden_field


def test_parse_extension_error_example_and_validate_required_shape() -> None:
    error = parse_extension_error_json(EXTENSION_ERROR_EXAMPLE_PATH.read_text(encoding="utf-8"))

    assert error.error_code == "REMOTE_AUTH_FAILED"
    assert error.message == "credential expired"
    assert error.category is ExtensionErrorCategory.AUTH
    assert error.retryable is False
    assert error.details == {}

    circuit_open = parse_extension_error_json(
        """
        {
          "errorCode": "CIRCUIT_OPEN",
          "message": "circuit breaker is open",
          "category": "CIRCUIT_OPEN",
          "retryable": true,
          "details": {"breaker": "remote-tool"}
        }
        """
    )
    assert circuit_open.category is ExtensionErrorCategory.CIRCUIT_OPEN
    assert circuit_open.retryable is True
    assert circuit_open.details == {"breaker": "remote-tool"}

    invalid_payloads = [
        '{"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":false}',
        '{"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"NOT_A_CATEGORY","retryable":false,"details":{}}',
        '{"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":"false","details":{}}',
        '{"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":false,"details":[]}',
    ]
    for payload in invalid_payloads:
        with pytest.raises(ExtensionErrorParseError):
            parse_extension_error_json(payload)


def _operation_header_names(openapi: dict[str, object], operation_id: str) -> frozenset[str]:
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
            return frozenset(names)
    raise AssertionError(f"OpenAPI operation not found: {operation_id}")


def _base_frame(**overrides: object) -> dict[str, object]:
    frame: dict[str, object] = {
        "protocol": "lynxus.channel-outbound-frame.v1",
        "frameId": "channel-profile-1:exec-1:7:DRAFT_COMPLETE",
        "channelProfileId": "channel-profile-1",
        "providerType": "enterprise.acme.im",
        "assistantId": "assistant-1",
        "externalConversationId": "chat-1",
        "sessionId": "session-1",
        "kind": "DRAFT_COMPLETE",
        "occurredAt": "2026-05-04T10:00:00Z",
        "idempotencyKey": "channel-profile-1:exec-1:7:DRAFT_COMPLETE",
        "payload": {},
    }
    frame.update(overrides)
    return frame


def _endpoint_property_keys(descriptor_schema: dict[str, object]) -> set[str]:
    return set(descriptor_schema["properties"]["endpoints"]["properties"])  # type: ignore[index]
