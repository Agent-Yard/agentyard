from __future__ import annotations

import json
import os
from typing import Any

import httpx
import pytest

os.environ.setdefault("AGENTYARD_INTERNAL_AUTH_TOKEN", "test-internal-token")

from agentyard_agent_runtime.http_clients import reset_shared_http_client_registry  # noqa: E402
from agentyard_agent_runtime.models import PlaybookToolTaskRequest, ToolDescriptor, ToolOperationDescriptor  # noqa: E402
from agentyard_agent_runtime.tool_connectors import (  # noqa: E402
    ConnectorRuntime,
    ToolConnectorProtocolError,
    ToolConnectorRemoteError,
    call_connector_tool,
    reset_default_tool_connector_registry,
    reset_remote_tool_connector_circuits,
    set_default_tool_connector_registry,
)
from agentyard_agent_runtime.tooling import execute_playbook_tool_task  # noqa: E402
from agentyard_agent_runtime.extension_registry import (  # noqa: E402
    ToolConnectorRegistry,
    ToolConnectorRegistryEntry,
)


TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class _FakeResponse:
    def __init__(self, payload: Any, *, status_code: int = 200) -> None:
        self._payload = payload
        self.status_code = status_code
        self.text = json.dumps(payload, ensure_ascii=False)

    def raise_for_status(self) -> None:
        return None

    def json(self) -> Any:
        return self._payload


class _FakeClient:
    def __init__(self, request_log: list[dict[str, Any]], response: _FakeResponse | list[Any]) -> None:
        self._request_log = request_log
        self._responses = response if isinstance(response, list) else [response]

    def request(self, method: str, url: str, **kwargs: Any) -> _FakeResponse:
        self._request_log.append({"method": method.upper(), "url": url, **kwargs})
        return _FakeResponse({"ticketId": "t-local", "routeKey": "success"})

    def post(self, url: str, **kwargs: Any) -> _FakeResponse:
        self._request_log.append({"method": "POST", "url": url, **kwargs})
        response = self._responses.pop(0) if len(self._responses) > 1 else self._responses[0]
        if isinstance(response, BaseException):
            raise response
        return response

    def close(self) -> None:
        return None


@pytest.fixture(autouse=True)
def _reset_http_clients() -> None:
    yield
    reset_shared_http_client_registry()
    reset_default_tool_connector_registry()
    reset_remote_tool_connector_circuits()


def test_builtin_connector_uses_registry_but_stays_local(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="simple-http",
        account_snapshot=None,
        config={"baseUrl": "https://local-tool.example"},
        operation_mapping={"method": "POST", "path": "/create", "requestPlacement": "JSON_BODY"},
    )
    operation = descriptor.operations[0]
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(_remote_success({"ignored": True}))),
    )

    output = call_connector_tool(
        descriptor,
        operation,
        {"ticketId": "t-1"},
        _runtime(_registry(_entry("simple-http", base_url="https://extensions.example.test/core"))),
    )

    assert output == {"ticketId": "t-local", "routeKey": "success"}
    assert request_log == [
        {
            "method": "POST",
            "url": "https://local-tool.example/create",
            "timeout": 15,
            "json": {"ticketId": "t-1"},
        }
    ]


