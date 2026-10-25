import asyncio
import json
from dataclasses import replace

import httpx
import pytest

from agentyard_agent_runtime import control_plane
from agentyard_agent_runtime.models import ActivationRuntime, ActivationOutcome
from agentyard_agent_runtime.openai_compatible import OpenAiCompatibleStreamEvent
from agentyard_agent_runtime.streaming import stream_activation
from agentyard_agent_runtime.transcript_store import TurnExecutionContext
from agentyard_agent_runtime.tooling import runtime_tool_registry

from runtime_fixtures import request_payload, runtime_client, sqlite_transcript_store


@pytest.fixture(autouse=True)
def runtime_environment(monkeypatch):
    monkeypatch.setenv("TEST_OPENAI_COMPATIBLE_API_KEY", "unit-test-key")
    monkeypatch.setenv("AGENTYARD_INTERNAL_AUTH_TOKEN", "test-internal-token")


def text_events(text):
    for part in [text[:max(1, len(text)//2)], text[max(1, len(text)//2):]]:
        if part:
            yield OpenAiCompatibleStreamEvent(event_type="content_delta", delta=part)
    yield OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop")
    yield OpenAiCompatibleStreamEvent(event_type="usage", usage={"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18})


def tool_events(name, arguments, call_id="call-1"):
    yield OpenAiCompatibleStreamEvent(event_type="tool_call_delta", tool_call_index=0,
        tool_call_id=call_id, tool_name=name, arguments_delta=json.dumps(arguments, ensure_ascii=False))
    yield OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls")


def finish_events(text, status="COMPLETED", **extra):
    yield from tool_events("activation_finish", {"status": status, "summary": "Contribution checked", "reply": text, **extra}, f"finish-{status}")
    yield OpenAiCompatibleStreamEvent(event_type="usage", usage={"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18})


def model_script(monkeypatch, rounds):
    requests = []
    remaining = iter(rounds)
    def stream(_settings, payload, **_):
        requests.append(json.loads(json.dumps(payload)))
        result = next(remaining)
        if isinstance(result, Exception):
            raise result
        return iter(result)
    monkeypatch.setattr("agentyard_agent_runtime.streaming.stream_chat_completion_events", stream)
    return requests


def run(store=None, payload=None):
    store = store or sqlite_transcript_store()
    request = ActivationRuntime.model_validate(payload or request_payload())
    async def collect():
        return [frame async for frame in stream_activation(request, store)]
    frames = asyncio.run(collect())
    return frames, frames[-1].payload["outcome"], store


def ctx(payload=None):
    p = payload or request_payload()
    return TurnExecutionContext(p["sessionId"], p["participantId"], p["trigger"]["id"], p["activationId"], "read-only")


def test_plain_reply_has_real_durable_transcript_and_usage(monkeypatch):
    calls = model_script(monkeypatch, [finish_events("已经收到，我来帮你梳理。")])
    frames, outcome, store = run()
    assert outcome["success"]
    assert outcome["replyMessage"]["blocks"][0]["text"] == "已经收到，我来帮你梳理。"
    assert outcome["llmUsage"][0]["sourceType"] == "AGENT_MODEL"
    assert any(f.kind == "REPLY_BLOCK_COMPLETED" for f in frames)
    history = store.history(ctx())
    assert len(history["entries"]) == 4
    assert all(e["status"] == "COMMITTED" for e in history["entries"])
    assert all("ownerAgentId" not in f.model_dump() and "ownershipEpoch" not in f.model_dump() for f in frames)
    assert "human-1" in calls[0]["messages"][-1]["content"]


def test_skill_body_requires_live_authorization_and_uses_its_descriptor(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("read_skill", {"skillId": "skill-ver-1"}), finish_events("Skill checked")])
    authorized = request_payload()["agent"]["skills"][0] | {"skillPrompt": "CURRENT_AUTHORIZED_SKILL"}
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": authorized}})
    _, outcome, _ = run()
    assert outcome["success"]
    assert "CURRENT_AUTHORIZED_SKILL" not in json.dumps(calls[0])
    assert "退款时必须" not in json.dumps(calls[0], ensure_ascii=False)
    assert "CURRENT_AUTHORIZED_SKILL" in calls[1]["messages"][-1]["content"]


def test_stream_presentation_outage_keeps_durable_success(monkeypatch):
    model_script(monkeypatch, [finish_events("Finished despite presentation outage")])
    store = sqlite_transcript_store()
    with runtime_client(store) as client:
        def unavailable(frame):
            raise httpx.ConnectError("presentation service unavailable")
        monkeypatch.setattr("agentyard_agent_runtime.main.publish_frame", unavailable)
        response = client.post("/activations/execute", json={"activationId": "activation-1"},
            headers={"Authorization": "Bearer test-internal-token"})
        assert response.status_code == 200
        assert response.json()["success"]
        assert len(store.history(ctx())["entries"]) == 4


def test_report_schema_rejects_string_body_before_api_and_explains_field(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("task_command", {
        "taskId": "task-1", "expectedRevision": 1, "kind": "PUT_CONTEXT",
        "data": {"kind": "ARTIFACT", "title": "Report", "body": "Use a structured markdown object"}}), finish_events("Correcting the report")])
    invoked = []
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: invoked.append(a))
    _, outcome, _ = run()
    assert outcome["success"] and not invoked
    assert "body" in calls[1]["messages"][-1]["content"]
    assert "INVALID_ARGUMENTS" in calls[1]["messages"][-1]["content"]


def test_authorized_cross_session_history_uses_server_identity(monkeypatch):
    calls = model_script(monkeypatch, [finish_events("authorized source evidence"),
        tool_events("resource_read", {"reference": {"kind": "SESSION_HISTORY", "resourceId": "session-1"}}),
        finish_events("Evidence read with explicit grant")])
    _, _, store = run()
    payload = request_payload()
    payload.update(activationId="reader-activation", sessionId="reader-session", participantId="reader")
    payload["trigger"]["recipientId"] = "reader"
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: {"success": True, "result": {
        "descriptor": {"sessionId": "session-1", "participantId": "participant-agent-1"}}})
    _, outcome, _ = run(store, payload)
    assert outcome["success"]
    assert "authorized source evidence" in calls[2]["messages"][-1]["content"]
    assert "authorized source evidence" not in str(calls[1]["messages"][:-1])


def test_activation_result_replayed_without_another_model_call(monkeypatch):
    calls = model_script(monkeypatch, [finish_events("done")])
    _, outcome, store = run()
    _, replay, _ = run(store)
    assert replay == outcome
    assert len(calls) == 1
    assert len(store.history(ctx())["entries"]) == 4


def test_session_history_continues_after_runtime_restart(tmp_path, monkeypatch):
    path = str(tmp_path / "runtime.sqlite")
    store = sqlite_transcript_store(path)
    calls = model_script(monkeypatch, [finish_events("项目一正在进行。"), finish_events("继续项目一。")])
    run(store)
    store.close()
    restored = sqlite_transcript_store(path)
    payload = request_payload()
    payload["activationId"] = "activation-2"
    payload["trigger"]["id"] = "message-2"
    payload["trigger"]["body"]["text"] = "继续昨天的那个"
    _, outcome, _ = run(restored, payload)
    assert outcome["success"]
    assert any("项目一正在进行" in str(m) for m in calls[1]["messages"])
    assert len(restored.history(ctx(payload))["entries"]) == 8


def test_same_template_instances_do_not_share_context(monkeypatch):
    calls = model_script(monkeypatch, [finish_events("私有材料 A"), finish_events("另一个实例")])
    _, _, store = run()
    payload = request_payload()
    payload.update(activationId="activation-other", sessionId="session-other", participantId="participant-other")
    payload["trigger"]["recipientId"] = "participant-other"
    _, outcome, _ = run(store, payload)
    assert outcome["success"]
    assert not any("私有材料 A" in str(m.get("content")) for m in calls[1]["messages"])


def test_collaboration_tools_use_trusted_activation_and_model_call_id(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("task_create", {"title": "采购调研", "goal": "比較供应商", "sourceMessageId": "message-1"}), finish_events("已安排调研。")])
    invoked = []
    def tool(request, call_id, name, arguments):
        invoked.append((request.activationId, request.participantId, call_id, name, arguments))
        return {"success": True, "result": {"taskId": "task-1"}}
    monkeypatch.setattr(control_plane, "invoke_tool", tool)
    _, outcome, store = run()
    assert outcome["success"]
    assert invoked[0][:4] == ("activation-1", "participant-agent-1", "call-1", "task_create")
    assert invoked[0][4]["sourceMessageId"] == "message-1"
    assert "task-1" in calls[1]["messages"][-1]["content"]
    assert any(e["message"]["role"] == "tool" for e in store.history(ctx())["entries"])


def test_model_cannot_forge_principal_in_tool_arguments(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("task_create", {"title": "x", "goal": "y", "participantId": "admin"}), finish_events("需要按合法身份处理。")])
    invoked = []
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: invoked.append(a))
    _, outcome, _ = run()
    assert outcome["success"]
    assert invoked == []
    assert "INVALID_ARGUMENTS" in calls[1]["messages"][-1]["content"]


