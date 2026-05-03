from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Callable

from .json_schema import validate_json_schema_value
from .models import (
    AgentConfig,
    AgentTurnRequest,
    KnowledgeBindingDescriptor,
    PlaybookConfig,
    PlaybookToolTaskRequest,
    PlaybookToolTaskResult,
    ToolDescriptor,
    ToolOperationDescriptor,
)
from .http_clients import shared_http_client_for_url
from .semantic import SemanticToolDefinition
from .tool_connectors import ConnectorRuntime, call_connector_tool

_KNOWLEDGE_SEARCH_TOOL = "knowledge_search"
_KNOWLEDGE_READ_TOOL = "knowledge_read"
RuntimeToolHandler = Callable[[AgentTurnRequest, dict[str, Any]], dict[str, Any]]


class RuntimeToolKind(StrEnum):
    CONTEXT_TOOL = "CONTEXT_TOOL"
    STATE_TOOL = "STATE_TOOL"
    MESSAGE_BLOCK_TOOL = "MESSAGE_BLOCK_TOOL"
    LIFECYCLE_ACTION_TOOL = "LIFECYCLE_ACTION_TOOL"


@dataclass(frozen=True)
class RuntimeToolSpec:
    name: str
    kind: RuntimeToolKind
    definition: SemanticToolDefinition
    handler: RuntimeToolHandler | None = None


def runtime_tool_specs(request: AgentTurnRequest, *, include_outcome_tools: bool = True) -> list[RuntimeToolSpec]:
    specs = _context_tool_specs(request)
    if include_outcome_tools:
        specs.extend(_outcome_tool_specs(request))
    specs.extend(_resource_tool_specs(request))
    _require_unique_tool_specs(specs)
    return specs


def runtime_tool_registry(request: AgentTurnRequest, *, include_outcome_tools: bool = True) -> dict[str, RuntimeToolSpec]:
    return {
        spec.name: spec
        for spec in runtime_tool_specs(request, include_outcome_tools=include_outcome_tools)
    }


def execute_tool_call(request: AgentTurnRequest, spec: RuntimeToolSpec, arguments: dict[str, Any]) -> dict[str, Any]:
    if spec.kind != RuntimeToolKind.CONTEXT_TOOL or spec.handler is None:
        raise ValueError(f"tool {spec.name} is not executable as a context tool")
    return spec.handler(request, arguments)


def execute_playbook_tool_task(request: PlaybookToolTaskRequest) -> PlaybookToolTaskResult:
    descriptor = _resolve_tool_descriptor(request.ownerAgent, request.toolId)
    operation = _resolve_tool_operation(descriptor, request.toolOperation)
    arguments = _build_playbook_tool_arguments(request)
    input_schema = _parse_json_schema(operation.inputSchema)
    validate_json_schema_value(arguments, input_schema)
    output = _call_connector_tool(descriptor, operation, arguments, retry_enabled=True)
    output_schema = _parse_json_schema(operation.outputSchema)
    validate_json_schema_value(output, output_schema)
    route_key = _first_non_blank(
        _resolve_string_path(output, str(request.config.get("routeKeyPath") or "").strip()),
        str(output.get("routeKey") or "").strip() or None,
        str(request.config.get("routeKey") or "").strip() or None,
    )
    terminal_status = _first_non_blank(
        _resolve_string_path(output, str(request.config.get("terminalStatusPath") or "").strip()),
        str(output.get("terminalStatus") or "").strip() or None,
        str(request.config.get("terminalStatus") or "").strip() or None,
    )
    failure_reason = _first_non_blank(
        _resolve_string_path(output, str(request.config.get("failureReasonPath") or "").strip()),
        str(output.get("failureReason") or "").strip() or None,
        str(request.config.get("failureReason") or "").strip() or None,
    )
    state_patch = _build_playbook_tool_state_patch(request, output)
    return PlaybookToolTaskResult(
        statePatch=state_patch,
        routeKey=route_key,
        terminalStatus=terminal_status or None,
        failureReason=failure_reason or None,
    )


def skill_catalog(request: AgentTurnRequest) -> list[dict[str, str]]:
    return [
        {
            "skillId": skill.resourceVersionId,
            "skillName": skill.skillName,
            "skillDesc": skill.skillDesc,
        }
        for skill in request.currentOwner.skills
    ]


