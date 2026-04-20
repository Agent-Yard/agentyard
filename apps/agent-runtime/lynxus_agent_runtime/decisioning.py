from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Any

import httpx

from .openai_adapter import (
    assistant_tool_call_message,
    parse_openai_tool_calls,
    render_openai_tool_definitions,
    render_openai_runtime_message,
    tool_result_message,
)
from .models import AgentDecision, AgentTurnRequest, AgentTurnResult
from .prompting import build_prompt_bundle, loaded_skill_runtime_message, render_openai_messages
from .semantic import SemanticMessage, SemanticToolCall, SemanticToolResult
from .tooling import execute_tool_call, load_skills, semantic_tool_definitions

LOGGER = logging.getLogger("lynxus-agent-runtime")

AGENT_ACTIONS = (
    "REPLY",
    "NO_REPLY",
    "SWITCH_OWNER",
    "RUN_PLAYBOOK",
    "SESSION_HUMAN_HANDOFF",
)
DEFAULT_MAX_TOOL_STEPS = 4
OPENAI_COMPATIBLE_PROVIDER_TYPES = {"OPENAI", "OPENAI_COMPATIBLE"}


@dataclass(frozen=True)
class ProviderSettings:
    base_url: str
    model_id: str
    api_key: str
    temperature: float
    max_tokens: int
    organization: str
    project: str


def execute_agent_turn(request: AgentTurnRequest) -> tuple[AgentTurnResult, PromptBundle]:
    prompt_bundle = build_prompt_bundle(request)
    result = _execute_via_openai_compatible(request, prompt_bundle)
    if result is None:
        result = _fallback_result(request)
    return result, prompt_bundle


def _execute_via_openai_compatible(
    request: AgentTurnRequest, prompt_bundle: PromptBundle
) -> AgentTurnResult | None:
    settings = _resolve_provider_settings(request)
    if settings is None:
        return None

    headers = {
        "Authorization": f"Bearer {settings.api_key}",
        "Content-Type": "application/json",
    }
    if settings.organization:
        headers["OpenAI-Organization"] = settings.organization
    if settings.project:
        headers["OpenAI-Project"] = settings.project

    messages = render_openai_messages(prompt_bundle)
    tools = render_openai_tool_definitions(semantic_tool_definitions(request))
    loaded_skill_ids: set[str] = set()
    max_steps = _max_tool_steps()
    tool_call_count = 0
    skill_read_count = 0
    try:
        with httpx.Client(timeout=20.0) as client:
            for step in range(max_steps + 1):
                payload: dict[str, Any] = {
                    "model": settings.model_id,
                    "temperature": settings.temperature,
                    "messages": messages,
                    "tools": tools,
                }
                if settings.max_tokens > 0:
                    payload["max_tokens"] = settings.max_tokens
                response = client.post(
                    settings.base_url.rstrip("/") + "/chat/completions",
                    headers=headers,
                    json=payload,
                )
                response.raise_for_status()
                message = response.json()["choices"][0]["message"]
                tool_calls = message.get("tool_calls") or []
                if tool_calls:
                    content, semantic_tool_calls = parse_openai_tool_calls(message)
                    messages.append(render_openai_runtime_message(assistant_tool_call_message(content, semantic_tool_calls)))
                    for tool_call in semantic_tool_calls:
                        tool_call_count += 1
                        tool_result = _execute_model_tool_call(request, tool_call)
                        messages.append(render_openai_runtime_message(tool_result_message(SemanticToolResult(tool_call.call_id, tool_result))))
                    continue

                parsed = _extract_json_object(message.get("content"))
                if not isinstance(parsed, dict):
                    raise ValueError("model response is not a JSON object")
                if _is_skill_read_request(parsed):
                    requested_ids = _parse_skill_reads(parsed, request, loaded_skill_ids)
                    loaded_skill_ids.update(requested_ids)
                    skill_read_count += len(requested_ids)
                    messages.append(
                        render_openai_runtime_message(
                            SemanticMessage(
                                kind="assistant_turn",
                                content=json.dumps({"skillReads": requested_ids}, ensure_ascii=False),
                            )
                        )
                    )
                    messages.append(render_openai_runtime_message(loaded_skill_runtime_message(load_skills(request, requested_ids))))
                    continue
                decision_payload = parsed.get("decision")
                shared_state = parsed.get("sharedState")
                result = AgentTurnResult(
                    decision=AgentDecision.model_validate(decision_payload or {}),
                    sharedState=shared_state if isinstance(shared_state, dict) else _updated_shared_state(request),
                )
                LOGGER.info(
                    "agent turn executed via openai-compatible provider",
                    extra={
                        "sessionId": request.sessionId,
                        "assistantId": request.assistantId,
                        "ownerAgentId": request.currentOwner.agentId,
                        "action": result.decision.action,
                        "toolLoopSteps": step,
                        "toolCallCount": tool_call_count,
                        "skillReadCount": skill_read_count,
                    },
                )
                return result
        raise ValueError("model did not return a final decision within loop step budget")
    except Exception as error:  # noqa: BLE001
        LOGGER.warning(
            "openai-compatible execution failed, falling back to deterministic policy",
            extra={
                "sessionId": request.sessionId,
                "assistantId": request.assistantId,
                "ownerAgentId": request.currentOwner.agentId,
                "error": str(error),
            },
        )
        return None