def test_remote_connector_request_uses_descriptor_endpoint_headers_and_snapshot_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        account_snapshot={"accountId": "integration-account-1", "externalSecretRef": "vault://secret-1"},
        config={"tenant": "acme"},
        operation_mapping={"externalOperation": "tickets.create"},
        timeout_seconds=7,
    )
    operation = descriptor.operations[0]
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(_remote_success({"ticketId": "t-1"}))),
    )

    output = call_connector_tool(
        descriptor,
        operation,
        {"ticketId": "t-1", "note": "customer asked for help"},
        _runtime(
            _registry(
                _entry(
                    "enterprise.acme.crm",
                    base_url="https://extensions.example.test/enterprise-tools",
                    invoke_path="/tools/invoke",
                )
            )
        ),
    )

    assert output == {"ticketId": "t-1"}
    assert len(request_log) == 1
    outbound = request_log[0]
    assert outbound["method"] == "POST"
    assert outbound["url"] == "https://extensions.example.test/enterprise-tools/tools/invoke"
    assert outbound["timeout"] == 7
    assert outbound["headers"] == {
        "Authorization": "Bearer test-internal-token",
        "X-AgentYard-Extension-Registration-Id": "enterprise-tools",
        "X-AgentYard-Extension-Descriptor-Type": "TOOL_CONNECTOR",
        "X-AgentYard-Extension-Descriptor-Id": "enterprise.acme.crm",
        "X-AgentYard-Trace-Id": "4bf92f3577b34da6a3ce929d0e0e4736",
        "X-AgentYard-Request-Id": "tool-call-1",
        "Idempotency-Key": "tool-call-1",
    }
    assert outbound["json"] == {
        "connectorType": "enterprise.acme.crm",
        "externalSecretRef": "vault://secret-1",
        "tool": {
            "resourceId": "tool-1",
            "resourceVersionId": "rv-tool-1",
            "name": "Ticket Tool",
        },
        "operation": {
            "name": "create_ticket",
            "description": "Create a ticket",
        },
        "config": {
            "connector": {"tenant": "acme"},
            "operationMapping": {"externalOperation": "tickets.create"},
        },
        "input": {"arguments": {"ticketId": "t-1", "note": "customer asked for help"}},
        "execution": {
            "idempotencyKey": "tool-call-1",
            "timeoutSeconds": 7,
            "traceContext": {"traceparent": TRACEPARENT},
        },
    }
    assert "accountId" not in json.dumps(outbound["json"], ensure_ascii=False)
    assert "inputSchema" not in outbound["json"]
    assert "outputSchema" not in outbound["json"]


def test_remote_external_secret_ref_is_omitted_when_snapshot_does_not_contain_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        account_snapshot={"accountId": "integration-account-1"},
    )
    operation = descriptor.operations[0]
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(_remote_success({"ticketId": "t-1"}))),
    )

    call_connector_tool(
        descriptor,
        operation,
        {"ticketId": "t-1"},
        _runtime(_registry(_entry("enterprise.acme.crm"))),
    )

    assert "externalSecretRef" not in request_log[0]["json"]
    assert "accountId" not in json.dumps(request_log[0]["json"], ensure_ascii=False)


@pytest.mark.parametrize(
    "payload,match",
    [
        (["not-object"], "must return a JSON object envelope"),
        ({"status": "FAILED", "output": {}, "metadata": {}}, "status must be SUCCEEDED"),
        ({"status": "SUCCEEDED", "output": [], "metadata": {}}, "output must be a JSON object"),
        ({"status": "SUCCEEDED", "output": {}, "metadata": []}, "metadata must be a JSON object"),
    ],
)
def test_remote_success_envelope_validation(
    payload: Any,
    match: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(connector_type="enterprise.acme.crm")
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(payload)),
    )

    with pytest.raises(ToolConnectorProtocolError, match=match):
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm"))),
        )


def test_remote_non_2xx_extension_error_is_mapped_with_protocol_fields(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        account_snapshot={"accountId": "integration-account-1", "externalSecretRef": "vault://secret-1"},
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            _FakeResponse(
                {
                    "errorCode": "REMOTE_AUTH_FAILED",
                    "message": "credential expired",
                    "category": "AUTH",
                    "retryable": False,
                    "details": {},
                },
                status_code=401,
            ),
        ),
    )

    with pytest.raises(ToolConnectorRemoteError) as error_info:
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm"))),
        )

    assert error_info.value.http_status == 401
    assert error_info.value.error_code == "REMOTE_AUTH_FAILED"
    assert error_info.value.category == "AUTH"
    assert error_info.value.retryable is False
    assert "vault://secret-1" not in str(error_info.value)


def test_remote_retry_reuses_idempotency_key_timeout_and_sleeps_until_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        timeout_seconds=7,
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=3,
            initial_delay_ms=100,
            max_delay_ms=100,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []
    sleeps: list[float] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                _FakeResponse(_remote_error(category="REMOTE_UNAVAILABLE", retryable=True), status_code=503),
                _FakeResponse(_remote_success({"ticketId": "t-1"})),
            ],
        ),
    )

    output = call_connector_tool(
        descriptor,
        descriptor.operations[0],
        {"ticketId": "t-1"},
        _runtime(
            _registry(_entry("enterprise.acme.crm")),
            retry_enabled=True,
            sleep=sleeps.append,
        ),
    )

    assert output == {"ticketId": "t-1"}
    assert len(request_log) == 2
    assert sleeps == [0.1]
    assert [entry["timeout"] for entry in request_log] == [7, 7]
    assert {entry["headers"]["Idempotency-Key"] for entry in request_log} == {"tool-call-1"}
    assert {entry["json"]["execution"]["idempotencyKey"] for entry in request_log} == {"tool-call-1"}


