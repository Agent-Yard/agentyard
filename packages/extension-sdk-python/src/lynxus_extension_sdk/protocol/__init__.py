"""Hand-written protocol facade constrained by extension-protocol contracts."""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Mapping

AUTHORIZATION_HEADER = "Authorization"
REGISTRATION_ID_HEADER = "X-Lynxus-Extension-Registration-Id"
DESCRIPTOR_TYPE_HEADER = "X-Lynxus-Extension-Descriptor-Type"
DESCRIPTOR_ID_HEADER = "X-Lynxus-Extension-Descriptor-Id"
TRACE_ID_HEADER = "X-Lynxus-Trace-Id"
REQUEST_ID_HEADER = "X-Lynxus-Request-Id"
IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

SERVICE_LEVEL_REQUIRED_HEADERS = frozenset({AUTHORIZATION_HEADER})
SERVICE_LEVEL_OPTIONAL_HEADERS = frozenset({REGISTRATION_ID_HEADER})
SERVICE_LEVEL_HEADERS = frozenset({AUTHORIZATION_HEADER, REGISTRATION_ID_HEADER})

DESCRIPTOR_LEVEL_REQUIRED_HEADERS = frozenset(
    {
        AUTHORIZATION_HEADER,
        REGISTRATION_ID_HEADER,
        DESCRIPTOR_TYPE_HEADER,
        DESCRIPTOR_ID_HEADER,
        TRACE_ID_HEADER,
        REQUEST_ID_HEADER,
        IDEMPOTENCY_KEY_HEADER,
    }
)

CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS = frozenset(
    {
        AUTHORIZATION_HEADER,
        TRACE_ID_HEADER,
        REQUEST_ID_HEADER,
    }
)

EXTENSION_API_VERSION = 1
EXTENSION_MANIFEST_PATH = "/extension/manifest"
EXTENSION_HEALTH_PATH = "/extension/health"
HEALTH_LIVE_PATH = "/health/live"
HEALTH_READY_PATH = "/health/ready"

TOOL_CONNECTOR_INVOKE_ENDPOINT = "invoke"
CHANNEL_PROVIDER_SEND_OUTBOUND_ENDPOINT = "sendOutbound"
CHANNEL_PROVIDER_SEND_ACTIVITY_ENDPOINT = "sendActivity"
CHANNEL_PROVIDER_RUN_JOB_ENDPOINT = "runJob"
CREATE_CREDENTIAL_ENDPOINT = "createCredential"
ROTATE_CREDENTIAL_ENDPOINT = "rotateCredential"
REVOKE_CREDENTIAL_ENDPOINT = "revokeCredential"
VALIDATE_CREDENTIAL_ENDPOINT = "validateCredential"


class DescriptorType(StrEnum):
    TOOL_CONNECTOR = "TOOL_CONNECTOR"
    CHANNEL_PROVIDER = "CHANNEL_PROVIDER"


class ExtensionErrorCategory(StrEnum):
    AUTH = "AUTH"
    BAD_REQUEST = "BAD_REQUEST"
    REMOTE_TIMEOUT = "REMOTE_TIMEOUT"
    REMOTE_UNAVAILABLE = "REMOTE_UNAVAILABLE"
    REMOTE_RATE_LIMITED = "REMOTE_RATE_LIMITED"
    REMOTE_BUSINESS_REJECTED = "REMOTE_BUSINESS_REJECTED"
    PROTOCOL_ERROR = "PROTOCOL_ERROR"
    CIRCUIT_OPEN = "CIRCUIT_OPEN"
    UNKNOWN = "UNKNOWN"


class ExtensionErrorParseError(ValueError):
    """Raised when an ExtensionError payload violates the protocol shape."""


@dataclass(frozen=True, slots=True)
class ExtensionError:
    error_code: str
    message: str
    category: ExtensionErrorCategory
    retryable: bool
    details: dict[str, Any]


def parse_extension_error_json(raw_json: str) -> ExtensionError:
    try:
        value = json.loads(raw_json)
    except json.JSONDecodeError as exc:
        raise ExtensionErrorParseError("Invalid ExtensionError JSON") from exc
    return parse_extension_error_object(value)


def parse_extension_error_object(value: Mapping[str, Any]) -> ExtensionError:
    if not isinstance(value, Mapping):
        raise ExtensionErrorParseError("ExtensionError must be a JSON object")

    allowed_fields = {"errorCode", "message", "category", "retryable", "details"}
    unknown_fields = set(value) - allowed_fields
    if unknown_fields:
        raise ExtensionErrorParseError(f"ExtensionError has unknown fields: {sorted(unknown_fields)}")

    error_code = _required_string(value, "errorCode", 1, 128)
    message = _required_string(value, "message", 1, 4096)
    raw_category = _required_string(value, "category", 1, 128)
    try:
        category = ExtensionErrorCategory(raw_category)
    except ValueError as exc:
        raise ExtensionErrorParseError(f"Unknown ExtensionError.category {raw_category}") from exc

    if "retryable" not in value or not isinstance(value["retryable"], bool):
        raise ExtensionErrorParseError("ExtensionError.retryable must be boolean")
    if "details" not in value or not isinstance(value["details"], dict):
        raise ExtensionErrorParseError("ExtensionError.details must be an object")

    return ExtensionError(
        error_code=error_code,
        message=message,
        category=category,
        retryable=value["retryable"],
        details=dict(value["details"]),
    )


