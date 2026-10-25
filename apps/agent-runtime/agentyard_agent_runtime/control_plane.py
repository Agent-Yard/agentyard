"""Authenticated control-plane calls; model input can never supply the principal."""
from __future__ import annotations

import os
from typing import Any
from urllib.parse import quote

from .http_clients import shared_http_client_for_url
from .models import ActivationRuntime, ActivationStreamFrame


def base_url() -> str:
    return (os.getenv("AGENTYARD_API_BASE_URL") or "http://127.0.0.1:8080/api").rstrip("/")


def auth_headers() -> dict[str, str]:
    token = (os.getenv("AGENTYARD_INTERNAL_AUTH_TOKEN") or "").strip()
    if not token:
        raise RuntimeError("internal authentication token is required")
    return {"Authorization": f"Bearer {token}"}


def request(path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
    endpoint = base_url() + path
    client = shared_http_client_for_url(endpoint)
    response = client.get(endpoint, headers=auth_headers(), timeout=30) if body is None else client.post(
        endpoint, headers=auth_headers(), json=body, timeout=30)
    response.raise_for_status()
    result = response.json()
    if not isinstance(result, dict):
        raise ValueError("control-plane response must be an object")
    return result


def activation_runtime(activation_id: str) -> ActivationRuntime:
    runtime = ActivationRuntime.model_validate(request("/internal/activations/" + quote(activation_id, safe="") + "/runtime"))
    if runtime.activationId != activation_id:
        raise ValueError("control-plane activation identity mismatch")
    return runtime


def invoke_tool(runtime: ActivationRuntime, call_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return request("/internal/activations/" + quote(runtime.activationId, safe="") + "/tools",
        {"callId": call_id, "name": name, "arguments": arguments})


def publish_frame(frame: ActivationStreamFrame) -> dict[str, Any]:
    return request("/internal/activations/" + quote(frame.activationId, safe="") + "/stream",
        frame.model_dump(mode="json"))


def authorize_resource(runtime: ActivationRuntime, call_id: str, resource_type: str, resource_id: str,
                       operation: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return invoke_tool(runtime, call_id + ":authorize", "resource_authorize", {
        "resourceType": resource_type, "resourceId": resource_id,
        "operation": operation, "arguments": arguments})