def _resolve_provider_settings(request: AgentTurnRequest) -> ProviderSettings | None:
    model = request.currentOwner.model
    if model is not None and model.providerType.upper() in OPENAI_COMPATIBLE_PROVIDER_TYPES:
        api_key = (os.getenv(model.apiKeyEnvVar) or "").strip()
        if api_key and model.baseUrl.strip() and model.modelId.strip():
            return ProviderSettings(
                base_url=model.baseUrl.strip(),
                model_id=model.modelId.strip(),
                api_key=api_key,
                temperature=model.temperature,
                max_tokens=model.maxTokens,
                organization=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
                project=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
            )
    base_url = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_BASE_URL") or "").strip()
    model_id = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID") or "").strip()
    if not base_url or not model_id:
        return None
    api_key_env = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR") or "OPENAI_COMPATIBLE_API_KEY").strip()
    api_key = (os.getenv(api_key_env) or "").strip()
    if not api_key:
        return None
    return ProviderSettings(
        base_url=base_url,
        model_id=model_id,
        api_key=api_key,
        temperature=0,
        max_tokens=0,
        organization=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
        project=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
    )


def _execute_model_tool_call(request: AgentTurnRequest, tool_call: SemanticToolCall) -> dict[str, Any]:
    return execute_tool_call(request, tool_call.tool_name, tool_call.arguments)


def _is_skill_read_request(parsed: dict[str, Any]) -> bool:
    return "skillReads" in parsed and "decision" not in parsed and "sharedState" not in parsed


def _parse_skill_reads(parsed: dict[str, Any], request: AgentTurnRequest, loaded_skill_ids: set[str]) -> list[str]:
    raw_value = parsed.get("skillReads")
    if not isinstance(raw_value, list):
        raise ValueError("skillReads must be an array")
    allowed_ids = {skill.resourceVersionId for skill in request.currentOwner.skills}
    requested_ids: list[str] = []
    for item in raw_value:
        resource_version_id = str(item or "").strip()
        if not resource_version_id:
            raise ValueError("skillReads items must be non-empty strings")
        if resource_version_id not in allowed_ids:
            raise ValueError(f"unknown skillResourceVersionId={resource_version_id}")
        if resource_version_id not in loaded_skill_ids and resource_version_id not in requested_ids:
            requested_ids.append(resource_version_id)
    if not requested_ids:
        raise ValueError("skillReads must request at least one new skill")
    return requested_ids