def parse_extension_error(value: str | Mapping[str, Any]) -> ExtensionError:
    if isinstance(value, str):
        return parse_extension_error_json(value)
    return parse_extension_error_object(value)


def build_service_level_headers(*, authorization: str, registration_id: str | None = None) -> dict[str, str]:
    headers = {AUTHORIZATION_HEADER: _required_header(authorization, AUTHORIZATION_HEADER)}
    if registration_id is not None and registration_id.strip():
        headers[REGISTRATION_ID_HEADER] = registration_id
    return headers


def build_descriptor_level_headers(
    *,
    authorization: str,
    registration_id: str,
    descriptor_type: DescriptorType | str,
    descriptor_id: str,
    trace_id: str,
    request_id: str,
    idempotency_key: str,
) -> dict[str, str]:
    descriptor_type_value = descriptor_type.value if isinstance(descriptor_type, DescriptorType) else descriptor_type
    return {
        AUTHORIZATION_HEADER: _required_header(authorization, AUTHORIZATION_HEADER),
        REGISTRATION_ID_HEADER: _required_header(registration_id, REGISTRATION_ID_HEADER),
        DESCRIPTOR_TYPE_HEADER: _required_header(descriptor_type_value, DESCRIPTOR_TYPE_HEADER),
        DESCRIPTOR_ID_HEADER: _required_header(descriptor_id, DESCRIPTOR_ID_HEADER),
        TRACE_ID_HEADER: _required_header(trace_id, TRACE_ID_HEADER),
        REQUEST_ID_HEADER: _required_header(request_id, REQUEST_ID_HEADER),
        IDEMPOTENCY_KEY_HEADER: _required_header(idempotency_key, IDEMPOTENCY_KEY_HEADER),
    }


def build_credential_lifecycle_headers(*, authorization: str, trace_id: str, request_id: str) -> dict[str, str]:
    return {
        AUTHORIZATION_HEADER: _required_header(authorization, AUTHORIZATION_HEADER),
        TRACE_ID_HEADER: _required_header(trace_id, TRACE_ID_HEADER),
        REQUEST_ID_HEADER: _required_header(request_id, REQUEST_ID_HEADER),
    }


def build_manifest_url(base_url: str) -> str:
    clean_base_url = _required_header(base_url, "base_url")
    if clean_base_url.endswith("/"):
        return clean_base_url[:-1] + EXTENSION_MANIFEST_PATH
    return clean_base_url + EXTENSION_MANIFEST_PATH


def parse_non_2xx_extension_error(status_code: int, raw_body: str) -> ExtensionError:
    if 200 <= status_code <= 299:
        raise ExtensionErrorParseError("ExtensionError response requires a non-2xx status code")
    return parse_extension_error_json(raw_body)


def _required_header(value: str, name: str) -> str:
    if value is None or not value.strip():
        raise ValueError(f"{name} must not be blank")
    return value


def _required_string(value: Mapping[str, Any], field: str, min_length: int, max_length: int) -> str:
    raw = value.get(field)
    if not isinstance(raw, str):
        raise ExtensionErrorParseError(f"ExtensionError.{field} must be string")
    if len(raw) < min_length or len(raw) > max_length:
        raise ExtensionErrorParseError(f"ExtensionError.{field} must be {min_length}-{max_length} characters")
    return raw


__all__ = [
    "AUTHORIZATION_HEADER",
    "CHANNEL_PROVIDER_RUN_JOB_ENDPOINT",
    "CHANNEL_PROVIDER_SEND_ACTIVITY_ENDPOINT",
    "CHANNEL_PROVIDER_SEND_OUTBOUND_ENDPOINT",
    "CREATE_CREDENTIAL_ENDPOINT",
    "CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS",
    "DESCRIPTOR_ID_HEADER",
    "DESCRIPTOR_LEVEL_REQUIRED_HEADERS",
    "DESCRIPTOR_TYPE_HEADER",
    "EXTENSION_API_VERSION",
    "EXTENSION_HEALTH_PATH",
    "EXTENSION_MANIFEST_PATH",
    "HEALTH_LIVE_PATH",
    "HEALTH_READY_PATH",
    "IDEMPOTENCY_KEY_HEADER",
    "REGISTRATION_ID_HEADER",
    "REQUEST_ID_HEADER",
    "REVOKE_CREDENTIAL_ENDPOINT",
    "ROTATE_CREDENTIAL_ENDPOINT",
    "SERVICE_LEVEL_HEADERS",
    "SERVICE_LEVEL_OPTIONAL_HEADERS",
    "SERVICE_LEVEL_REQUIRED_HEADERS",
    "TOOL_CONNECTOR_INVOKE_ENDPOINT",
    "TRACE_ID_HEADER",
    "VALIDATE_CREDENTIAL_ENDPOINT",
    "DescriptorType",
    "ExtensionError",
    "ExtensionErrorCategory",
    "ExtensionErrorParseError",
    "build_credential_lifecycle_headers",
    "build_descriptor_level_headers",
    "build_manifest_url",
    "build_service_level_headers",
    "parse_extension_error",
    "parse_extension_error_json",
    "parse_extension_error_object",
    "parse_non_2xx_extension_error",
]
