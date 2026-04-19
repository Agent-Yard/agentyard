from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .models import AgentTurnRequest
from .tooling import resolve_knowledge_binding, skill_catalog

DEFAULT_EVENT_WINDOW = 8
MAX_EVENT_WINDOW = 20
DEFAULT_SHARED_STATE_KEY_WINDOW = 8
DEFAULT_RUNTIME_BYTE_BUDGET = 6000


@dataclass(frozen=True)
class PromptBundle:
    instruction: str
    runtime_messages: list[dict[str, str]]
    capabilities: dict[str, Any]
    response_contract: dict[str, Any]


def build_prompt_bundle(request: AgentTurnRequest) -> PromptBundle:
    knowledge_binding = resolve_knowledge_binding(request.currentOwner)
    capabilities = {
        "allowedActions": request.currentOwner.allowedActions,
        "switchableOwnerAgentIds": request.currentOwner.switchableOwnerAgentIds,
        "playbookIds": request.currentOwner.playbookIds,
        "availableSkills": skill_catalog(request),
        "availableTools": [
            {
                "resourceId": tool.resourceId,
                "resourceVersionId": tool.resourceVersionId,
                "resourceName": tool.resourceName,
                "providerType": tool.providerType,
                "operations": [
                    {
                        "name": operation.name,
                        "description": operation.description,
                        "inputSchema": operation.inputSchema,
                        "outputSchema": operation.outputSchema,
                    }
                    for operation in tool.operations
                ],
            }
            for tool in request.currentOwner.tools
        ],
        "knowledgeBinding": None
        if knowledge_binding is None
        else {
            "knowledgeBaseId": knowledge_binding.knowledgeBaseId,
            "knowledgeBaseName": knowledge_binding.knowledgeBaseName,
            "knowledgeReleaseVersion": knowledge_binding.knowledgeReleaseVersion,
            "snapshotId": knowledge_binding.snapshotId,
        },
        "availableAgents": [
            {
                "agentId": agent.agentId,
                "name": agent.name,
                "canOwnSession": agent.canOwnSession,
            }
            for agent in request.availableAgents
        ],
        "availablePlaybooks": [
            {
                "playbookId": playbook.playbookId,
                "name": playbook.name,
                "description": playbook.description,
            }
            for playbook in request.availablePlaybooks
        ],
    }
    response_contract = {
        "skillReads": "optional array of skill resourceVersionIds when you need mounted skill details before the final decision",
        "decision": {
            "action": "REPLY | NO_REPLY | SWITCH_OWNER | RUN_PLAYBOOK | SESSION_HUMAN_HANDOFF",
            "replyContent": "required when action=REPLY",
            "targetAgentId": "required when action=SWITCH_OWNER",
            "playbookId": "required when action=RUN_PLAYBOOK",
            "playbookInput": "structured object when action=RUN_PLAYBOOK",
            "accompanyingReply": "optional only for SWITCH_OWNER/RUN_PLAYBOOK/SESSION_HUMAN_HANDOFF",
        },
        "sharedState": "full snapshot object to replace current sharedState",
    }
    instruction = "\n".join(
        [
            "You are the current session owner agent.",
            f"Owner identity: {request.currentOwner.name} ({request.currentOwner.agentId})",
            f"Role: {request.currentOwner.role}",
            f"Responsibility: {request.currentOwner.responsibility}",
            "Follow the allowedActions whitelist strictly.",
            "Tools are exposed as native function tools. Call them when you need business actions or extra session context.",
            "Skills are exposed as a directory first. If you need one or more mounted skills, return JSON only with key skillReads before the final decision.",
            "When requesting skills, return only {\"skillReads\": [...]} and do not include decision or sharedState yet.",
            "After receiving loaded skill details or tool results, continue reasoning and only finish when you can return the final decision JSON.",
            "Final output must be JSON only with keys decision and sharedState.",
            "Never invent unsupported fields. Keep sharedState as a full snapshot object.",
            "If a playbook is active, do not switch owner or start a second playbook.",
            "If you choose REPLY, put the user-visible text in decision.replyContent.",
            "If you choose NO_REPLY, do not include replyContent or accompanyingReply.",
            f"System prompt:\n{request.currentOwner.systemPrompt.strip() or '(empty)'}",
        ]
    )
    event_window = _event_window_size(request)
    runtime_budget = DEFAULT_RUNTIME_BYTE_BUDGET
    shared_state_view = _shared_state_view(request.sharedState, runtime_budget // 2, event_window)
    runtime_messages: list[dict[str, str]] = [
        {
            "role": "system",
            "content": "Session trigger:\n" + json.dumps(
                {
                    "triggerType": request.trigger.triggerType,
                    "eventId": request.trigger.eventId,
                    "payload": request.trigger.payload,
                },
                ensure_ascii=False,
            ),
        },
        {
            "role": "system",
            "content": "Visible sharedState slice (use get_shared_state tool if you need more keys):\n"
            + json.dumps(shared_state_view, ensure_ascii=False),
        },
    ]
    if request.activePlaybook is not None:
        runtime_messages.append(
            {
                "role": "system",
                "content": "Active playbook summary:\n"
                + json.dumps(request.activePlaybook.model_dump(mode="json"), ensure_ascii=False),
            }
        )
    if request.recentEvents:
        event_slice = [event.model_dump(mode="json") for event in request.recentEvents[-event_window:]]
        runtime_messages.append(
            {
                "role": "system",
                "content": "Recent session events:\n"
                + json.dumps(event_slice, ensure_ascii=False),
            }
        )
    if request.trigger.triggerType == "USER_MESSAGE":
        runtime_messages.append(
            {
                "role": "user",
                "content": str(request.trigger.payload.get("text") or ""),
            }
        )
    else:
        runtime_messages.append(
            {
                "role": "system",
                "content": "System event result:\n"
                + json.dumps(request.trigger.payload, ensure_ascii=False),
            }
        )
    return PromptBundle(
        instruction=instruction,
        runtime_messages=runtime_messages,
        capabilities=capabilities,
        response_contract=response_contract,
    )


def render_openai_messages(bundle: PromptBundle) -> list[dict[str, str]]:
    system_sections = [
        bundle.instruction,
        "Capabilities:\n" + json.dumps(bundle.capabilities, ensure_ascii=False),
        "Response contract:\n" + json.dumps(bundle.response_contract, ensure_ascii=False),
    ]
    messages = [{"role": "system", "content": "\n\n".join(system_sections)}]
    messages.extend(bundle.runtime_messages)
    return messages


def loaded_skill_runtime_message(loaded_skills: list[dict[str, str]]) -> dict[str, str]:
    return {
        "role": "system",
        "content": "Loaded skill details:\n" + json.dumps(loaded_skills, ensure_ascii=False),
    }


def _event_window_size(request: AgentTurnRequest) -> int:
    raw_value = request.currentOwner.memoryWindowSize
    if raw_value <= 0:
        return DEFAULT_EVENT_WINDOW
    return max(1, min(raw_value, MAX_EVENT_WINDOW))


def _shared_state_view(shared_state: dict[str, Any], byte_budget: int, key_window: int) -> dict[str, Any]:
    if not shared_state:
        return {"sharedState": {}, "truncated": False}
    visible: dict[str, Any] = {}
    used_bytes = 0
    key_limit = max(key_window or DEFAULT_SHARED_STATE_KEY_WINDOW, 1)
    for key in sorted(shared_state.keys()):
        candidate = {key: shared_state[key]}
        candidate_bytes = len(json.dumps(candidate, ensure_ascii=False))
        if visible and (len(visible) >= key_limit or used_bytes + candidate_bytes > byte_budget):
            break
        visible[key] = shared_state[key]
        used_bytes += candidate_bytes
    truncated = len(visible) < len(shared_state)
    return {
        "sharedState": visible,
        "truncated": truncated,
        "visibleKeys": list(visible.keys()),
        "omittedKeyCount": max(0, len(shared_state) - len(visible)),
    }