def test_agent_remote_tool_call_does_not_retry_when_runtime_retry_is_disabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=3,
            initial_delay_ms=100,
            max_delay_ms=100,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                _FakeResponse(_remote_error(category="REMOTE_UNAVAILABLE", retryable=True), status_code=503),
                _FakeResponse(_remote_success({"ticketId": "t-1"})),
            ],
        ),
    )

    with pytest.raises(ToolConnectorRemoteError) as error_info:
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm"))),
        )

    assert error_info.value.category == "REMOTE_UNAVAILABLE"
    assert len(request_log) == 1


def test_remote_retry_can_match_retryable_error_code(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=2,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_error_codes=["VENDOR_BUSY"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                _FakeResponse(
                    _remote_error(
                        category="REMOTE_BUSINESS_REJECTED",
                        retryable=True,
                        error_code="VENDOR_BUSY",
                    ),
                    status_code=409,
                ),
                _FakeResponse(_remote_success({"ticketId": "t-1"})),
            ],
        ),
    )

    output = call_connector_tool(
        descriptor,
        descriptor.operations[0],
        {"ticketId": "t-1"},
        _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True),
    )

    assert output == {"ticketId": "t-1"}
    assert len(request_log) == 2


def test_playbook_tool_task_enables_remote_connector_retry(monkeypatch: pytest.MonkeyPatch) -> None:
    payload = _playbook_request_payload()
    tool = payload["ownerAgent"]["tools"][0]
    tool["connector"] = {
        "connectorType": "enterprise.acme.crm",
        "accountSnapshot": None,
        "timeoutSeconds": 15,
        "retryPolicy": _retry_policy(
            mode="FIXED",
            max_attempts=2,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
        "config": {},
        "operationMappings": {"create_ticket": {}},
    }
    request = PlaybookToolTaskRequest.model_validate(payload)
    request_log: list[dict[str, Any]] = []

    set_default_tool_connector_registry(_registry(_entry("enterprise.acme.crm")))
    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                _FakeResponse(_remote_error(category="REMOTE_UNAVAILABLE", retryable=True), status_code=503),
                _FakeResponse(_remote_success({"ticketId": "t-100"})),
            ],
        ),
    )

    result = execute_playbook_tool_task(request)

    assert result.statePatch["workflow.ticket"]["ticketId"] == "t-100"
    assert len(request_log) == 2


def test_remote_retry_ignores_non_retryable_extension_error(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=3,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            _FakeResponse(_remote_error(category="REMOTE_UNAVAILABLE", retryable=False), status_code=503),
        ),
    )

    with pytest.raises(ToolConnectorRemoteError):
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True),
        )

    assert len(request_log) == 1


@pytest.mark.parametrize(
    "payload",
    [
        {"status": "FAILED", "output": {}, "metadata": {}},
        {"status": "SUCCEEDED", "output": [], "metadata": {}},
    ],
)
def test_remote_protocol_envelope_error_is_not_retried(payload: dict[str, Any], monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=3,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(payload)),
    )

    with pytest.raises(ToolConnectorProtocolError):
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True),
        )

    assert len(request_log) == 1


def test_remote_5xx_without_extension_error_retries_and_final_failure_is_structured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=2,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                _FakeResponse({"not": "extension-error"}, status_code=500),
                _FakeResponse({"still": "not-extension-error"}, status_code=500),
            ],
        ),
    )

    with pytest.raises(ToolConnectorRemoteError) as error_info:
        call_connector_tool(
            descriptor,
            descriptor.operations[0],
            {"ticketId": "t-1"},
            _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True),
        )

    assert len(request_log) == 2
    assert error_info.value.http_status == 500
    assert error_info.value.category == "REMOTE_UNAVAILABLE"
    assert error_info.value.error_code == "REMOTE_HTTP_500"


def test_remote_transport_timeout_retries_as_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=2,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_TIMEOUT"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            [
                httpx.TimeoutException("timed out"),
                _FakeResponse(_remote_success({"ticketId": "t-1"})),
            ],
        ),
    )

    output = call_connector_tool(
        descriptor,
        descriptor.operations[0],
        {"ticketId": "t-1"},
        _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True),
    )

    assert output == {"ticketId": "t-1"}
    assert len(request_log) == 2


