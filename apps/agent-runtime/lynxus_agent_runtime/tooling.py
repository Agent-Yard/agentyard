from __future__ import annotations

import json
import os
import re
from typing import Any

import httpx

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

_RESOURCE_TOOL_PREFIX = "resource_tool__"
_KNOWLEDGE_SEARCH_TOOL = "knowledge_search"
_KNOWLEDGE_READ_TOOL = "knowledge_read"


def openai_tool_definitions(request: AgentTurnRequest) -> list[dict[str, Any]]:
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
                    "providerType": tool.providerType,
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
    if descriptor.providerType.upper() == "HTTP":
        output = _call_http_tool(descriptor, operation, arguments)
    elif descriptor.providerType.upper() == "MCP":
        output = _call_mcp_tool(descriptor, operation, arguments)
    else:
        raise ValueError(f"unsupported tool provider: {descriptor.providerType}")
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


def tool_result_message(tool_call_id: str, result: dict[str, Any]) -> dict[str, str]:
    return {
        "role": "tool",
        "tool_call_id": tool_call_id,
        "content": json.dumps(result, ensure_ascii=False),
    }


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


def _builtin_tool_definitions(binding: KnowledgeBindingDescriptor | None) -> list[dict[str, Any]]:
    definitions = [
        _function_tool(
            "get_owner_capabilities",
            "Read the current owner agent's action whitelist and mounted skill/tool directory.",
            {"type": "object", "properties": {}, "additionalProperties": False},
        ),
        _function_tool(
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
        ),
        _function_tool(
            "list_available_playbooks",
            "List playbooks available in the current assistant release.",
            {
                "type": "object",
                "properties": {
                    "only_owner_enabled": {"type": "boolean"},
                },
                "additionalProperties": False,
            },
        ),
        _function_tool(
            "get_active_playbook",
            "Read the active playbook summary when a playbook is currently running.",
            {"type": "object", "properties": {}, "additionalProperties": False},
        ),
        _function_tool(
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
        ),
        _function_tool(
            "get_shared_state",
            "Read the current sharedState snapshot or a selected key subset.",
            {
                "type": "object",
                "properties": {
                    "keys": {"type": "array", "items": {"type": "string"}},
                },
                "additionalProperties": False,
            },
        ),
    ]
    if binding is not None:
        definitions.insert(
            1,
            _function_tool(
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
            ),
        )
        definitions.insert(
            2,
            _function_tool(
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
            ),
        )
    return definitions


def _resource_tool_definitions(request: AgentTurnRequest) -> list[dict[str, Any]]:
    definitions: list[dict[str, Any]] = []
    for tool in request.currentOwner.tools:
        for operation in tool.operations:
            definitions.append(
                _function_tool(
                    resource_tool_function_name(tool, operation),
                    operation.description
                    or f"Invoke {tool.resourceName}.{operation.name} via {tool.providerType} provider.",
                    _parse_json_schema(operation.inputSchema),
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
    with httpx.Client(timeout=10.0) as client:
        response = client.post(
            _knowledge_service_base_url() + "/internal/retrieve",
            headers=_internal_auth_headers(),
            json={
                "indexSnapshotId": active_binding.snapshotId,
                "query": query,
                "topK": top_k_raw,
                "minScore": float(min_score_raw),
                "retrievalMode": retrieval_mode,
            },
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
    with httpx.Client(timeout=10.0) as client:
        response = client.post(
            _knowledge_service_base_url() + "/internal/read-chunks",
            headers=_internal_auth_headers(),
            json={
                "indexSnapshotId": active_binding.snapshotId,
                "chunkIds": chunk_ids,
            },
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
    if descriptor.providerType.upper() == "HTTP":
        result = _call_http_tool(descriptor, operation, arguments)
    elif descriptor.providerType.upper() == "MCP":
        result = _call_mcp_tool(descriptor, operation, arguments)
    else:
        raise ValueError(f"unsupported tool provider: {descriptor.providerType}")
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


def _call_http_tool(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
) -> dict[str, Any]:
    if descriptor.http is None:
        raise ValueError(f"tool {descriptor.resourceVersionId} is missing HTTP provider config")
    method = (descriptor.http.method or "POST").upper()
    request_kwargs: dict[str, Any] = {"headers": _provider_headers(descriptor.authType)}
    if method == "GET":
        request_kwargs["params"] = arguments
    else:
        request_kwargs["json"] = arguments
    with httpx.Client(timeout=max(1, descriptor.timeoutSeconds)) as client:
        response = client.request(method, descriptor.http.endpoint, **request_kwargs)
        response.raise_for_status()
        payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError(f"tool {descriptor.resourceName}.{operation.name} must return a JSON object")
    return payload


def _call_mcp_tool(
    descriptor: ToolDescriptor,
    operation: ToolOperationDescriptor,
    arguments: dict[str, Any],
) -> dict[str, Any]:
    if descriptor.mcp is None:
        raise ValueError(f"tool {descriptor.resourceVersionId} is missing MCP provider config")
    remote_operation = descriptor.mcp.operationMappings.get(operation.name, operation.name)
    with httpx.Client(timeout=max(1, descriptor.timeoutSeconds)) as client:
        response = client.post(
            descriptor.mcp.connectionUri,
            headers=_provider_headers(descriptor.authType),
            json={
                "serverName": descriptor.mcp.serverName,
                "namespace": descriptor.mcp.namespace,
                "transport": descriptor.mcp.transport,
                "tool": remote_operation,
                "arguments": arguments,
            },
        )
        response.raise_for_status()
        payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError(f"tool {descriptor.resourceName}.{operation.name} must return a JSON object")
    return payload


def _provider_headers(auth_type: str) -> dict[str, str]:
    if (auth_type or "").strip().upper() != "SERVICE_ACCOUNT":
        return {}
    internal_token = (os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN") or "").strip()
    if not internal_token:
        return {}
    return {"Authorization": f"Bearer {internal_token}"}


def _internal_auth_headers() -> dict[str, str]:
    internal_token = (os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN") or "").strip()
    if not internal_token:
        return {}
    return {"Authorization": f"Bearer {internal_token}"}


def _knowledge_service_base_url() -> str:
    return (os.getenv("LYNXUS_KNOWLEDGE_SERVICE_BASE_URL") or "http://127.0.0.1:8091").rstrip("/")


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


def _function_tool(name: str, description: str, parameters: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        },
    }
