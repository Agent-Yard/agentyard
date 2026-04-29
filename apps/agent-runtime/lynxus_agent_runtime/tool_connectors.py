from __future__ import annotations

import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from threading import Lock
from typing import Any, Callable, Protocol
from urllib.parse import urljoin, urlsplit

import httpx
from lynxus_common import current_traceparent
from lynxus_extension_sdk.protocol import (
    DescriptorType,
    ExtensionError,
    ExtensionErrorCategory,
    ExtensionErrorParseError,
    build_descriptor_level_headers,
    parse_non_2xx_extension_error,
)

from .extension_registry import ToolConnectorRegistry, ToolConnectorRegistryEntry, load_tool_connector_registry
from .http_clients import shared_http_client_for_url
from .models import ToolConnectorDescriptor, ToolDescriptor, ToolOperationDescriptor


_DEFAULT_REGISTRY_LOCK = Lock()
_DEFAULT_TOOL_CONNECTOR_REGISTRY: ToolConnectorRegistry | None = None
_REMOTE_CIRCUIT_LOCK = Lock()
_REMOTE_CIRCUIT_STATES: dict[str, "_RemoteCircuitState"] = {}
_REMOTE_CIRCUIT_FAILURE_THRESHOLD = 2
_REMOTE_CIRCUIT_OPEN_SECONDS = 30.0
_NEVER_RETRY_CATEGORIES = {"PROTOCOL_ERROR", "CIRCUIT_OPEN"}
_NEVER_RETRY_ERROR_CODES = {
    "EXTENSION_PROTOCOL_ERROR",
    "TOOL_OUTPUT_INVALID",
    "TOOL_OUTPUT_SCHEMA_VIOLATION",
}


def default_tool_connector_registry() -> ToolConnectorRegistry:
    global _DEFAULT_TOOL_CONNECTOR_REGISTRY
    with _DEFAULT_REGISTRY_LOCK:
        if _DEFAULT_TOOL_CONNECTOR_REGISTRY is None:
            _DEFAULT_TOOL_CONNECTOR_REGISTRY = load_tool_connector_registry()
        return _DEFAULT_TOOL_CONNECTOR_REGISTRY


def set_default_tool_connector_registry(registry: ToolConnectorRegistry) -> None:
    global _DEFAULT_TOOL_CONNECTOR_REGISTRY
    with _DEFAULT_REGISTRY_LOCK:
        _DEFAULT_TOOL_CONNECTOR_REGISTRY = registry


def reset_default_tool_connector_registry() -> None:
    global _DEFAULT_TOOL_CONNECTOR_REGISTRY
    with _DEFAULT_REGISTRY_LOCK:
        _DEFAULT_TOOL_CONNECTOR_REGISTRY = None


def reset_remote_tool_connector_circuits() -> None:
    with _REMOTE_CIRCUIT_LOCK:
        _REMOTE_CIRCUIT_STATES.clear()


