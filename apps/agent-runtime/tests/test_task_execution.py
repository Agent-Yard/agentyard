from __future__ import annotations

import uuid
from unittest.mock import Mock

import pytest

from agentyard_agent_runtime import task_execution
from agentyard_agent_runtime.models import AgentTurnExecutionOutcome, AgentTurnStreamFrame
from agentyard_agent_runtime.prompting import build_system_instruction
from agentyard_agent_runtime.tooling import runtime_tool_registry, execute_tool_call


def payload():
    return {
        "execution": {"id": str(uuid.uuid4()), "instruction": "Compare the evidence", "input": {"contextEntries": ["private duplicate"], "subject": "research"}, "result": {}},
        "runtime": {"assistantId": "assistant", "releaseVersion": "7", "agent": {"agentId": "researcher", "name": "Researcher", "role": "analyst", "responsibility": "Investigate", "allowedActions": ["RUN_PLAYBOOK", "SWITCH_OWNER"]}},
        "context": {"task": {"id": "task", "revision": 4, "goal": "Assess evidence"}, "context": [{"id": "entry", "visibleToRoles": ["research", "owner"]}], "permissions": ["VIEW", "CONTRIBUTE"]},
    }


def test_task_turn_is_independent_and_uses_frozen_agent_without_session_actions():
    value = payload()
    turn = task_execution.build_task_turn(value, {})
    assert turn.sessionId == "task-execution:" + value["execution"]["id"]
    assert turn.taskExecutionId == value["execution"]["id"]
    assert turn.assistantReleaseVersion == "7"
    assert turn.currentOwner.allowedActions == ["REPLY", "SESSION_HUMAN_HANDOFF"]
    assert turn.trigger.payload["input"] == {"subject": "research"}
    assert turn.trigger.payload["permittedOutputRoles"] == ["owner", "research"]
    assert "persistent collaborative Task" in build_system_instruction(turn)


def test_derived_output_uses_intersection_and_rejects_disjoint_audiences():
    context = {"context": [{"visibleToRoles": ["owner", "research"]}, {"visibleToRoles": ["owner"]}, {"visibleToRoles": []}]}
    assert task_execution.permitted_output_roles(context) == ["owner"]
    context["context"].append({"visibleToRoles": ["external"]})
    with pytest.raises(ValueError, match="disjoint"):
        task_execution.permitted_output_roles(context)


def test_task_tools_bind_identity_to_execution_not_model_arguments(monkeypatch):
    turn = task_execution.build_task_turn(payload(), {})
    control = Mock(return_value={"revision": 5})
    monkeypatch.setattr(task_execution, "control_plane", control)
    registry = runtime_tool_registry(turn)
    assert "task_contribute" in registry and "task_read_context" in registry
    assert "read_session_tasks" not in registry
    assert "run_playbook" not in registry and "switch_owner" not in registry
    command = {"requestId": "contribution", "expectedRevision": 4, "kind": "PUT_CONTEXT", "data": {"title": "Evidence", "kind": "FACT"}}
    assert execute_tool_call(turn, registry["task_contribute"], command) == {"revision": 5}
    control.assert_called_once_with(turn.taskExecutionId, command)


def test_task_read_does_not_expose_runtime_configuration(monkeypatch):
    value = payload(); value["runtime"]["credential"] = "not-context"
    turn = task_execution.build_task_turn(value, {})
    monkeypatch.setattr(task_execution, "control_plane", lambda execution_id: value)
    result = execute_tool_call(turn, runtime_tool_registry(turn)["task_read_context"], {})
    assert set(result) == {"context", "permittedOutputRoles"}
    assert result["context"]["task"]["revision"] == 4


def test_human_resume_creates_a_distinct_turn_with_previous_result():
    value = payload(); value["execution"]["result"] = {"question": "Choose scope"}
    turn = task_execution.build_task_turn(value, {"signalId": "response-1", "text": "Scope A"})
    assert turn.turnId.endswith(":response-1")
    assert turn.trigger.payload["previousResult"] == {"question": "Choose scope"}
    assert turn.trigger.payload["resume"]["text"] == "Scope A"


def test_control_plane_requires_trusted_identity_and_token(monkeypatch):
    monkeypatch.delenv("AGENTYARD_INTERNAL_AUTH_TOKEN", raising=False)
    with pytest.raises(ValueError):
        task_execution.control_plane("../another-execution")
    with pytest.raises(ValueError, match="authentication"):
        task_execution.control_plane(str(uuid.uuid4()))


def test_revoked_execution_cannot_invoke_a_mounted_tool(monkeypatch):
    from agentyard_agent_runtime.tooling import RuntimeToolSpec, RuntimeToolKind
    from agentyard_agent_runtime.semantic import SemanticToolDefinition
    turn = task_execution.build_task_turn(payload(), {})
    handler = Mock()
    spec = RuntimeToolSpec("send_purchase_order", RuntimeToolKind.CONTEXT_TOOL,
                           SemanticToolDefinition(name="send_purchase_order", description="Place order", input_schema={}), handler)
    monkeypatch.setattr(task_execution, "control_plane", Mock(side_effect=ValueError("permission revoked")))
    with pytest.raises(ValueError, match="permission revoked"):
        execute_tool_call(turn, spec, {})
    handler.assert_not_called()


def test_concurrent_write_returns_recoverable_tool_rejection(monkeypatch):
    import httpx
    turn = task_execution.build_task_turn(payload(), {})
    request = httpx.Request("POST", "http://control-plane/commands")
    response = httpx.Response(409, request=request)
    conflict = httpx.HTTPStatusError("revision conflict", request=request, response=response)
    monkeypatch.setattr(task_execution, "control_plane", Mock(side_effect=conflict))
    result = execute_tool_call(turn, runtime_tool_registry(turn)["task_contribute"], {})
    assert result["accepted"] is False
    assert result["error"]["code"] == "REVISION_CONFLICT"
    response.status_code = 403
    with pytest.raises(httpx.HTTPStatusError):
        execute_tool_call(turn, runtime_tool_registry(turn)["task_contribute"], {})


def test_task_tool_budget_is_independent_and_bounded(monkeypatch):
    from agentyard_agent_runtime.streaming import _max_streaming_tool_steps
    monkeypatch.delenv("AGENTYARD_AGENT_RUNTIME_TASK_MAX_TOOL_STEPS", raising=False)
    monkeypatch.setenv("AGENTYARD_AGENT_RUNTIME_MAX_TOOL_STEPS", "4")
    turn = task_execution.build_task_turn(payload(), {})
    assert _max_streaming_tool_steps(turn) == 24
    assert _max_streaming_tool_steps() == 4
    monkeypatch.setenv("AGENTYARD_AGENT_RUNTIME_TASK_MAX_TOOL_STEPS", "10000")
    assert _max_streaming_tool_steps(turn) == 100
    monkeypatch.setenv("AGENTYARD_AGENT_RUNTIME_TASK_MAX_TOOL_STEPS", "invalid")
    assert _max_streaming_tool_steps(turn) == 24