def test_current_permission_denial_prevents_external_connector_call(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"}), finish_events("当前无权执行。")])
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": False, "code": "FORBIDDEN", "message": "grant revoked"})
    external = []
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool", lambda *a, **kw: external.append(a))
    _, outcome, _ = run()
    assert outcome["success"] and external == []
    assert "FORBIDDEN" in calls[1]["messages"][-1]["content"]


def test_resource_descriptor_is_authorized_and_call_scoped_idempotency_used(monkeypatch):
    model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"}), finish_events("已创建。")])
    descriptor = request_payload()["agent"]["tools"][0]
    seen = []
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": descriptor, "idempotencyKey": "activation-1:call-1"}})
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool",
        lambda d, op, args, **kw: seen.append((d, op, args, kw)) or {"ticketId": "t-1", "status": "created"})
    _, outcome, _ = run()
    assert outcome["success"]
    assert seen[0][3]["idempotency_key"] == "activation-1:call-1"


def test_failed_external_write_is_not_blindly_replayed_after_restart(tmp_path, monkeypatch):
    path = str(tmp_path / "restart.sqlite")
    store = sqlite_transcript_store(path)
    first_calls = model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"})])
    descriptor = request_payload()["agent"]["tools"][0]
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": descriptor}})
    external = []
    def uncertain(*a, **kw):
        external.append(1)
        raise ConnectionError("connection lost after request was sent")
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool", uncertain)
    _, failed, _ = run(store)
    assert not failed["success"] and external == [1]
    store.close()
    restored = sqlite_transcript_store(path)
    calls = model_script(monkeypatch, [finish_events("调用结果不明，需要核对回执后再继续。")])
    _, recovered, _ = run(restored)
    assert recovered["success"] and external == [1]
    assert any("TOOL_OUTCOME_UNKNOWN" in str(m.get("content")) for m in calls[0]["messages"])
    assert len(first_calls) == 1


