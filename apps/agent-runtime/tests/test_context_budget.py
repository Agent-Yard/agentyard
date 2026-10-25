import copy
import json

import httpx
import pytest

from agentyard_agent_runtime.context_budget import bounded_provider_messages, bounded_tool_result, encoded
from agentyard_agent_runtime.transcript_store import TurnExecutionContext
from runtime_fixtures import runtime_client, sqlite_transcript_store


def test_projection_preserves_tool_pairs_and_never_mutates_full_history():
    messages = [{"role": "system", "content": "system authority"}]
    for i in range(30):
        messages.extend([{"role": "assistant", "content": "", "tool_calls": [{"id": f"c{i}", "function": {"name": "read", "arguments": "{}"}}]},
                         {"role": "tool", "tool_call_id": f"c{i}", "content": "x" * 1000}])
    messages.extend([{"role": "user", "content": "current authorized context"}, {"role": "user", "content": "current human request"}])
    original = copy.deepcopy(messages)
    result = bounded_provider_messages(messages, max_chars=5000)
    assert messages == original and len(encoded(result)) < 5000
    assert result[-1]["content"] == "current human request"
    assert "omitted" in result[1]["content"]
    ids = {call["id"] for m in result for call in m.get("tool_calls", [])}
    assert {m["tool_call_id"] for m in result if m["role"] == "tool"} == ids


def test_required_context_overflow_fails_explicitly_instead_of_dropping_system_rules():
    with pytest.raises(ValueError, match="context character budget"):
        bounded_provider_messages([{"role": "system", "content": "rules" * 1000}], max_chars=2000)


def test_large_receipt_has_complete_paged_source_with_identity_and_privacy_boundary():
    store = sqlite_transcript_store()
    context = TurnExecutionContext("session", "agent", "message", "activation", "attempt")
    store.begin_execution(context)
    full = {"body": "data" * 5000, "email": "private@example.test"}
    store.begin_tool_call(context, "large", "read_document", {}, retry_safe=False)
    store.complete_tool_call(context, "large", full)
    visible = {**full, "email": "[EMAIL_1]"}
    bounded = bounded_tool_result(visible, activation_id="activation", call_id="large")
    assert bounded["truncated"] and bounded["source"]["callId"] == "large"
    pages, offset, inspected = [], 0, []
    def sanitize(value):
        inspected.append(value["email"])
        return {**value, "email": "[EMAIL_1]"}
    while offset is not None:
        page = store.tool_result_page(context, activation_id="activation", call_id="large", offset=offset, limit=1000,
                                      result_sanitizer=sanitize)
        pages.append(page["content"])
        offset = page["nextOffset"]
    assert json.loads("".join(pages)) == visible
    assert set(inspected) == {"private@example.test"}
    with pytest.raises(ValueError):
        store.tool_result_page(TurnExecutionContext("session", "intruder", "m", "a", "t"), activation_id="activation", call_id="large")
    with pytest.raises(ValueError):
        store.tool_result_page(context, activation_id="other-activation", call_id="large")


def test_paused_node_returns_423_before_claiming_any_durable_tool_receipt(monkeypatch):
    from agentyard_agent_runtime import control_plane
    monkeypatch.setenv("AGENTYARD_INTERNAL_AUTH_TOKEN", "test-internal-token")
    store = sqlite_transcript_store()
    def paused(*args, **kwargs):
        response = httpx.Response(423, request=httpx.Request("POST", "http://control.test/authorize-node"))
        raise httpx.HTTPStatusError("EXECUTION_PAUSED", request=response.request, response=response)
    monkeypatch.setattr(control_plane, "request", paused)
    def forbidden(*args, **kwargs):
        pytest.fail("paused authorization must not claim a receipt")
    monkeypatch.setattr(store, "begin_tool_call", forbidden)
    with runtime_client(store) as client:
        result = client.post("/tool-run-nodes/execute", headers={"Authorization": "Bearer test-internal-token"},
            json={"toolRunId": "run", "nodeKey": "node", "executionId": "run:node:1", "input": {}})
        assert result.status_code == 423 and result.json()["detail"] == "EXECUTION_PAUSED"