def _max_tool_steps() -> int:
    raw_value = (os.getenv("LYNXUS_AGENT_RUNTIME_MAX_TOOL_STEPS") or "").strip()
    if not raw_value:
        return DEFAULT_MAX_TOOL_STEPS
    try:
        return max(1, min(int(raw_value), 8))
    except ValueError:
        return DEFAULT_MAX_TOOL_STEPS


def _extract_json_object(content: Any) -> Any:
    if isinstance(content, list):
        text = "".join(
            part.get("text", "")
            for part in content
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        )
    else:
        text = str(content or "")
    text = text.strip()
    if not text:
        raise ValueError("empty model response")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end < start:
            raise
        return json.loads(text[start : end + 1])


def _fallback_result(request: AgentTurnRequest) -> AgentTurnResult:
    trigger_text = str(request.trigger.payload.get("text") or "").strip()
    lowered = trigger_text.lower()
    if "playbook" in lowered and "RUN_PLAYBOOK" in request.currentOwner.allowedActions and request.currentOwner.playbookIds:
        return AgentTurnResult(
            decision=AgentDecision(
                action="RUN_PLAYBOOK",
                playbookId=request.currentOwner.playbookIds[0],
                playbookInput={"source": "fallback", "text": trigger_text},
                accompanyingReply="已开始执行流程",
            ),
            sharedState=_updated_shared_state(request),
        )
    if trigger_text.startswith("/switch ") and "SWITCH_OWNER" in request.currentOwner.allowedActions:
        target_agent_id = trigger_text.removeprefix("/switch ").strip()
        if target_agent_id in request.currentOwner.switchableOwnerAgentIds:
            return AgentTurnResult(
                decision=AgentDecision(
                    action="SWITCH_OWNER",
                    targetAgentId=target_agent_id,
                    accompanyingReply="已切换处理智能体",
                ),
                sharedState=_updated_shared_state(request),
            )
    if "人工" in trigger_text and "SESSION_HUMAN_HANDOFF" in request.currentOwner.allowedActions:
        return AgentTurnResult(
            decision=AgentDecision(
                action="SESSION_HUMAN_HANDOFF",
                accompanyingReply="已为你转接人工处理",
            ),
            sharedState=_updated_shared_state(request),
        )
    if "REPLY" in request.currentOwner.allowedActions:
        return AgentTurnResult(
            decision=AgentDecision(action="REPLY", replyContent="已收到，我来继续处理。"),
            sharedState=_updated_shared_state(request),
        )
    if "NO_REPLY" in request.currentOwner.allowedActions:
        return AgentTurnResult(
            decision=AgentDecision(action="NO_REPLY"),
            sharedState=_updated_shared_state(request),
        )
    fallback_action = "SWITCH_OWNER" if "SWITCH_OWNER" in request.currentOwner.allowedActions else AGENT_ACTIONS[0]
    decision_payload: dict[str, Any] = {"action": fallback_action}
    if fallback_action == "REPLY":
        decision_payload["replyContent"] = "已收到，我来继续处理。"
    if fallback_action == "SWITCH_OWNER" and request.currentOwner.switchableOwnerAgentIds:
        decision_payload["targetAgentId"] = request.currentOwner.switchableOwnerAgentIds[0]
    if fallback_action == "RUN_PLAYBOOK" and request.currentOwner.playbookIds:
        decision_payload["playbookId"] = request.currentOwner.playbookIds[0]
        decision_payload["playbookInput"] = {"source": "fallback"}
    return AgentTurnResult(
        decision=AgentDecision.model_validate(decision_payload),
        sharedState=_updated_shared_state(request),
    )


def _updated_shared_state(request: AgentTurnRequest) -> dict[str, Any]:
    shared_state = dict(request.sharedState)
    if request.trigger.triggerType == "USER_MESSAGE":
        shared_state["lastUserMessage"] = request.trigger.payload.get("text")
    return shared_state
