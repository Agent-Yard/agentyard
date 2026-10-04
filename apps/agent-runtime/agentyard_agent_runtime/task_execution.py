"""Task execution adapter: server-scoped context, ephemeral turn, durable Task contributions."""
from __future__ import annotations

import os
import uuid
import httpx
from typing import Any
from urllib.parse import quote

from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from .http_clients import shared_http_client_for_url
from .models import AgentConfig, AgentTurnExecutionOutcome, AgentTurnRequest, SessionTrigger
from .semantic import SemanticToolDefinition


class TaskRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    executionId: str
    resume: dict[str, Any] = Field(default_factory=dict)


def control_plane(execution_id: str, command: dict[str, Any] | None = None) -> dict[str, Any]:
    # executionId comes from the worker/turn envelope; model arguments cannot choose an actor or another execution.
    uuid.UUID(execution_id)
    base = (os.getenv("AGENTYARD_API_BASE_URL") or "http://127.0.0.1:8080/api").rstrip("/")
    token = (os.getenv("AGENTYARD_INTERNAL_AUTH_TOKEN") or "").strip()
    if not token:
        raise ValueError("internal authentication token is required")
    url = f"{base}/internal/task-executions/{execution_id}"
    headers = {"Authorization": f"Bearer {token}"}
    client = shared_http_client_for_url(url)
    response = client.get(url, headers=headers, timeout=30) if command is None else client.post(url + "/commands", json=command, headers=headers, timeout=30)
    response.raise_for_status()
    return response.json()


def permitted_output_roles(context: dict[str, Any]) -> list[str]:
    restricted = [set(entry["visibleToRoles"]) for entry in context["context"] if entry.get("visibleToRoles")]
    if not restricted:
        return []
    allowed = set.intersection(*restricted)
    if not allowed:
        raise ValueError("selected context has disjoint audiences; use separate executions for each audience")
    return sorted(allowed)


def build_task_turn(payload: dict[str, Any], resume: dict[str, Any]) -> AgentTurnRequest:
    execution = payload["execution"]
    runtime = payload["runtime"]
    context = payload["context"]
    agent = AgentConfig.model_validate(runtime["agent"]).model_copy(update={
        "allowedActions": ["REPLY", "SESSION_HUMAN_HANDOFF"], "switchableOwnerAgentIds": [], "playbookIds": [],
    })
    identity = execution["id"]
    turn = identity + ":" + str(resume.get("signalId", "initial"))
    return AgentTurnRequest(
        sessionId="task-execution:" + identity, turnId=turn, turnExecutionId=turn,
        replyMessageId=turn + ":result", assistantId=runtime["assistantId"], assistantReleaseVersion=runtime["releaseVersion"],
        currentOwner=agent, availableAgents=[agent], taskExecutionId=identity,
        effectivePrivacyMappingEnabled=agent.effectivePrivacyMappingEnabled,
        effectivePrivacyModelBinding=agent.effectivePrivacyModelBinding,
        trigger=SessionTrigger(triggerType="SYSTEM_OWNER_WAKEUP", turnId=turn, eventId=turn,
            payload={"instruction": execution["instruction"], "task": context, "input": {
                k: v for k, v in execution["input"].items() if k not in {"contextEntries", "contextEntryIds"}
            }, "previousResult": execution.get("result", {}), "resume": resume,
            "permittedOutputRoles": permitted_output_roles(context)}),
    )


async def execute_task(request: TaskRunRequest) -> AgentTurnExecutionOutcome:
    from .streaming import stream_agent_turn
    payload = await run_in_threadpool(control_plane, request.executionId)
    turn = build_task_turn(payload, request.resume)
    async for frame in stream_agent_turn(turn, transcript_store=None):
        if frame.kind == "FINAL_OUTCOME":
            return AgentTurnExecutionOutcome.model_validate(frame.payload["outcome"])
    return AgentTurnExecutionOutcome(success=False, failureReason="task runtime produced no final outcome")


def task_tool_specs(request: AgentTurnRequest):
    from .tooling import RuntimeToolKind, RuntimeToolSpec
    if not request.taskExecutionId:
        return []

    def read(current: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
        payload = control_plane(current.taskExecutionId or "")
        return {"context": payload["context"], "permittedOutputRoles": permitted_output_roles(payload["context"])}

    def contribute(current: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
        try:
            return control_plane(current.taskExecutionId or "", arguments)
        except httpx.HTTPStatusError as error:
            if error.response.status_code != 409:
                raise
            # Concurrent contributions are normal collaboration. Return a tool rejection
            # so the model can read the latest revision and reconcile, without silently
            # replaying a write against content it has never seen.
            return {"accepted": False, "error": {"code": "REVISION_CONFLICT",
                "message": "The task, entry or execution changed. Read task_read_context, reconcile the contribution and submit a new command. A closed execution cannot continue."}}

    return [
        RuntimeToolSpec("task_read_context", RuntimeToolKind.CONTEXT_TOOL, SemanticToolDefinition(
            name="task_read_context", description="Read this Task's current goal, revision, permissions and the execution's selected context snapshot.",
            input_schema={"type": "object", "properties": {}, "additionalProperties": False}), read),
        RuntimeToolSpec("task_contribute", RuntimeToolKind.CONTEXT_TOOL, SemanticToolDefinition(
            name="task_contribute", description="Persist a versioned Task contribution, change its state if permitted, or create a child Task. PUT_CONTEXT data: kind, title, body object, visibleToRoles, source with entryIds; edits also require entryId and entryRevision. SET_CONTEXT_STATE: entryId, entryRevision, state. CREATE_CHILD: title, goal, optional policy and participants [{actorId, roles}]. Include parent participants who need to manage or work on the child; they must be allowed to see all execution input. Otherwise only the creator can access it. Actor identity and execution provenance are enforced by the server.",
            input_schema={"type": "object", "properties": {
                "requestId": {"type": "string", "description": "Unique idempotency key for this exact command"},
                "expectedRevision": {"type": "integer"},
                "kind": {"type": "string", "enum": ["PUT_CONTEXT", "SET_CONTEXT_STATE", "CREATE_CHILD"]},
                "data": {"type": "object"},
            }, "required": ["requestId", "expectedRevision", "kind", "data"], "additionalProperties": False}), contribute),
    ]


def session_task_tool_specs(request: AgentTurnRequest):
    from .tooling import RuntimeToolKind, RuntimeToolSpec, _control_plane_base_url, _internal_auth_headers

    def read(current: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
        url = _control_plane_base_url() + "/internal/task-sessions/" + quote(current.sessionId, safe="")
        task_id = arguments.get("taskId")
        if task_id:
            url += "/tasks/" + quote(str(task_id), safe="") + "/context"
        params = {"agentId": current.currentOwner.agentId}
        if arguments.get("cursor"):
            params["cursor"] = str(arguments["cursor"])
        response = shared_http_client_for_url(url).get(url, headers=_internal_auth_headers(), params=params, timeout=15)
        if response.status_code == 404 and not task_id:
            return {"tasks": []}
        response.raise_for_status()
        return response.json() if task_id else {"tasks": response.json()}

    return [RuntimeToolSpec("read_session_tasks", RuntimeToolKind.CONTEXT_TOOL, SemanticToolDefinition(
        name="read_session_tasks",
        description="List persistent Tasks explicitly linked to this session, or read one Task's paginated shared context by taskId. Access is the intersection of the customer's and current Agent's memberships; session linking grants no task permissions.",
        input_schema={"type": "object", "properties": {"taskId": {"type": "string"}, "cursor": {"type": "string"}}, "additionalProperties": False},
    ), read)]