def test_completed_receipt_recovers_crash_before_tool_message_without_duplicate_side_effect(monkeypatch):
    store = sqlite_transcript_store()
    descriptor = request_payload()["agent"]["tools"][0]
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": descriptor}})
    writes = []
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool",
        lambda *a, **kw: writes.append(1) or {"ticketId": "t-1", "status": "created"})
    append = store.append_pending_entries
    crashed = False
    def append_or_crash(context, entries):
        nonlocal crashed
        if entries and entries[0].role == "tool" and not crashed:
            crashed = True
            raise RuntimeError("crash after durable side-effect receipt")
        append(context, entries)
    monkeypatch.setattr(store, "append_pending_entries", append_or_crash)
    model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"})])
    _, failed, _ = run(store)
    assert not failed["success"] and writes == [1]
    calls = model_script(monkeypatch, [finish_events("已恢复原来的处理结果。")])
    _, outcome, _ = run(store)
    assert outcome["success"] and writes == [1]
    assert any("t-1" in str(m.get("content")) for m in calls[0]["messages"])


def test_security_block_is_recorded_and_cannot_claim_human_handoff(monkeypatch):
    model_script(monkeypatch, [tool_events("security_block", {"categories": ["credential_extraction"], "reason": "requests secrets", "confidence": 1})])
    _, outcome, _ = run()
    assert outcome["success"]
    assert outcome["securityAssessment"]["action"] == "BLOCK"
    assert outcome["replyMessage"] is None
    names = runtime_tool_registry(ActivationRuntime.model_validate(request_payload()))
    assert not {"human_handoff", "switch_owner", "run_playbook", "task_contribute"} & set(names)