def default_idempotency_key(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
) -> str:
    seed = stable_json_dumps(
        {
            "resourceVersionId": descriptor.resourceVersionId,
            "operation": operation.name,
            "arguments": arguments,
        }
    )
    return "tool-call:" + hashlib.sha256(seed.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ConnectorRuntime:
    load_integration_account: Callable[[str], dict[str, Any]]
    internal_auth_headers: Callable[[], dict[str, str]]
    tool_connector_registry: Callable[[], ToolConnectorRegistry] = default_tool_connector_registry
    traceparent: Callable[[], str] = current_traceparent
    idempotency_key: Callable[[ToolDescriptor, ToolOperationDescriptor, dict[str, Any]], str] = default_idempotency_key
    retry_enabled: bool = False
    sleep: Callable[[float], None] = time.sleep
    monotonic: Callable[[], float] = time.monotonic


@dataclass(frozen=True)
class ConnectorCall:
    descriptor: ToolDescriptor
    connector: ToolConnectorDescriptor
    operation: ToolOperationDescriptor
    arguments: dict[str, Any]
    runtime: ConnectorRuntime


class ToolConnector(Protocol):
    connector_type: str

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        ...


class ToolConnectorProtocolError(ValueError):
    pass


class ToolConnectorRemoteError(RuntimeError):
    def __init__(self, *, http_status: int, extension_error: ExtensionError) -> None:
        self.http_status = http_status
        self.extension_error = extension_error
        self.error_code = extension_error.error_code
        self.category = extension_error.category.value
        self.retryable = extension_error.retryable
        self.details = dict(extension_error.details)
        super().__init__(
            f"remote tool connector failed: status={http_status} category={self.category} errorCode={self.error_code}"
        )


@dataclass
class _RemoteCircuitState:
    consecutive_failures: int = 0
    open_until: float = 0.0


class SimpleHttpConnector:
    connector_type = "simple-http"

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        mapping = operation_mapping(request.connector, request.operation)
        endpoint = http_connector_endpoint(request.connector, mapping)
        headers = self._auth_headers(request)
        response = send_http_connector_request(
            endpoint,
            request.connector.timeoutSeconds,
            mapping,
            request.arguments,
            headers=headers,
        )
        return json_object_response(response, request.descriptor, request.operation)

    def _auth_headers(self, request: ConnectorCall) -> dict[str, str]:
        connector = request.connector
        account_id = connector_account_id(connector)
        if account_id is None:
            return {}
        account = load_matching_account(request, self.connector_type)
        credential = account.get("credential")
        if not isinstance(credential, dict):
            raise ValueError(f"integration account {account_id} credential must be an object")
        bearer_token = first_non_blank(
            str(credential.get("bearerToken") or "").strip() or None,
            str(credential.get("token") or "").strip() or None,
            str(credential.get("accessToken") or "").strip() or None,
        )
        if bearer_token is None:
            raise ValueError(
                f"integration account {account_id} for tool {request.descriptor.resourceVersionId} requires bearerToken"
            )
        header_name = str(connector.config.get("authorizationHeader") or "Authorization").strip() or "Authorization"
        return {header_name: f"Bearer {bearer_token}"}


class BusinessCodeSecretHttpConnector:
    connector_type = "business-code-secret-http"

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        account = load_matching_account(request, self.connector_type, required=True)
        account_id = connector_account_id(request.connector)
        credential = account.get("credential")
        if not isinstance(credential, dict):
            raise ValueError(f"integration account {account_id} credential must be an object")
        business_code = str(credential.get("businessCode") or "").strip()
        secret_key = str(credential.get("secretKey") or "").strip()
        if not business_code or not secret_key:
            raise ValueError(f"integration account {account_id} requires businessCode and secretKey")

        mapping = operation_mapping(request.connector, request.operation)
        endpoint = http_connector_endpoint(request.connector, mapping)
        payload = dict(request.arguments)
        encrypted = hmac.new(
            secret_key.encode("utf-8"),
            stable_json_dumps(request.arguments).encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        payload[str(request.connector.config.get("businessCodeField") or "businessCode")] = business_code
        payload[str(request.connector.config.get("encryptedField") or "encrypted")] = encrypted
        response = send_http_connector_request(endpoint, request.connector.timeoutSeconds, mapping, payload)
        return json_object_response(response, request.descriptor, request.operation)


class McpConnector:
    connector_type = "mcp"

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        connection_uri = str(request.connector.config.get("connectionUri") or "").strip()
        if not connection_uri:
            raise ValueError(f"tool {request.descriptor.resourceVersionId} MCP connector requires config.connectionUri")
        mapping = operation_mapping(request.connector, request.operation)
        remote_operation = str(mapping.get("tool") or request.operation.name)
        timeout_seconds = max(1, request.connector.timeoutSeconds)
        client = shared_http_client_for_url(connection_uri)
        headers = request.runtime.internal_auth_headers() if config_bool(request.connector.config, "internalAuthEnabled") else {}
        response = client.post(
            connection_uri,
            headers=headers,
            json={
                "serverName": request.connector.config.get("serverName") or "",
                "namespace": request.connector.config.get("namespace") or "",
                "transport": request.connector.config.get("transport") or "STREAMABLE_HTTP",
                "tool": remote_operation,
                "arguments": request.arguments,
            },
            timeout=timeout_seconds,
        )
        response.raise_for_status()
        return json_object_response(response, request.descriptor, request.operation)


class RemoteToolConnectorAdapter:
    def __init__(self, registry_entry: ToolConnectorRegistryEntry) -> None:
        self._registry_entry = registry_entry

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        circuit_error = _open_circuit_error(request.connector.connectorType, request.runtime.monotonic())
        if circuit_error is not None:
            raise circuit_error
        body = self._request_body(request)
        idempotency_key = body["execution"]["idempotencyKey"]
        traceparent = body["execution"]["traceContext"]["traceparent"]
        headers = build_descriptor_level_headers(
            authorization=_authorization_header(request.runtime.internal_auth_headers()),
            registration_id=self._registry_entry.registration_id,
            descriptor_type=DescriptorType.TOOL_CONNECTOR,
            descriptor_id=request.connector.connectorType,
            trace_id=_trace_id_from_traceparent(traceparent),
            request_id=idempotency_key,
            idempotency_key=idempotency_key,
        )
        url = descriptor_endpoint_url(self._registry_entry.base_url, self._registry_entry.invoke_path)
        client = shared_http_client_for_url(url)
        timeout_seconds = max(1, request.connector.timeoutSeconds)
        max_attempts = _max_attempts(request)
        attempt = 1
        while True:
            try:
                response = client.post(url, headers=headers, json=body, timeout=timeout_seconds)
                output = self._parse_response(response)
            except ToolConnectorRemoteError as error:
                if _should_retry(request, error, attempt, max_attempts):
                    _sleep_before_retry(request, attempt)
                    attempt += 1
                    continue
                _record_retryable_exhausted_failure(request, error, attempt, max_attempts)
                raise
            except httpx.TimeoutException as error:
                remote_error = _synthetic_remote_error(
                    http_status=504,
                    error_code="REMOTE_TRANSPORT_TIMEOUT",
                    message="remote tool connector transport timed out",
                    category=ExtensionErrorCategory.REMOTE_TIMEOUT,
                    retryable=True,
                )
                if _should_retry(request, remote_error, attempt, max_attempts):
                    _sleep_before_retry(request, attempt)
                    attempt += 1
                    continue
                _record_retryable_exhausted_failure(request, remote_error, attempt, max_attempts)
                raise remote_error from error
            except httpx.RequestError as error:
                remote_error = _synthetic_remote_error(
                    http_status=503,
                    error_code="REMOTE_TRANSPORT_UNAVAILABLE",
                    message="remote tool connector transport unavailable",
                    category=ExtensionErrorCategory.REMOTE_UNAVAILABLE,
                    retryable=True,
                )
                if _should_retry(request, remote_error, attempt, max_attempts):
                    _sleep_before_retry(request, attempt)
                    attempt += 1
                    continue
                _record_retryable_exhausted_failure(request, remote_error, attempt, max_attempts)
                raise remote_error from error
            _record_remote_success(request.connector.connectorType)
            return output

    def _request_body(self, request: ConnectorCall) -> dict[str, Any]:
        traceparent = request.runtime.traceparent()
        idempotency_key = request.runtime.idempotency_key(request.descriptor, request.operation, request.arguments)
        body: dict[str, Any] = {
            "connectorType": request.connector.connectorType,
            "tool": {
                "resourceId": request.descriptor.resourceId,
                "resourceVersionId": request.descriptor.resourceVersionId,
                "name": request.descriptor.resourceName,
            },
            "operation": {
                "name": request.operation.name,
                "description": request.operation.description,
            },
            "config": {
                "connector": dict(request.connector.config),
                "operationMapping": operation_mapping(request.connector, request.operation),
            },
            "input": {
                "arguments": dict(request.arguments),
            },
            "execution": {
                "idempotencyKey": idempotency_key,
                "timeoutSeconds": max(1, request.connector.timeoutSeconds),
                "traceContext": {
                    "traceparent": traceparent,
                },
            },
        }
        external_secret_ref = connector_external_secret_ref(request.connector)
        if external_secret_ref is not None:
            body["externalSecretRef"] = external_secret_ref
        return body

    def _parse_response(self, response: Any) -> dict[str, Any]:
        status_code = int(getattr(response, "status_code", 200))
        if not 200 <= status_code <= 299:
            raw_body = str(getattr(response, "text", ""))
            try:
                extension_error = parse_non_2xx_extension_error(status_code, raw_body)
            except ExtensionErrorParseError as error:
                if status_code >= 500:
                    raise _synthetic_remote_error(
                        http_status=status_code,
                        error_code=f"REMOTE_HTTP_{status_code}",
                        message="remote tool connector returned 5xx without a valid ExtensionError",
                        category=ExtensionErrorCategory.REMOTE_TIMEOUT
                        if status_code == 504
                        else ExtensionErrorCategory.REMOTE_UNAVAILABLE,
                        retryable=True,
                    ) from error
                raise ToolConnectorProtocolError(
                    "remote tool connector returned non-2xx without a valid ExtensionError"
                ) from error
            if extension_error.category == ExtensionErrorCategory.CIRCUIT_OPEN:
                extension_error = ExtensionError(
                    error_code="EXTENSION_RETURNED_CIRCUIT_OPEN",
                    message="remote tool connector returned reserved CIRCUIT_OPEN category",
                    category=ExtensionErrorCategory.PROTOCOL_ERROR,
                    retryable=False,
                    details={},
                )
            raise ToolConnectorRemoteError(http_status=status_code, extension_error=extension_error)

        try:
            payload = response.json()
        except Exception as error:  # noqa: BLE001
            raise ToolConnectorProtocolError("remote tool connector response must return a JSON object envelope") from error
        if not isinstance(payload, dict):
            raise ToolConnectorProtocolError("remote tool connector response must return a JSON object envelope")
        if payload.get("status") != "SUCCEEDED":
            raise ToolConnectorProtocolError("remote tool connector response status must be SUCCEEDED")
        output = payload.get("output")
        if not isinstance(output, dict):
            raise ToolConnectorProtocolError("remote tool connector response output must be a JSON object")
        if not isinstance(payload.get("metadata"), dict):
            raise ToolConnectorProtocolError("remote tool connector response metadata must be a JSON object")
        return dict(output)


CONNECTOR_REGISTRY: dict[str, ToolConnector] = {
    connector.connector_type: connector
    for connector in (
        SimpleHttpConnector(),
        BusinessCodeSecretHttpConnector(),
        McpConnector(),
    )
}


def call_connector_tool(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
    runtime: ConnectorRuntime,
) -> dict[str, Any]:
    connector = require_tool_connector(descriptor)
    connector_type = connector.connectorType.strip()
    registry_entry = runtime.tool_connector_registry().require(connector_type)
    call = ConnectorCall(descriptor, connector, operation, arguments, runtime)
    implementation = CONNECTOR_REGISTRY.get(connector_type)
    if implementation is not None:
        return implementation.call(call)
    return RemoteToolConnectorAdapter(registry_entry).call(call)


def _max_attempts(request: ConnectorCall) -> int:
    policy = request.connector.retryPolicy
    if not request.runtime.retry_enabled or policy.mode == "NONE":
        return 1
    return max(1, policy.maxAttempts)


def _should_retry(request: ConnectorCall, error: ToolConnectorRemoteError, attempt: int, max_attempts: int) -> bool:
    policy = request.connector.retryPolicy
    if not request.runtime.retry_enabled or policy.mode == "NONE":
        return False
    if attempt >= max_attempts:
        return False
    if not error.retryable:
        return False
    if error.category in _NEVER_RETRY_CATEGORIES or error.error_code in _NEVER_RETRY_ERROR_CODES:
        return False
    return _matches_retry_policy(policy, error)


def _matches_retry_policy(policy: Any, error: ToolConnectorRemoteError) -> bool:
    categories = set(policy.retryableCategories)
    error_codes = set(policy.retryableErrorCodes)
    if error_codes and error.error_code in error_codes:
        return True
    return error.category in categories


def _sleep_before_retry(request: ConnectorCall, attempt: int) -> None:
    delay_seconds = _retry_delay_seconds(request.connector.retryPolicy, attempt)
    if delay_seconds > 0:
        request.runtime.sleep(delay_seconds)


def _retry_delay_seconds(policy: Any, attempt: int) -> float:
    if policy.mode == "FIXED":
        delay_ms = policy.initialDelayMs
    elif policy.mode == "EXPONENTIAL":
        delay_ms = int(policy.initialDelayMs * (policy.backoffMultiplier ** max(0, attempt - 1)))
    else:
        return 0.0
    if policy.maxDelayMs > 0:
        delay_ms = min(delay_ms, policy.maxDelayMs)
    return max(0, delay_ms) / 1000.0


def _record_retryable_exhausted_failure(
    request: ConnectorCall,
    error: ToolConnectorRemoteError,
    attempt: int,
    max_attempts: int,
) -> None:
    policy = request.connector.retryPolicy
    if not request.runtime.retry_enabled or policy.mode == "NONE" or attempt < max_attempts:
        return
    if not error.retryable or error.category in _NEVER_RETRY_CATEGORIES or error.error_code in _NEVER_RETRY_ERROR_CODES:
        return
    if not _matches_retry_policy(policy, error):
        return
    with _REMOTE_CIRCUIT_LOCK:
        state = _REMOTE_CIRCUIT_STATES.setdefault(request.connector.connectorType, _RemoteCircuitState())
        state.consecutive_failures += 1
        if state.consecutive_failures >= _REMOTE_CIRCUIT_FAILURE_THRESHOLD:
            state.open_until = request.runtime.monotonic() + _REMOTE_CIRCUIT_OPEN_SECONDS


def _record_remote_success(connector_type: str) -> None:
    with _REMOTE_CIRCUIT_LOCK:
        _REMOTE_CIRCUIT_STATES.pop(connector_type, None)


def _open_circuit_error(connector_type: str, now: float) -> ToolConnectorRemoteError | None:
    with _REMOTE_CIRCUIT_LOCK:
        state = _REMOTE_CIRCUIT_STATES.get(connector_type)
        if state is None or state.open_until <= 0:
            return None
        if state.open_until <= now:
            _REMOTE_CIRCUIT_STATES.pop(connector_type, None)
            return None
    return _synthetic_remote_error(
        http_status=503,
        error_code="CIRCUIT_OPEN",
        message="remote tool connector circuit is open",
        category=ExtensionErrorCategory.CIRCUIT_OPEN,
        retryable=True,
    )


def _synthetic_remote_error(
    *,
    http_status: int,
    error_code: str,
    message: str,
    category: ExtensionErrorCategory,
    retryable: bool,
) -> ToolConnectorRemoteError:
    return ToolConnectorRemoteError(
        http_status=http_status,
        extension_error=ExtensionError(
            error_code=error_code,
            message=message,
            category=category,
            retryable=retryable,
            details={},
        ),
    )


def require_tool_connector(descriptor: ToolDescriptor) -> ToolConnectorDescriptor:
    if descriptor.connector is None:
        raise ValueError(f"tool {descriptor.resourceVersionId} is missing connector config")
    return descriptor.connector


def load_matching_account(request: ConnectorCall, expected_connector_type: str, required: bool = False) -> dict[str, Any]:
    connector = request.connector
    account_id = connector_account_id(connector)
    if account_id is None:
        if required:
            raise ValueError(
                f"tool {request.descriptor.resourceVersionId} {expected_connector_type} connector requires accountSnapshot.accountId"
            )
        return {}
    account = request.runtime.load_integration_account(account_id)
    if str(account.get("accountId") or "").strip() != account_id:
        raise ValueError(f"integration account response accountId must be {account_id}")
    if str(account.get("subjectType") or "").upper() != "TOOL_CONNECTOR":
        raise ValueError(f"integration account {account_id} subjectType must be TOOL_CONNECTOR")
    if str(account.get("subjectId") or "").strip() != expected_connector_type:
        raise ValueError(f"integration account {account_id} subjectId must be {expected_connector_type}")
    if str(account.get("status") or "").upper() != "ENABLED":
        raise ValueError(f"integration account {account_id} is not ENABLED")
    return account


def connector_account_id(connector: ToolConnectorDescriptor) -> str | None:
    if connector.accountSnapshot is None:
        return None
    account_id = connector.accountSnapshot.accountId.strip()
    return account_id or None


def connector_external_secret_ref(connector: ToolConnectorDescriptor) -> str | None:
    if connector.accountSnapshot is None:
        return None
    external_secret_ref = str(connector.accountSnapshot.externalSecretRef or "").strip()
    return external_secret_ref or None


def operation_mapping(connector: ToolConnectorDescriptor, operation: ToolOperationDescriptor) -> dict[str, Any]:
    return dict(connector.operationMappings.get(operation.name) or {})


def http_connector_endpoint(connector: ToolConnectorDescriptor, mapping: dict[str, Any]) -> str:
    endpoint = str(mapping.get("endpoint") or "").strip()
    if endpoint:
        return endpoint
    base_url = str(connector.config.get("baseUrl") or "").strip()
    if not base_url:
        raise ValueError(f"{connector.connectorType} connector requires config.baseUrl or operation endpoint")
    path = str(mapping.get("path") or "").strip()
    return urljoin(base_url.rstrip("/") + "/", path.lstrip("/"))


def send_http_connector_request(
    endpoint: str,
    timeout_seconds: int,
    mapping: dict[str, Any],
    payload: dict[str, Any],
    headers: dict[str, str] | None = None,
) -> Any:
    method = str(mapping.get("method") or "POST").upper()
    placement = str(mapping.get("requestPlacement") or ("QUERY" if method == "GET" else "JSON_BODY")).upper()
    request_kwargs: dict[str, Any] = {}
    if headers:
        request_kwargs["headers"] = headers
    if placement == "QUERY" or method == "GET":
        request_kwargs["params"] = payload
    else:
        request_kwargs["json"] = payload
    client = shared_http_client_for_url(endpoint)
    response = client.request(method, endpoint, timeout=max(1, timeout_seconds), **request_kwargs)
    response.raise_for_status()
    return response


def descriptor_endpoint_url(base_url: str, declared_path: str) -> str:
    path = declared_path.strip()
    if not path.startswith("/"):
        raise ToolConnectorProtocolError("remote tool connector descriptor endpoints.invoke must start with /")
    parsed = urlsplit(path)
    if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
        raise ToolConnectorProtocolError("remote tool connector descriptor endpoints.invoke must be a declared path")
    return base_url.rstrip("/") + path


def _authorization_header(headers: dict[str, str]) -> str:
    authorization = str(headers.get("Authorization") or "").strip()
    if not authorization:
        raise ValueError("remote tool connector invocation requires internal Authorization header")
    return authorization


def _trace_id_from_traceparent(traceparent: str) -> str:
    parts = traceparent.strip().split("-")
    if len(parts) >= 2 and len(parts[1]) == 32:
        return parts[1]
    raise ValueError("traceparent must include a trace id")


def json_object_response(response: Any, descriptor: ToolDescriptor, operation: ToolOperationDescriptor) -> dict[str, Any]:
    payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError(f"tool {descriptor.resourceName}.{operation.name} must return a JSON object")
    return payload


def stable_json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def config_bool(config: dict[str, Any], key: str, default: bool = False) -> bool:
    value = config.get(key)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes", "y", "on"}
    return bool(value)


def first_non_blank(*values: str | None) -> str | None:
    for value in values:
        if value is not None and value.strip():
            return value.strip()
    return None