def owner_capability_directory(request: AgentTurnRequest) -> dict[str, Any]:
    knowledge_binding = resolve_knowledge_binding(request.currentOwner)
    return {
        "ownerAgentId": request.currentOwner.agentId,
        "allowedActions": _allowed_actions_with_security_block(request.currentOwner.allowedActions),
        "switchableOwnerAgentIds": request.currentOwner.switchableOwnerAgentIds,
        "playbookIds": request.currentOwner.playbookIds,
        "skills": skill_catalog(request),
        "knowledgeBinding": None if knowledge_binding is None else knowledge_binding.model_dump(mode="json"),
        "functions": [
            {
                "name": operation.name,
                "description": _tool_operation_description(tool, operation),
            }
            for tool in request.currentOwner.tools
            for operation in tool.operations
        ],
    }


def load_skills(request: AgentTurnRequest, skill_ids: list[str]) -> list[dict[str, str]]:
    descriptors = {skill.resourceVersionId: skill for skill in request.currentOwner.skills}
    loaded: list[dict[str, str]] = []
    for skill_id in skill_ids:
        descriptor = descriptors.get(skill_id)
        if descriptor is None:
            raise ValueError(f"unknown skillId={skill_id}")
        loaded.append(
            {
                "skillId": descriptor.resourceVersionId,
                "skillName": descriptor.skillName,
                "skillPrompt": descriptor.skillPrompt,
            }
        )
    return loaded


def _read_skill_ids(arguments: dict[str, Any]) -> list[str]:
    raw_single = arguments.get("skillId")
    raw_many = arguments.get("skillIds")
    values: list[Any]
    if raw_many is not None:
        if not isinstance(raw_many, list):
            raise ValueError("read_skill.skillIds must be an array")
        values = raw_many
    else:
        values = [raw_single]
    skill_ids: list[str] = []
    for item in values:
        skill_id = str(item or "").strip()
        if not skill_id:
            raise ValueError("read_skill requires skillId")
        if skill_id not in skill_ids:
            skill_ids.append(skill_id)
    return skill_ids


def resolve_knowledge_binding(agent: AgentConfig) -> KnowledgeBindingDescriptor | None:
    if not agent.knowledgeEnabled:
        return None
    return agent.knowledgeBinding


def _allowed_actions_with_security_block(actions: list[str]) -> list[str]:
    ordered = list(actions or [])
    if "SECURITY_BLOCK" not in ordered:
        ordered.append("SECURITY_BLOCK")
    return ordered