def test_work_memory_output_is_patch_and_does_not_overwrite_tool_update(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("session_memory", {"patch": {"waiting": "supplier-1"}}), finish_events("继续跟进。")])
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: {"success": True, "result": {"knownPreference": "email", "waiting": "supplier-1"}})
    _, outcome, _ = run()
    assert outcome["workMemory"] == {}
    assert "supplier-1" in calls[1]["messages"][-1]["content"]


def test_runtime_rejects_missing_durable_store(monkeypatch):
    request = ActivationRuntime.model_validate(request_payload())
    async def collect():
        return [frame async for frame in stream_activation(request, None)]
    with pytest.raises(RuntimeError, match="durable"):
        asyncio.run(collect())


def test_http_entry_accepts_only_activation_identity_and_uses_control_plane_context(monkeypatch):
    model_script(monkeypatch, [finish_events("ok")])
    with runtime_client() as client:
        headers = {"Authorization": "Bearer test-internal-token"}
        rejected = client.post("/activations/execute", json={"activationId": "activation-1", "participantId": "admin"}, headers=headers)
        assert rejected.status_code == 422
        response = client.post("/activations/execute", json={"activationId": "activation-1"}, headers=headers)
        assert response.status_code == 200
        assert response.json()["success"]
        assert client.post("/task-executions/execute", json={}, headers=headers).status_code == 404
        assert client.post("/agent-turns/execute-stream", json={}, headers=headers).status_code == 404


def test_model_limit_failure_preserves_durable_tool_history(monkeypatch):
    payload = request_payload()
    payload["maxToolSteps"] = 1
    model_script(monkeypatch, [tool_events("task_list", {}, "c1"), tool_events("task_list", {}, "c2")])
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: {"success": True, "result": []})
    _, outcome, store = run(payload=payload)
    assert not outcome["success"]
    assert "step budget" in outcome["failureReason"]
    assert len([e for e in store.history(ctx())["entries"] if e["message"]["role"] == "tool"]) == 2


def test_invalid_model_decision_json_is_not_delivered_as_reply(monkeypatch):
    model_script(monkeypatch, [text_events('{"action":"SWITCH_OWNER","targetAgentId":"other"}')])
    frames, outcome, _ = run()
    assert not outcome["success"]
    assert not any(f.kind == "REPLY_BLOCK_DELTA" for f in frames)


def test_large_tool_output_can_be_read_in_pages_without_repeating_external_action(monkeypatch):
    full = {"report": "evidence-" * 5000 + "TAIL_VERIFIED"}
    calls = model_script(monkeypatch, [tool_events("task_read", {"taskId": "task-1"}, "large-report"),
        tool_events("tool_result_read", {"activationId": "activation-1", "callId": "large-report", "offset": 44000, "limit": 8000}, "page"),
        finish_events("已阅读完整来源中的最后一段。")])
    lookups = []
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: lookups.append(a) or full)
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": {"sessionId": "session-1", "participantId": "participant-agent-1"}}})
    _, outcome, store = run()
    assert outcome["success"] and len(lookups) == 1
    bounded = json.loads(calls[1]["messages"][-1]["content"])
    assert bounded["truncated"] and bounded["source"] == {"activationId": "activation-1", "callId": "large-report"}
    assert "TAIL_VERIFIED" in calls[2]["messages"][-1]["content"]
    assert store.tool_result_page(ctx(), activation_id="activation-1", call_id="large-report", offset=44000)["content"].endswith('TAIL_VERIFIED"}')