def test_remote_circuit_open_stops_call_without_sending_request(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        retry_policy=_retry_policy(
            mode="FIXED",
            max_attempts=1,
            initial_delay_ms=0,
            max_delay_ms=0,
            retryable_categories=["REMOTE_UNAVAILABLE"],
        ),
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(
            request_log,
            _FakeResponse(_remote_error(category="REMOTE_UNAVAILABLE", retryable=True), status_code=503),
        ),
    )
    runtime = _runtime(_registry(_entry("enterprise.acme.crm")), retry_enabled=True)

    for _ in range(2):
        with pytest.raises(ToolConnectorRemoteError) as error_info:
            call_connector_tool(descriptor, descriptor.operations[0], {"ticketId": "t-1"}, runtime)
        assert error_info.value.category == "REMOTE_UNAVAILABLE"

    assert len(request_log) == 2
    with pytest.raises(ToolConnectorRemoteError) as circuit_error:
        call_connector_tool(descriptor, descriptor.operations[0], {"ticketId": "t-1"}, runtime)

    assert circuit_error.value.category == "CIRCUIT_OPEN"
    assert circuit_error.value.error_code == "CIRCUIT_OPEN"
    assert len(request_log) == 2


def test_remote_output_schema_validation_still_runs(monkeypatch: pytest.MonkeyPatch) -> None:
    payload = _playbook_request_payload()
    tool = payload["ownerAgent"]["tools"][0]
    tool["connector"] = {
        "connectorType": "enterprise.acme.crm",
        "accountSnapshot": {"accountId": "integration-account-1", "externalSecretRef": "vault://secret-1"},
        "timeoutSeconds": 15,
        "retryPolicy": _retry_policy(),
        "config": {},
        "operationMappings": {"create_ticket": {}},
    }
    request = PlaybookToolTaskRequest.model_validate(payload)
    request_log: list[dict[str, Any]] = []

    set_default_tool_connector_registry(_registry(_entry("enterprise.acme.crm")))
    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(_remote_success({"ticketId": 100}))),
    )

    with pytest.raises(ValueError, match=r"\$\.ticketId expected type string"):
        execute_playbook_tool_task(request)
    assert len(request_log) == 1


def test_remote_connector_does_not_call_runtime_account_resolver(monkeypatch: pytest.MonkeyPatch) -> None:
    descriptor = _tool_descriptor(
        connector_type="enterprise.acme.crm",
        account_snapshot={"accountId": "integration-account-1", "externalSecretRef": "vault://secret-1"},
    )
    request_log: list[dict[str, Any]] = []

    monkeypatch.setattr(
        "agentyard_agent_runtime.http_clients.httpx.Client",
        lambda *args, **kwargs: _FakeClient(request_log, _FakeResponse(_remote_success({"ticketId": "t-1"}))),
    )

    call_connector_tool(
        descriptor,
        descriptor.operations[0],
        {"ticketId": "t-1"},
        _runtime(
            _registry(_entry("enterprise.acme.crm")),
            load_integration_account=lambda _: (_ for _ in ()).throw(AssertionError("remote must not call API")),
        ),
    )

    assert request_log[0]["url"] == "https://extensions.example.test/enterprise-tools/tools/invoke"


def _runtime(
    registry: ToolConnectorRegistry,
    *,
    load_integration_account: Any | None = None,
    retry_enabled: bool = False,
    sleep: Any | None = None,
    monotonic: Any | None = None,
) -> ConnectorRuntime:
    return ConnectorRuntime(
        load_integration_account=load_integration_account or (lambda _: {}),
        internal_auth_headers=lambda: {"Authorization": "Bearer test-internal-token"},
        tool_connector_registry=lambda: registry,
        traceparent=lambda: TRACEPARENT,
        idempotency_key=lambda _descriptor, _operation, _arguments: "tool-call-1",
        retry_enabled=retry_enabled,
        sleep=sleep or (lambda _seconds: None),
        monotonic=monotonic or (lambda: 1000.0),
    )


def _registry(*entries: ToolConnectorRegistryEntry) -> ToolConnectorRegistry:
    return ToolConnectorRegistry(entries)


