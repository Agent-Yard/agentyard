from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from typing import Any, Callable, Protocol
from urllib.parse import urljoin

from .http_clients import shared_http_client_for_url
from .models import ToolConnectorDescriptor, ToolDescriptor, ToolOperationDescriptor


@dataclass(frozen=True)
class ConnectorRuntime:
    load_integration_account: Callable[[str], dict[str, Any]]
    internal_auth_headers: Callable[[], dict[str, str]]


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


class SimpleHttpConnector:
    connector_type = "SIMPLE_HTTP"

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
        if not connector.accountId:
            return {}
        account = load_matching_account(request, self.connector_type)
        credential = account.get("credential")
        if not isinstance(credential, dict):
            raise ValueError(f"integration account {connector.accountId} credential must be an object")
        bearer_token = first_non_blank(
            str(credential.get("bearerToken") or "").strip() or None,
            str(credential.get("token") or "").strip() or None,
            str(credential.get("accessToken") or "").strip() or None,
        )
        if bearer_token is None:
            raise ValueError(
                f"integration account {connector.accountId} for tool {request.descriptor.resourceVersionId} requires bearerToken"
            )
        header_name = str(connector.config.get("authorizationHeader") or "Authorization").strip() or "Authorization"
        return {header_name: f"Bearer {bearer_token}"}


class BusinessCodeSecretHttpConnector:
    connector_type = "BUSINESS_CODE_SECRET_HTTP"

    def call(self, request: ConnectorCall) -> dict[str, Any]:
        account = load_matching_account(request, self.connector_type, required=True)
        credential = account.get("credential")
        if not isinstance(credential, dict):
            raise ValueError(f"integration account {request.connector.accountId} credential must be an object")
        business_code = str(credential.get("businessCode") or "").strip()
        secret_key = str(credential.get("secretKey") or "").strip()
        if not business_code or not secret_key:
            raise ValueError(f"integration account {request.connector.accountId} requires businessCode and secretKey")

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
    connector_type = "MCP"

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
    connector_type = connector.connectorType.upper()
    implementation = CONNECTOR_REGISTRY.get(connector_type)
    if implementation is None:
        raise ValueError(f"unsupported tool connector: {connector.connectorType}")
    return implementation.call(ConnectorCall(descriptor, connector, operation, arguments, runtime))


def require_tool_connector(descriptor: ToolDescriptor) -> ToolConnectorDescriptor:
    if descriptor.connector is None:
        raise ValueError(f"tool {descriptor.resourceVersionId} is missing connector config")
    return descriptor.connector


def load_matching_account(request: ConnectorCall, expected_connector_type: str, required: bool = False) -> dict[str, Any]:
    connector = request.connector
    if not connector.accountId:
        if required:
            raise ValueError(f"tool {request.descriptor.resourceVersionId} {expected_connector_type} connector requires accountId")
        return {}
    account = request.runtime.load_integration_account(connector.accountId)
    if str(account.get("connectorType") or "").upper() != expected_connector_type:
        raise ValueError(f"integration account {connector.accountId} connectorType must be {expected_connector_type}")
    if str(account.get("status") or "").upper() != "ACTIVE":
        raise ValueError(f"integration account {connector.accountId} is not ACTIVE")
    return account


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