def malformed_tool_events():
    yield OpenAiCompatibleStreamEvent(event_type="tool_call_delta", tool_call_index=0,
        tool_call_id="malformed", tool_name="create_ticket", arguments_delta='{"subject":"unfinished')
    yield OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="length")


@pytest.mark.parametrize("crash_after_arguments", [False, True])
def test_malformed_tool_arguments_are_durable_repairable_and_never_execute(monkeypatch, crash_after_arguments):
    store = sqlite_transcript_store()
    descriptor = request_payload()["agent"]["tools"][0]
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": descriptor}})
    writes = []
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool",
        lambda *a, **kw: writes.append(1) or {"ticketId": "t-1", "status": "created"})
    if crash_after_arguments:
        append = store.append_pending_entries
        def interrupted(context, entries):
            if entries and entries[0].role == "tool":
                raise RuntimeError("crash after recording malformed arguments")
            append(context, entries)
        monkeypatch.setattr(store, "append_pending_entries", interrupted)
        model_script(monkeypatch, [malformed_tool_events()])
        _, outcome, _ = run(store)
        assert not outcome["success"] and not writes
        monkeypatch.setattr(store, "append_pending_entries", append)
    requests = model_script(monkeypatch, ([] if crash_after_arguments else [malformed_tool_events()]) + [
        tool_events("create_ticket", {"subject": "valid correction"}, "valid"), finish_events("已处理。")])
    _, outcome, _ = run(store)
    assert outcome["success"] and writes == [1]
    assert any("INVALID_TOOL_ARGUMENTS" in str(m.get("content")) for r in requests for m in r["messages"])
    history = store.history(ctx())["entries"]
    assert any(c["function"]["arguments"] == '{"subject":"unfinished' for e in history for c in e["message"].get("tool_calls", []))
    with pytest.raises(ValueError, match="receipt is not available"):
        store.tool_result_page(ctx(), activation_id="activation-1", call_id="malformed")


def test_progress_only_stop_is_continued_until_real_work_and_explicit_finish(monkeypatch):
    calls = model_script(monkeypatch, [text_events("我现在开始修订。"),
        tool_events("task_command", {"taskId": "task-1", "expectedRevision": 1, "kind": "PUT_CONTEXT",
            "data": {"kind": "ARTIFACT", "title": "修订稿", "body": {"markdown": "实际修订"}}}),
        finish_events("修订稿已保存，请审阅。")])
    writes = []
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: writes.append(a) or {"success": True, "result": {"revision": 2}})
    frames, outcome, _ = run()
    assert outcome["success"] and outcome["completion"] == "COMPLETED" and len(writes) == 1
    assert "Execution is not complete" in calls[1]["messages"][-1]["content"]
    assert outcome["replyMessage"]["blocks"] == [{"type": "TEXT", "text": "修订稿已保存，请审阅。"}]
    assert "我现在开始" not in str([f.payload for f in frames if f.visibility == "CUSTOMER"])


def test_rich_reply_and_model_echo_produce_only_one_customer_answer(monkeypatch):
    model_script(monkeypatch, [tool_events("append_rich_text_block", {"content": "**已完成**"}),
        text_events("已完成"), tool_events("activation_finish", {"status": "COMPLETED", "summary": "saved"}, "finish")])
    frames, outcome, _ = run()
    assert len(outcome["replyMessage"]["blocks"]) == 1
    assert outcome["replyMessage"]["blocks"][0]["content"] == "**已完成**"
    assert len([f for f in frames if f.kind == "REPLY_BLOCK_COMPLETED"]) == 1


def test_explicit_result_already_sent_suppresses_automatic_reply_and_stream(monkeypatch):
    payload = request_payload(); payload["context"]["replyRecipientId"] = "human-1"
    model_script(monkeypatch, [tool_events("message_send", {"recipientId": "human-1", "kind": "RESULT", "body": {"text": "结果"}}), finish_events("再次复述结果")])
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: {"success": True, "result": {"id": "sent", "recipientId": "human-1", "kind": "RESULT"}})
    frames, outcome, _ = run(payload=payload)
    assert outcome["success"] and outcome["replyMessage"] is None
    assert not any(f.visibility == "CUSTOMER" for f in frames)