def _entry(
    connector_type: str,
    *,
    base_url: str = "https://extensions.example.test/enterprise-tools",
    invoke_path: str = "/tools/invoke",
) -> ToolConnectorRegistryEntry:
    return ToolConnectorRegistryEntry(
        connector_type=connector_type,
        registration_id="enterprise-tools" if not connector_type.startswith("simple") else "core-agent-runtime",
        base_url=base_url,
        descriptor={
            "connectorType": connector_type,
            "title": connector_type,
            "endpoints": {"invoke": invoke_path},
        },
        invoke_path=invoke_path,
        in_process=connector_type in {"simple-http", "business-code-secret-http", "mcp"},
    )


def _tool_descriptor(
    *,
    connector_type: str,
    account_snapshot: dict[str, Any] | None = None,
    config: dict[str, Any] | None = None,
    operation_mapping: dict[str, Any] | None = None,
    timeout_seconds: int = 15,
    retry_policy: dict[str, Any] | None = None,
) -> ToolDescriptor:
    return ToolDescriptor.model_validate(
        {
            "resourceId": "tool-1",
            "resourceName": "Ticket Tool",
            "resourceVersionId": "rv-tool-1",
            "resourceVersion": "1.0.0",
            "operations": [
                {
                    "name": "create_ticket",
                    "description": "Create a ticket",
                    "inputSchema": '{"type":"object","properties":{"ticketId":{"type":"string"},"note":{"type":"string"}},"additionalProperties":true}',
                    "outputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"}},"additionalProperties":true}',
                }
            ],
            "connector": {
                "connectorType": connector_type,
                "accountSnapshot": account_snapshot,
                "timeoutSeconds": timeout_seconds,
                "retryPolicy": retry_policy or _retry_policy(),
                "config": config or {},
                "operationMappings": {"create_ticket": operation_mapping or {}},
            },
        }
    )


def _remote_success(output: dict[str, Any]) -> dict[str, Any]:
    return {"status": "SUCCEEDED", "output": output, "metadata": {}}


def _remote_error(
    *,
    category: str,
    retryable: bool,
    error_code: str | None = None,
) -> dict[str, Any]:
    return {
        "errorCode": error_code or category,
        "message": "remote failure",
        "category": category,
        "retryable": retryable,
        "details": {},
    }


def _retry_policy(
    *,
    mode: str = "NONE",
    max_attempts: int = 1,
    initial_delay_ms: int = 0,
    max_delay_ms: int = 0,
    backoff_multiplier: float = 1.0,
    retryable_categories: list[str] | None = None,
    retryable_error_codes: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "mode": mode,
        "maxAttempts": max_attempts,
        "initialDelayMs": initial_delay_ms,
        "maxDelayMs": max_delay_ms,
        "backoffMultiplier": backoff_multiplier,
        "retryableCategories": retryable_categories or [],
        "retryableErrorCodes": retryable_error_codes or [],
    }


def _playbook_request_payload() -> dict[str, Any]:
    return {
        "sessionId": "session-1",
        "playbookRunId": "run-1",
        "playbookId": "pb-1",
        "nodeKey": "tool-node",
        "nodeName": "Create Ticket",
        "ownerAgent": {
            "agentId": "agent-a",
            "name": "Agent A",
            "role": "support",
            "responsibility": "help the customer",
            "tools": [
                {
                    "resourceId": "tool-1",
                    "resourceName": "Ticket Tool",
                    "resourceVersionId": "rv-tool-1",
                    "resourceVersion": "1.0.0",
                    "operations": [
                        {
                            "name": "create_ticket",
                            "description": "Create a ticket",
                            "inputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"}},"additionalProperties":true}',
                            "outputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"}},"additionalProperties":true}',
                        }
                    ],
                    "connector": {
                        "connectorType": "simple-http",
                        "accountSnapshot": None,
                        "timeoutSeconds": 15,
                        "retryPolicy": _retry_policy(),
                        "config": {"baseUrl": "https://tool.example"},
                        "operationMappings": {"create_ticket": {}},
                    },
                }
            ],
        },
        "toolId": "tool-1",
        "toolOperation": "create_ticket",
        "input": {"ticketId": "t-100"},
        "config": {
            "arguments": {"ticketId": "{{input.ticketId}}"},
            "outputKey": "workflow.ticket",
        },
    }