def _context_tool_specs(request: AgentTurnRequest) -> list[RuntimeToolSpec]:
    binding = resolve_knowledge_binding(request.currentOwner)
    definitions = [
        _semantic_tool(
            "get_owner_capabilities",
            "Read the current owner agent's action whitelist and mounted skill/tool directory.",
            {"type": "object", "properties": {}, "additionalProperties": False},
            {
                "type": "object",
                "properties": {
                    "ownerAgentId": {"type": "string"},
                    "allowedActions": {"type": "array", "items": {"type": "string"}},
                    "switchableOwnerAgentIds": {"type": "array", "items": {"type": "string"}},
                    "playbookIds": {"type": "array", "items": {"type": "string"}},
                    "skills": {"type": "array", "items": {"type": "object"}},
                    "knowledgeBinding": {"type": ["object", "null"]},
                    "functions": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["ownerAgentId", "allowedActions", "switchableOwnerAgentIds", "playbookIds", "skills", "knowledgeBinding", "functions"],
                "additionalProperties": False,
            },
        ),
        _semantic_tool(
            "list_available_agents",
            "List agents available in the current assistant release.",
            {
                "type": "object",
                "properties": {
                    "only_switchable": {"type": "boolean"},
                    "include_current_owner": {"type": "boolean"},
                },
                "additionalProperties": False,
            },
            {
                "type": "object",
                "properties": {
                    "agents": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["agents"],
                "additionalProperties": False,
            },
        ),
        _semantic_tool(
            "list_available_playbooks",
            "List playbooks available in the current assistant release.",
            {
                "type": "object",
                "properties": {
                    "only_owner_enabled": {"type": "boolean"},
                },
                "additionalProperties": False,
            },
            {
                "type": "object",
                "properties": {
                    "playbooks": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["playbooks"],
                "additionalProperties": False,
            },
        ),
        _semantic_tool(
            "get_active_playbook",
            "Read the active playbook summary when a playbook is currently running.",
            {"type": "object", "properties": {}, "additionalProperties": False},
            {
                "type": "object",
                "properties": {
                    "activePlaybook": {"type": ["object", "null"]},
                },
                "required": ["activePlaybook"],
                "additionalProperties": False,
            },
        ),
        _semantic_tool(
            "list_recent_events",
            "Read recent session events for additional runtime context.",
            {
                "type": "object",
                "properties": {
                    "limit": {"type": "integer", "minimum": 1, "maximum": 20},
                    "event_types": {"type": "array", "items": {"type": "string"}},
                },
                "additionalProperties": False,
            },
            {
                "type": "object",
                "properties": {
                    "events": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["events"],
                "additionalProperties": False,
            },
        ),
        _semantic_tool(
            "get_shared_state",
            "Read the current sharedState snapshot or a selected key subset.",
            {
                "type": "object",
                "properties": {
                    "keys": {"type": "array", "items": {"type": "string"}},
                },
                "additionalProperties": False,
            },
            {
                "type": "object",
                "properties": {
                    "sharedState": {"type": "object"},
                    "missingKeys": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["sharedState"],
                "additionalProperties": False,
            },
        ),
    ]
    if binding is not None:
        definitions.insert(
            1,
            _semantic_tool(
                _KNOWLEDGE_SEARCH_TOOL,
                "Search the bound knowledge snapshot through knowledge-service before answering.",
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "topK": {"type": "integer"},
                        "minScore": {"type": "number"},
                        "retrievalMode": {"type": "string", "enum": ["LEXICAL", "VECTOR", "HYBRID"]},
                    },
                    "required": ["query"],
                    "additionalProperties": False,
                },
                {
                    "type": "object",
                    "properties": {
                        "knowledgeBaseId": {"type": "string"},
                        "knowledgeBaseName": {"type": "string"},
                        "knowledgeReleaseId": {"type": "string"},
                        "knowledgeReleaseVersion": {"type": "string"},
                        "lowConfidence": {"type": "boolean"},
                        "hits": {"type": "array", "items": {"type": "object"}},
                    },
                    "required": [
                        "knowledgeBaseId",
                        "knowledgeBaseName",
                        "knowledgeReleaseId",
                        "knowledgeReleaseVersion",
                        "lowConfidence",
                        "hits",
                    ],
                    "additionalProperties": False,
                },
            ),
        )
        definitions.insert(
            2,
            _semantic_tool(
                _KNOWLEDGE_READ_TOOL,
                "Read full chunk content from the bound knowledge snapshot through knowledge-service.",
                {
                    "type": "object",
                    "properties": {
                        "chunkIds": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["chunkIds"],
                    "additionalProperties": False,
                },
                {
                    "type": "object",
                    "properties": {
                        "knowledgeBaseId": {"type": "string"},
                        "knowledgeBaseName": {"type": "string"},
                        "knowledgeReleaseId": {"type": "string"},
                        "knowledgeReleaseVersion": {"type": "string"},
                        "chunks": {"type": "array", "items": {"type": "object"}},
                    },
                    "required": [
                        "knowledgeBaseId",
                        "knowledgeBaseName",
                        "knowledgeReleaseId",
                        "knowledgeReleaseVersion",
                        "chunks",
                    ],
                    "additionalProperties": False,
                },
            ),
        )
    definitions.append(
        _semantic_tool(
            "read_skill",
            "Read mounted skill prompt content by skillId and continue the model round with the tool result.",
            _read_skill_input_schema(request),
            {
                "type": "object",
                "properties": {"skills": {"type": "array", "items": {"type": "object"}}},
                "required": ["skills"],
                "additionalProperties": False,
            },
        )
    )
    handlers: dict[str, RuntimeToolHandler] = {
        "get_owner_capabilities": _get_owner_capabilities,
        "list_available_agents": _list_available_agents,
        "list_available_playbooks": _list_available_playbooks,
        "get_active_playbook": _get_active_playbook,
        "list_recent_events": _list_recent_events,
        "get_shared_state": _get_shared_state,
        _KNOWLEDGE_SEARCH_TOOL: _knowledge_search_context,
        _KNOWLEDGE_READ_TOOL: _knowledge_read_context,
        "read_skill": _read_skill_context,
    }
    return [
        RuntimeToolSpec(
            name=definition.name,
            kind=RuntimeToolKind.CONTEXT_TOOL,
            definition=definition,
            handler=handlers[definition.name],
        )
        for definition in definitions
    ]


def _outcome_tool_specs(request: AgentTurnRequest) -> list[RuntimeToolSpec]:
    definitions = [
        _semantic_tool(
            "update_shared_state",
            "Merge a patch into the final sharedState snapshot for this turn.",
            {
                "type": "object",
                "properties": {"patch": {"type": "object"}},
                "required": ["patch"],
                "additionalProperties": False,
            },
            _accepted_output_schema(),
        ),
        _semantic_tool(
            "append_text_block",
            "Append a user-visible TEXT block to the final replyMessage.",
            {
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
                "additionalProperties": False,
            },
            _accepted_block_output_schema(),
        ),
        _semantic_tool(
            "append_image_block",
            "Append an IMAGE block to the final replyMessage. Non-text draft streaming is not emitted in this phase.",
            {
                "type": "object",
                "properties": {
                    "url": {"type": "string"},
                    "mimeType": {"type": "string"},
                    "width": {"type": "integer"},
                    "height": {"type": "integer"},
                    "alt": {"type": "string"},
                },
                "required": ["url"],
                "additionalProperties": False,
            },
            _accepted_block_output_schema(),
        ),
        _semantic_tool(
            "append_rich_text_block",
            "Append a RICH_TEXT Markdown block to the final replyMessage.",
            {
                "type": "object",
                "properties": {
                    "format": {"type": "string", "enum": ["MARKDOWN"]},
                    "content": {"type": "string"},
                },
                "required": ["content"],
                "additionalProperties": False,
            },
            _accepted_block_output_schema(),
        ),
        _semantic_tool(
            "append_card_block",
            "Append a CARD block to the final replyMessage.",
            {
                "type": "object",
                "properties": {
                    "cardType": {"type": "string"},
                    "version": {"type": "string"},
                    "data": {"type": "object"},
                    "actions": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["cardType", "version"],
                "additionalProperties": False,
            },
            _accepted_block_output_schema(),
        ),
    ]
    definitions.extend(_lifecycle_action_tool_definitions(request))
    kinds = {
        "update_shared_state": RuntimeToolKind.STATE_TOOL,
        "append_text_block": RuntimeToolKind.MESSAGE_BLOCK_TOOL,
        "append_image_block": RuntimeToolKind.MESSAGE_BLOCK_TOOL,
        "append_rich_text_block": RuntimeToolKind.MESSAGE_BLOCK_TOOL,
        "append_card_block": RuntimeToolKind.MESSAGE_BLOCK_TOOL,
        "switch_owner": RuntimeToolKind.LIFECYCLE_ACTION_TOOL,
        "run_playbook": RuntimeToolKind.LIFECYCLE_ACTION_TOOL,
        "human_handoff": RuntimeToolKind.LIFECYCLE_ACTION_TOOL,
        "security_block": RuntimeToolKind.LIFECYCLE_ACTION_TOOL,
    }
    return [
        RuntimeToolSpec(
            name=definition.name,
            kind=kinds[definition.name],
            definition=definition,
        )
        for definition in definitions
    ]


def _read_skill_input_schema(request: AgentTurnRequest) -> dict[str, Any]:
    skill_ids = [skill.resourceVersionId for skill in request.currentOwner.skills]
    description = _mounted_skill_id_description(request)
    return {
        "type": "object",
        "properties": {
            "skillId": _string_id_schema(skill_ids, description),
            "skillIds": {
                "type": "array",
                "description": description,
                "items": _string_id_schema(skill_ids, description),
            },
        },
        "additionalProperties": False,
    }


def _lifecycle_action_tool_definitions(request: AgentTurnRequest) -> list[SemanticToolDefinition]:
    definitions: list[SemanticToolDefinition] = []
    allowed_actions = set(request.currentOwner.allowedActions)
    if "SWITCH_OWNER" in allowed_actions and request.currentOwner.switchableOwnerAgentIds:
        definitions.append(
            _semantic_tool(
                "switch_owner",
                "Request a session owner switch.",
                {
                    "type": "object",
                    "properties": {
                        "targetAgentId": _string_id_schema(
                            request.currentOwner.switchableOwnerAgentIds,
                            _switchable_owner_id_description(request),
                        )
                    },
                    "required": ["targetAgentId"],
                    "additionalProperties": False,
                },
                _accepted_output_schema(),
            )
        )
    if "RUN_PLAYBOOK" in allowed_actions and request.currentOwner.playbookIds:
        definitions.append(
            _semantic_tool(
                "run_playbook",
                "Request a playbook run.",
                {
                    "type": "object",
                    "properties": {
                        "playbookId": _string_id_schema(
                            request.currentOwner.playbookIds,
                            _enabled_playbook_id_description(request),
                        ),
                        "playbookInput": {"type": "object"},
                    },
                    "required": ["playbookId", "playbookInput"],
                    "additionalProperties": False,
                },
                _accepted_output_schema(),
            )
        )
    if "SESSION_HUMAN_HANDOFF" in allowed_actions:
        definitions.append(
            _semantic_tool(
                "human_handoff",
                "Request session human handoff.",
                {
                    "type": "object",
                    "properties": {"reason": {"type": "string"}},
                    "additionalProperties": False,
                },
                _accepted_output_schema(),
            )
        )
    definitions.append(
        _semantic_tool(
            "security_block",
            "Block a system-harmful current user message. This action has priority over other lifecycle actions.",
            {
                "type": "object",
                "properties": {
                    "categories": {"type": "array", "items": {"type": "string"}},
                    "reason": {"type": "string"},
                    "confidence": {"type": "number"},
                },
                "required": ["categories", "reason", "confidence"],
                "additionalProperties": False,
            },
            _accepted_output_schema(),
        )
    )
    return definitions


def _string_id_schema(values: list[str], description: str) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": "string", "description": description}
    if values:
        schema["enum"] = values
    return schema


def _mounted_skill_id_description(request: AgentTurnRequest) -> str:
    if not request.currentOwner.skills:
        return "No mounted skills are available."
    return "Mounted skills: " + "; ".join(
        _target_description(skill.resourceVersionId, skill.skillName, skill.skillDesc)
        for skill in request.currentOwner.skills
    )


def _switchable_owner_id_description(request: AgentTurnRequest) -> str:
    agents_by_id = {agent.agentId: agent for agent in request.availableAgents}
    return "Allowed target owners: " + "; ".join(
        _agent_target_description(agent_id, agents_by_id.get(agent_id))
        for agent_id in request.currentOwner.switchableOwnerAgentIds
    )


def _enabled_playbook_id_description(request: AgentTurnRequest) -> str:
    playbooks_by_id = {playbook.playbookId: playbook for playbook in request.availablePlaybooks}
    return "Allowed playbooks: " + "; ".join(
        _playbook_target_description(playbook_id, playbooks_by_id.get(playbook_id))
        for playbook_id in request.currentOwner.playbookIds
    )


def _agent_target_description(agent_id: str, agent: AgentConfig | None) -> str:
    if agent is None:
        return agent_id
    return _target_description(agent_id, f"{agent.name}, {agent.role}", agent.responsibility)


def _playbook_target_description(playbook_id: str, playbook: PlaybookConfig | None) -> str:
    if playbook is None:
        return playbook_id
    return _target_description(playbook_id, playbook.name, playbook.description)


def _target_description(identifier: str, label: str, description: str) -> str:
    value = f"{identifier} = {label}"
    cleaned_description = description.strip()
    return f"{value} - {cleaned_description}" if cleaned_description else value


def _accepted_output_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {"accepted": {"type": "boolean"}},
        "required": ["accepted"],
        "additionalProperties": True,
    }


def _accepted_block_output_schema() -> dict[str, Any]:
    schema = _accepted_output_schema()
    schema["properties"]["blockId"] = {"type": "string"}
    return schema


def _resource_tool_specs(request: AgentTurnRequest) -> list[RuntimeToolSpec]:
    specs: list[RuntimeToolSpec] = []
    for tool in request.currentOwner.tools:
        for operation in tool.operations:
            definition = _semantic_tool(
                operation.name,
                _tool_operation_description(tool, operation),
                _parse_json_schema(operation.inputSchema),
                _parse_json_schema(operation.outputSchema),
            )
            specs.append(
                RuntimeToolSpec(
                    name=definition.name,
                    kind=RuntimeToolKind.CONTEXT_TOOL,
                    definition=definition,
                    handler=_resource_tool_handler(tool, operation),
                )
            )
    return specs


def _tool_operation_description(tool: ToolDescriptor, operation: ToolOperationDescriptor) -> str:
    return operation.description or f"Invoke {tool.resourceName}.{operation.name}."


def _require_unique_tool_specs(specs: list[RuntimeToolSpec]) -> None:
    seen: set[str] = set()
    for spec in specs:
        if spec.name in seen:
            raise ValueError(f"duplicate runtime tool name: {spec.name}")
        seen.add(spec.name)


def _get_owner_capabilities(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    return owner_capability_directory(request)


def _get_active_playbook(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        "activePlaybook": None if request.activePlaybook is None else request.activePlaybook.model_dump(mode="json"),
    }


def _read_skill_context(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    return {"skills": load_skills(request, _read_skill_ids(arguments))}


def _knowledge_search_context(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    return _knowledge_search(resolve_knowledge_binding(request.currentOwner), arguments)


def _knowledge_read_context(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    return _knowledge_read(resolve_knowledge_binding(request.currentOwner), arguments)


def _resource_tool_handler(descriptor: ToolDescriptor, operation: ToolOperationDescriptor) -> RuntimeToolHandler:
    def handler(_request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
        return _call_resource_tool(descriptor, operation, arguments)

    return handler


def _list_available_agents(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    only_switchable = bool(arguments.get("only_switchable", False))
    include_current_owner = bool(arguments.get("include_current_owner", False))
    switchable = set(request.currentOwner.switchableOwnerAgentIds)
    agents = []
    for agent in request.availableAgents:
        if not include_current_owner and agent.agentId == request.currentOwner.agentId:
            continue
        if only_switchable and agent.agentId not in switchable:
            continue
        agents.append(
            {
                "agentId": agent.agentId,
                "name": agent.name,
                "role": agent.role,
                "responsibility": agent.responsibility,
                "canOwnSession": agent.canOwnSession,
                "allowedActions": agent.allowedActions,
            }
        )
    return {"agents": agents}


def _list_available_playbooks(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    only_owner_enabled = bool(arguments.get("only_owner_enabled", True))
    enabled = set(request.currentOwner.playbookIds)
    playbooks = []
    for playbook in request.availablePlaybooks:
        if only_owner_enabled and playbook.playbookId not in enabled:
            continue
        playbooks.append(
            {
                "playbookId": playbook.playbookId,
                "name": playbook.name,
                "description": playbook.description,
                "inputSchema": playbook.inputSchema,
                "resultSchema": playbook.resultSchema,
            }
        )
    return {"playbooks": playbooks}


def _list_recent_events(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    raw_limit = arguments.get("limit", 8)
    try:
        limit = max(1, min(int(raw_limit), 20))
    except (TypeError, ValueError):
        limit = 8
    event_types = {str(item) for item in (arguments.get("event_types") or []) if str(item).strip()}
    events = request.recentEvents
    if event_types:
        events = [event for event in events if event.eventType in event_types]
    return {"events": [event.model_dump(mode="json") for event in events[-limit:]]}


def _get_shared_state(request: AgentTurnRequest, arguments: dict[str, Any]) -> dict[str, Any]:
    keys = [str(item) for item in (arguments.get("keys") or []) if str(item).strip()]
    if not keys:
        return {"sharedState": request.sharedState}
    return {
        "sharedState": {key: request.sharedState.get(key) for key in keys},
        "missingKeys": [key for key in keys if key not in request.sharedState],
    }


def _knowledge_search(binding: KnowledgeBindingDescriptor | None, arguments: dict[str, Any]) -> dict[str, Any]:
    active_binding = _require_knowledge_binding(binding)
    query = str(arguments.get("query") or "").strip()
    if not query:
        raise ValueError("knowledge_search.query is required")
    top_k_raw = arguments.get("topK", active_binding.defaultTopK)
    min_score_raw = arguments.get("minScore", active_binding.minScore)
    retrieval_mode = str(arguments.get("retrievalMode") or active_binding.retrievalMode).strip().upper()
    if not isinstance(top_k_raw, int) or isinstance(top_k_raw, bool):
        raise ValueError("knowledge_search.topK must be an integer")
    if not isinstance(min_score_raw, (int, float)) or isinstance(min_score_raw, bool):
        raise ValueError("knowledge_search.minScore must be a number")
    if retrieval_mode not in {"LEXICAL", "VECTOR", "HYBRID"}:
        raise ValueError("knowledge_search.retrievalMode must be LEXICAL, VECTOR, or HYBRID")
    endpoint = _knowledge_service_base_url() + "/internal/retrieve"
    client = shared_http_client_for_url(endpoint)
    response = client.post(
        endpoint,
        headers=_internal_auth_headers(),
        json={
            "indexSnapshotId": active_binding.snapshotId,
            "query": query,
            "topK": top_k_raw,
            "minScore": float(min_score_raw),
            "retrievalMode": retrieval_mode,
        },
        timeout=10.0,
    )
    response.raise_for_status()
    payload = response.json()
    hits = payload.get("hits", [])
    return {
        "knowledgeBaseId": active_binding.knowledgeBaseId,
        "knowledgeBaseName": active_binding.knowledgeBaseName,
        "knowledgeReleaseId": active_binding.knowledgeReleaseId,
        "knowledgeReleaseVersion": active_binding.knowledgeReleaseVersion,
        "lowConfidence": bool(payload.get("lowConfidence", False)),
        "hits": hits if isinstance(hits, list) else [],
    }


def _knowledge_read(binding: KnowledgeBindingDescriptor | None, arguments: dict[str, Any]) -> dict[str, Any]:
    active_binding = _require_knowledge_binding(binding)
    raw_chunk_ids = arguments.get("chunkIds")
    if not isinstance(raw_chunk_ids, list):
        raise ValueError("knowledge_read.chunkIds must be a list")
    chunk_ids: list[str] = []
    for item in raw_chunk_ids:
        chunk_id = str(item or "").strip()
        if not chunk_id:
            raise ValueError("knowledge_read.chunkIds items must be non-empty strings")
        if chunk_id not in chunk_ids:
            chunk_ids.append(chunk_id)
    if not chunk_ids:
        raise ValueError("knowledge_read.chunkIds must not be empty")
    endpoint = _knowledge_service_base_url() + "/internal/read-chunks"
    client = shared_http_client_for_url(endpoint)
    response = client.post(
        endpoint,
        headers=_internal_auth_headers(),
        json={
            "indexSnapshotId": active_binding.snapshotId,
            "chunkIds": chunk_ids,
        },
        timeout=10.0,
    )
    response.raise_for_status()
    payload = response.json()
    chunks = payload.get("chunks", [])
    return {
        "knowledgeBaseId": active_binding.knowledgeBaseId,
        "knowledgeBaseName": active_binding.knowledgeBaseName,
        "knowledgeReleaseId": active_binding.knowledgeReleaseId,
        "knowledgeReleaseVersion": active_binding.knowledgeReleaseVersion,
        "chunks": chunks if isinstance(chunks, list) else [],
    }


def _call_resource_tool(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
) -> dict[str, Any]:
    input_schema = _parse_json_schema(operation.inputSchema)
    validate_json_schema_value(arguments, input_schema)
    result = _call_connector_tool(descriptor, operation, arguments)
    output_schema = _parse_json_schema(operation.outputSchema)
    validate_json_schema_value(result, output_schema)
    return result


def _resolve_tool_descriptor(agent: AgentConfig, tool_id: str) -> ToolDescriptor:
    for descriptor in agent.tools:
        if descriptor.resourceId == tool_id or descriptor.resourceVersionId == tool_id:
            return descriptor
    raise ValueError(f"unknown toolId={tool_id}")


def _resolve_tool_operation(descriptor: ToolDescriptor, operation_name: str) -> ToolOperationDescriptor:
    for operation in descriptor.operations:
        if operation.name == operation_name:
            return operation
    raise ValueError(f"unknown tool operation={operation_name} for tool={descriptor.resourceVersionId}")


def _build_playbook_tool_arguments(request: PlaybookToolTaskRequest) -> dict[str, Any]:
    template = request.config.get("arguments")
    context = {
        "input": request.input,
        "config": request.config,
        "node": {
            "nodeKey": request.nodeKey,
            "nodeName": request.nodeName,
        },
        "owner": request.ownerAgent.model_dump(mode="json"),
    }
    if template is None:
        return dict(request.input)
    rendered = _render_template_value(template, context)
    if not isinstance(rendered, dict):
        raise ValueError("playbook tool config.arguments must render to an object")
    return rendered


def _build_playbook_tool_state_patch(
    request: PlaybookToolTaskRequest, output: dict[str, Any]
) -> dict[str, Any]:
    explicit_patch = request.config.get("statePatch")
    context = {
        "input": request.input,
        "output": output,
        "config": request.config,
    }
    if explicit_patch is not None:
        rendered = _render_template_value(explicit_patch, context)
        if not isinstance(rendered, dict):
            raise ValueError("playbook tool config.statePatch must render to an object")
        return rendered
    state_patch_path = str(request.config.get("statePatchPath") or "").strip()
    if state_patch_path:
        resolved = _resolve_value_path(output, state_patch_path)
        if isinstance(resolved, dict):
            return resolved
        raise ValueError("playbook tool statePatchPath must point to an object")
    explicit_output_key = str(request.config.get("outputKey") or "").strip()
    if explicit_output_key:
        return {explicit_output_key: output}
    nested_state_patch = output.get("statePatch")
    if isinstance(nested_state_patch, dict):
        return nested_state_patch
    return {"playbook.lastToolResult": output}


def _render_template_value(value: Any, context: dict[str, Any]) -> Any:
    if isinstance(value, dict):
        return {str(key): _render_template_value(item, context) for key, item in value.items()}
    if isinstance(value, list):
        return [_render_template_value(item, context) for item in value]
    if isinstance(value, str):
        whole_match = re.fullmatch(r"\{\{\s*([^{}]+?)\s*\}\}|\$\{\s*([^{}]+?)\s*\}", value)
        if whole_match:
            return _resolve_value_path(context, whole_match.group(1) or whole_match.group(2))

        def replace(match: re.Match[str]) -> str:
            resolved = _resolve_value_path(context, match.group(1) or match.group(2))
            if resolved is None:
                return ""
            if isinstance(resolved, (dict, list)):
                return json.dumps(resolved, ensure_ascii=False)
            return str(resolved)

        return re.sub(r"\{\{\s*([^{}]+?)\s*\}\}|\$\{\s*([^{}]+?)\s*\}", replace, value)
    return value


def _resolve_value_path(source: Any, path: str) -> Any:
    current = source
    for segment in [item for item in path.split(".") if item]:
        if isinstance(current, dict):
            current = current.get(segment)
            continue
        return None
    return current


def _resolve_string_path(source: Any, path: str) -> str | None:
    if not path:
        return None
    resolved = _resolve_value_path(source, path)
    if resolved is None:
        return None
    if isinstance(resolved, str):
        return resolved.strip() or None
    return str(resolved)


def _first_non_blank(*values: str | None) -> str | None:
    for value in values:
        if value is not None and value.strip():
            return value.strip()
    return None


def _call_connector_tool(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
    *,
    retry_enabled: bool = False,
) -> dict[str, Any]:
    return call_connector_tool(
        descriptor,
        operation,
        arguments,
        ConnectorRuntime(
            load_integration_account=_load_runtime_integration_account,
            internal_auth_headers=_internal_auth_headers,
            retry_enabled=retry_enabled,
        ),
    )


def _load_runtime_integration_account(account_id: str) -> dict[str, Any]:
    endpoint = _control_plane_base_url() + f"/internal/integration/accounts/{account_id}/credential"
    client = shared_http_client_for_url(endpoint)
    response = client.get(endpoint, headers=_internal_auth_headers(), timeout=10.0)
    response.raise_for_status()
    payload = response.json()
    data = payload.get("data") if isinstance(payload, dict) and "data" in payload else payload
    if not isinstance(data, dict):
        raise ValueError(f"integration account credential response must be an object: {account_id}")
    return data


def _internal_auth_headers() -> dict[str, str]:
    internal_token = (os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN") or "").strip()
    if not internal_token:
        return {}
    return {"Authorization": f"Bearer {internal_token}"}


def _knowledge_service_base_url() -> str:
    return (os.getenv("LYNXUS_KNOWLEDGE_SERVICE_BASE_URL") or "http://127.0.0.1:8091").rstrip("/")


def _control_plane_base_url() -> str:
    return (os.getenv("LYNXUS_API_BASE_URL") or "http://127.0.0.1:8080/api").rstrip("/")


def _require_knowledge_binding(binding: KnowledgeBindingDescriptor | None) -> KnowledgeBindingDescriptor:
    if binding is None:
        raise ValueError("knowledge binding is not configured for current owner")
    if not binding.snapshotId.strip():
        raise ValueError("knowledge binding snapshotId is required")
    return binding


def _parse_json_schema(raw_schema: str) -> dict[str, Any]:
    text = (raw_schema or "").strip()
    if not text:
        return {"type": "object"}
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("tool schema must decode to a JSON object")
    return parsed


def _semantic_tool(
    name: str,
    description: str,
    parameters: dict[str, Any],
    output_schema: dict[str, Any],
) -> SemanticToolDefinition:
    return SemanticToolDefinition(name=name, description=description, input_schema=parameters, output_schema=output_schema)