def test_wait_requires_concrete_dependency_and_stops_without_continuation(monkeypatch):
    calls = model_script(monkeypatch, [tool_events("activation_finish", {"status": "WAITING", "summary": "need date"}, "invalid-wait"),
        finish_events("请确认交付日期", status="WAITING", waitingFor="用户回答交付日期")])
    _, outcome, _ = run()
    assert outcome["success"] and outcome["completion"] == "WAITING" and not outcome["retryable"]
    assert "waitingFor" in calls[1]["messages"][-1]["content"]


def test_explicit_continuation_recovers_same_activation_without_repeating_tools(monkeypatch):
    store = sqlite_transcript_store(); writes = []
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: writes.append(a) or {"success": True, "result": {"id": "saved"}})
    model_script(monkeypatch, [tool_events("task_create", {"title": "work", "goal": "goal"}),
        finish_events("", status="CONTINUE")])
    _, first, _ = run(store)
    assert not first["success"] and first["retryable"] and len(writes) == 1
    first_usage = len(first["llmUsage"])
    calls = model_script(monkeypatch, [finish_events("已完成后续部分")])
    _, completed, _ = run(store)
    assert completed["success"] and len(writes) == 1
    assert len(completed["llmUsage"]) == first_usage + 1
    assert any("saved" in str(m) for m in calls[0]["messages"])


def test_slice_budget_resumes_after_last_round_instead_of_exhausting_forever(monkeypatch):
    payload = request_payload(); payload["maxToolSteps"] = 1
    model_script(monkeypatch, [tool_events("task_list", {}, "one"), tool_events("task_list", {}, "two")])
    monkeypatch.setattr(control_plane, "invoke_tool", lambda *a: {"success": True, "result": []})
    _, yielded, store = run(payload=payload)
    assert yielded["retryable"]
    model_script(monkeypatch, [finish_events("done")])
    _, outcome, _ = run(store, payload)
    assert outcome["success"] and len(outcome["llmUsage"]) == 3


def test_uncertain_external_write_cannot_be_retried_under_new_call_id(monkeypatch):
    store = sqlite_transcript_store(); writes = []
    descriptor = request_payload()["agent"]["tools"][0]
    monkeypatch.setattr(control_plane, "authorize_resource", lambda *a: {"success": True, "result": {"descriptor": descriptor}})
    def uncertain(*a, **kw):
        writes.append(1); raise ConnectionError("response lost after write")
    monkeypatch.setattr("agentyard_agent_runtime.tooling._call_connector_tool", uncertain)
    model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"})])
    _, failed, _ = run(store)
    assert failed["retryable"]
    calls = model_script(monkeypatch, [tool_events("create_ticket", {"subject": "support"}, "new-call"),
        finish_events("请先核对外部回执", status="BLOCKED")])
    _, outcome, _ = run(store)
    assert outcome["completion"] == "BLOCKED" and writes == [1]
    assert "TOOL_OUTCOME_UNKNOWN" in calls[1]["messages"][-1]["content"]


def test_finish_checkpoint_survives_crash_before_commit_without_another_model_call(monkeypatch):
    store = sqlite_transcript_store(); commit = store.commit_success
    monkeypatch.setattr(store, "commit_success", lambda *a: (_ for _ in ()).throw(ConnectionError("commit transport lost")))
    model_script(monkeypatch, [finish_events("唯一的结果")])
    _, failed, _ = run(store)
    assert failed["retryable"]
    monkeypatch.setattr(store, "commit_success", commit)
    calls = model_script(monkeypatch, [])
    _, outcome, _ = run(store)
    assert outcome["success"] and not calls
    assert outcome["replyMessage"]["blocks"][0]["text"] == "唯一的结果"


def test_noncompliant_model_has_bounded_failure_not_false_success(monkeypatch):
    calls = model_script(monkeypatch, [text_events("马上开始") for _ in range(3)])
    frames, outcome, _ = run()
    assert len(calls) == 3 and not outcome["success"] and not outcome["retryable"]
    assert not any(f.visibility == "CUSTOMER" for f in frames)
