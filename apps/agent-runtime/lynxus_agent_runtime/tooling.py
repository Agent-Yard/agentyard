from __future__ import annotations

import json
import os
import re
from typing import Any

from .json_schema import validate_json_schema_value
from .models import (
    AgentConfig,
    AgentTurnRequest,
    KnowledgeBindingDescriptor,
    PlaybookToolTaskRequest,
    PlaybookToolTaskResult,
    ToolDescriptor,
    ToolOperationDescriptor,
)
from .http_clients import shared_http_client_for_url
from .semantic import SemanticToolDefinition
from .tool_connectors import ConnectorRuntime, call_connector_tool

_RESOURCE_TOOL_PREFIX = "resource_tool__"
_KNOWLEDGE_SEARCH_TOOL = "knowledge_search"
_KNOWLEDGE_READ_TOOL = "knowledge_read"


def semantic_tool_definitions(request: AgentTurnRequest) -> list[SemanticToolDefinition]:
    return [*_builtin_tool_definitions(resolve_knowledge_binding(request.currentOwner)), *_resource_tool_definitions(request)]


def execute_tool_call(request: AgentTurnRequest, tool_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    knowledge_binding = resolve_knowledge_binding(request.currentOwner)
    if tool_name == "get_owner_capabilities":
        return {
            "ownerAgentId": request.currentOwner.agentId,
            "allowedActions": request.currentOwner.allowedActions,
            "switchableOwnerAgentIds": request.currentOwner.switchableOwnerAgentIds,
            "playbookIds": request.currentOwner.playbookIds,
            "skills": [
                {
                    "resourceVersionId": skill.resourceVersionId,
                    "skillName": skill.skillName,
                    "skillDesc": skill.skillDesc,
                }
                for skill in request.currentOwner.skills
            ],
            "knowledgeBinding": None
            if knowledge_binding is None
            else knowledge_binding.model_dump(mode="json"),
            "tools": [
                {
                    "resourceVersionId": tool.resourceVersionId,
                    "resourceName": tool.resourceName,
                    "connectorType": None if tool.connector is None else tool.connector.connectorType,
                    "operations": [operation.name for operation in tool.operations],
                }
                for tool in request.currentOwner.tools
            ],
        }
    if tool_name == "list_available_agents":
        return _list_available_agents(request, arguments)
    if tool_name == "list_available_playbooks":
        return _list_available_playbooks(request, arguments)
    if tool_name == "get_active_playbook":
        return {
            "activePlaybook": None if request.activePlaybook is None else request.activePlaybook.model_dump(mode="json"),
        }
    if tool_name == "list_recent_events":
        return _list_recent_events(request, arguments)
    if tool_name == "get_shared_state":
        return _get_shared_state(request, arguments)
    if tool_name == _KNOWLEDGE_SEARCH_TOOL:
        return _knowledge_search(knowledge_binding, arguments)
    if tool_name == _KNOWLEDGE_READ_TOOL:
        return _knowledge_read(knowledge_binding, arguments)
    if tool_name.startswith(_RESOURCE_TOOL_PREFIX):
        return _execute_resource_tool(request, tool_name, arguments)
    raise ValueError(f"unsupported tool call: {tool_name}")


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


def resource_tool_function_name(tool: ToolDescriptor, operation: ToolOperationDescriptor) -> str:
    resource_segment = _sanitize_identifier(tool.resourceVersionId)
    operation_segment = _sanitize_identifier(operation.name)
    return f"{_RESOURCE_TOOL_PREFIX}{resource_segment}__{operation_segment}"


def skill_catalog(request: AgentTurnRequest) -> list[dict[str, str]]:
    return [
        {
            "resourceId": skill.resourceId,
            "resourceVersionId": skill.resourceVersionId,
            "resourceVersion": skill.resourceVersion,
            "skillName": skill.skillName,
            "skillDesc": skill.skillDesc,
        }
        for skill in request.currentOwner.skills
    ]


def load_skills(request: AgentTurnRequest, resource_version_ids: list[str]) -> list[dict[str, str]]:
    descriptors = {skill.resourceVersionId: skill for skill in request.currentOwner.skills}
    loaded: list[dict[str, str]] = []
    for resource_version_id in resource_version_ids:
        descriptor = descriptors.get(resource_version_id)
        if descriptor is None:
            raise ValueError(f"unknown skillResourceVersionId={resource_version_id}")
        loaded.append(
            {
                "resourceId": descriptor.resourceId,
                "resourceVersionId": descriptor.resourceVersionId,
                "resourceVersion": descriptor.resourceVersion,
                "skillName": descriptor.skillName,
                "skillPrompt": descriptor.skillPrompt,
            }
        )
    return loaded


def resolve_knowledge_binding(agent: AgentConfig) -> KnowledgeBindingDescriptor | None:
    if not agent.knowledgeEnabled:
        return None
    return agent.knowledgeBinding


def _builtin_tool_definitions(binding: KnowledgeBindingDescriptor | None) -> list[SemanticToolDefinition]:
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
                    "tools": {"type": "array", "items": {"type": "object"}},
                },
                "required": ["ownerAgentId", "allowedActions", "switchableOwnerAgentIds", "playbookIds", "skills", "knowledgeBinding", "tools"],
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
    return definitions


def _resource_tool_definitions(request: AgentTurnRequest) -> list[SemanticToolDefinition]:
    definitions: list[SemanticToolDefinition] = []
    for tool in request.currentOwner.tools:
        for operation in tool.operations:
            definitions.append(
                _semantic_tool(
                    resource_tool_function_name(tool, operation),
                    operation.description
                    or f"Invoke {tool.resourceName}.{operation.name} via {None if tool.connector is None else tool.connector.connectorType} connector.",
                    _parse_json_schema(operation.inputSchema),
                    _parse_json_schema(operation.outputSchema),
                )
            )
    return definitions


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


def _execute_resource_tool(request: AgentTurnRequest, tool_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    descriptor, operation = _resolve_resource_tool(request, tool_name)
    input_schema = _parse_json_schema(operation.inputSchema)
    validate_json_schema_value(arguments, input_schema)
    result = _call_connector_tool(descriptor, operation, arguments)
    output_schema = _parse_json_schema(operation.outputSchema)
    validate_json_schema_value(result, output_schema)
    return result


def _resolve_resource_tool(
    request: AgentTurnRequest, function_name: str
) -> tuple[ToolDescriptor, ToolOperationDescriptor]:
    for descriptor in request.currentOwner.tools:
        for operation in descriptor.operations:
            if resource_tool_function_name(descriptor, operation) == function_name:
                return descriptor, operation
    raise ValueError(f"unknown resource tool function: {function_name}")


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


def _sanitize_identifier(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9_]+", "_", value or "").strip("_")
    return normalized or "anonymous"


def _semantic_tool(
    name: str,
    description: str,
    parameters: dict[str, Any],
    output_schema: dict[str, Any],
) -> SemanticToolDefinition:
    return SemanticToolDefinition(name=name, description=description, input_schema=parameters, output_schema=output_schema)
