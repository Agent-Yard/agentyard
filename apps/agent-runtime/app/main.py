from __future__ import annotations

import json
import logging
import os
import time
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Dict, List, Optional, TypedDict
from urllib.parse import urlparse

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator

try:
    from langgraph.graph import END, StateGraph
except Exception as exc:  # pragma: no cover
    raise RuntimeError("langgraph is required for agent-runtime") from exc


class KnowledgeBaseDocument(BaseModel):
    id: str
    title: str
    content: str
    sourceUri: str = ""


class KnowledgeBaseConfig(BaseModel):
    defaultTopK: int
    documents: List[KnowledgeBaseDocument] = Field(default_factory=list)


class ToolOperationConfig(BaseModel):
    name: str
    description: str = ""
    inputSchema: str = ""
    outputSchema: str = ""


class HttpToolProviderConfig(BaseModel):
    endpoint: str
    method: str


class McpToolProviderConfig(BaseModel):
    serverName: str
    transport: str
    connectionUri: str
    namespace: str
    heartbeatSeconds: int
    operationMappings: Dict[str, str] = Field(default_factory=dict)


class ToolConfig(BaseModel):
    operations: List[ToolOperationConfig] = Field(default_factory=list)
    providerType: str
    authType: str
    timeoutSeconds: int
    retryPolicy: str
    http: Optional[HttpToolProviderConfig] = None
    mcp: Optional[McpToolProviderConfig] = None


class LlmModelConfig(BaseModel):
    providerType: str
    modelId: str
    baseUrl: str
    apiKeyEnvVar: str
    organization: str
    project: str
    region: str
    temperature: float
    maxTokens: int


class PromptTemplateConfig(BaseModel):
    templateType: str
    systemPrompt: str
    userPromptTemplate: str
    responseFormat: str


class ResourceConfigurationSnapshot(BaseModel):
    type: str
    knowledgeBase: Optional[KnowledgeBaseConfig] = None
    tool: Optional[ToolConfig] = None
    llmModel: Optional[LlmModelConfig] = None
    promptTemplate: Optional[PromptTemplateConfig] = None


class ResourceVersionSnapshot(BaseModel):
    resourceId: str
    resourceName: str
    resourceType: str
    resourceVersionId: str
    resourceVersion: str
    boundAgents: List[str]
    configuration: ResourceConfigurationSnapshot


class AssistantPolicySnapshot(BaseModel):
    providerResourceId: Optional[str] = None
    providerResourceVersionId: Optional[str] = None
    promptTemplateResourceId: Optional[str] = None
    promptTemplateResourceVersionId: Optional[str] = None
    ragEnabled: bool
    knowledgeBaseResourceId: Optional[str] = None
    knowledgeBaseResourceVersionId: Optional[str] = None
    memoryEnabled: bool
    memoryWindowSize: int


class AgentExecutionPolicySnapshot(BaseModel):
    inheritAssistantDefaults: bool
    modelResourceId: Optional[str] = None
    modelResourceVersionId: Optional[str] = None
    promptTemplateResourceId: Optional[str] = None
    promptTemplateResourceVersionId: Optional[str] = None
    inlinePrompt: str = ""
    ragEnabled: bool
    knowledgeBaseResourceId: Optional[str] = None
    knowledgeBaseResourceVersionId: Optional[str] = None
    memoryWindowSize: int
    toolResourceIds: List[str]
    toolResourceVersionIds: List[str]


class AgentSnapshot(BaseModel):
    agentId: str
    name: str
    role: str
    instructions: str
    executionPolicy: AgentExecutionPolicySnapshot


class HumanNodeConfig(BaseModel):
    title: str
    instruction: str
    expectedAction: str
    resumeRouteKey: str


class GraphNodeSnapshot(BaseModel):
    nodeKey: str
    nodeName: str
    nodeType: str
    description: str
    agentId: Optional[str] = None
    humanNode: Optional[HumanNodeConfig] = None


class GraphEdgeSnapshot(BaseModel):
    edgeKey: str
    sourceNodeKey: str
    targetNodeKey: str
    routeKey: str
    label: str
    defaultEdge: bool


class GraphSnapshot(BaseModel):
    executionMode: str
    nodes: List[GraphNodeSnapshot]
    edges: List[GraphEdgeSnapshot]


class AssistantRunSnapshot(BaseModel):
    assistantId: str
    assistantName: str
    assistantReleaseVersion: str
    assistantPolicy: AssistantPolicySnapshot
    agents: List[AgentSnapshot]
    resources: List[ResourceVersionSnapshot]
    graph: GraphSnapshot


class SessionMessageSnapshot(BaseModel):
    role: str
    senderName: str
    content: str
    createdAt: str

    @field_validator("createdAt", mode="before")
    @classmethod
    def normalize_created_at(cls, value: Any) -> str:
        if isinstance(value, str):
            return value
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(value, tz=timezone.utc).isoformat().replace("+00:00", "Z")
        if isinstance(value, datetime):
            return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        raise TypeError("createdAt must be a string, timestamp, or datetime")


class SessionContext(BaseModel):
    sessionId: str
    requester: str
    latestMessage: str
    history: List[SessionMessageSnapshot]


class WorkflowStartRequest(BaseModel):
    taskId: str
    workflowInstanceId: str
    scenarioId: str
    question: str
    operatorId: str
    sessionContext: SessionContext
    assistant: AssistantRunSnapshot


class HumanAction(BaseModel):
    action: str
    comment: str
    operatorId: str
    attributes: Dict[str, str] = Field(default_factory=dict)


class ExecutionCheckpoint(BaseModel):
    checkpointId: str
    currentNodeKey: Optional[str] = None
    waitingNodeKey: Optional[str] = None
    statePayload: str
    resumeCount: int


class WorkflowResumeRequest(BaseModel):
    taskId: str
    workflowInstanceId: str
    scenarioId: str
    action: HumanAction
    sessionContext: SessionContext
    assistant: AssistantRunSnapshot
    checkpoint: ExecutionCheckpoint


class ToolInvocationSnapshot(BaseModel):
    id: str
    providerType: str
    resourceId: str
    resourceName: str
    operation: str
    status: str
    detail: str
    createdAt: str


class HumanTaskSnapshot(BaseModel):
    nodeKey: str
    title: str
    instruction: str
    expectedAction: str


class ToolOutcomeSummary(BaseModel):
    toolResourceId: str
    toolResourceName: str
    operation: str
    providerType: str
    status: str
    externalReference: str
    recommendedAction: str
    detail: str


class ToolRequest(BaseModel):
    toolResourceVersionId: str
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class HumanRequest(BaseModel):
    title: str = ""
    instruction: str = ""
    expectedAction: str = ""


class AgentStructuredResponse(BaseModel):
    message: str = ""
    routeDecision: Optional[str] = None
    toolRequests: List[ToolRequest] = Field(default_factory=list)
    finish: bool = False
    humanRequest: Optional[HumanRequest] = None


class NodeSnapshot(BaseModel):
    nodeKey: str
    nodeName: str
    status: str
    detail: str
    updatedAt: str


class WorkflowResult(BaseModel):
    workflowInstanceId: str
    status: str
    summary: str
    finalReply: Optional[str] = None
    currentNodeKey: Optional[str] = None
    checkpoint: Optional[ExecutionCheckpoint] = None
    humanTask: Optional[HumanTaskSnapshot] = None
    nodes: List[NodeSnapshot]
    toolCalls: List[ToolInvocationSnapshot]
    escalationRequired: bool
    latestToolOutcome: Optional[ToolOutcomeSummary] = None


class AgentState(TypedDict):
    workflow_instance_id: str
    question: str
    session_context: Dict[str, Any]
    assistant: Dict[str, Any]
    graph: Dict[str, Any]
    entry_node_key: str
    current_node_key: Optional[str]
    next_node_key: Optional[str]
    route_key: Optional[str]
    final_reply: str
    summary: str
    retrieval_hits: List[str]
    retrieval_cache: Dict[str, List[str]]
    tool_results: Dict[str, Any]
    tool_calls: List[Dict[str, Any]]
    node_snapshots: List[Dict[str, Any]]
    human_task: Optional[Dict[str, Any]]
    checkpoint: Optional[Dict[str, Any]]
    escalation_required: bool
    latest_tool_outcome: Optional[Dict[str, Any]]
    human_input: Optional[Dict[str, Any]]
    resume_count: int


app = FastAPI(title="lynxus-agent-runtime", version="1.0.0")
logger = logging.getLogger("lynxus.agent_runtime")
LLM_REQUEST_TIMEOUT_SECONDS = 30


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def next_id(prefix: str) -> str:
    return f"{prefix}-{abs(hash((prefix, time.time_ns()))) % 1_000_000:06d}"


def append_node(state: AgentState, key: str, name: str, detail: str, status: str = "COMPLETED") -> None:
    state["node_snapshots"].append(
        {
            "nodeKey": key,
            "nodeName": name,
            "status": status,
            "detail": detail,
            "updatedAt": now_iso(),
        }
    )


def record_tool_call(
    state: AgentState,
    provider_type: str,
    resource: ResourceVersionSnapshot,
    operation: str,
    status: str,
    detail: str,
) -> None:
    state["tool_calls"].append(
        {
            "id": next_id("tool"),
            "providerType": provider_type,
            "resourceId": resource.resourceId,
            "resourceName": resource.resourceName,
            "operation": operation,
            "status": status,
            "detail": detail,
            "createdAt": now_iso(),
        }
    )


def assistant_from_state(state: AgentState) -> AssistantRunSnapshot:
    return AssistantRunSnapshot.model_validate(state["assistant"])


def graph_from_state(state: AgentState) -> GraphSnapshot:
    return GraphSnapshot.model_validate(state["graph"])


def resource_index(assistant: AssistantRunSnapshot) -> Dict[str, ResourceVersionSnapshot]:
    return {resource.resourceVersionId: resource for resource in assistant.resources}


def node_index(graph: GraphSnapshot) -> Dict[str, GraphNodeSnapshot]:
    return {node.nodeKey: node for node in graph.nodes}


def edge_index(graph: GraphSnapshot) -> Dict[str, List[GraphEdgeSnapshot]]:
    by_source: Dict[str, List[GraphEdgeSnapshot]] = defaultdict(list)
    for edge in graph.edges:
        by_source[edge.sourceNodeKey].append(edge)
    return by_source


def validate_graph(graph: GraphSnapshot, assistant: AssistantRunSnapshot) -> None:
    nodes = graph.nodes
    edges = graph.edges
    if not nodes:
        raise HTTPException(status_code=400, detail="graph requires nodes")

    node_keys = set()
    edge_keys = set()
    start_nodes = [node for node in nodes if node.nodeType == "START"]
    end_nodes = [node for node in nodes if node.nodeType == "END"]
    if len(start_nodes) != 1 or not end_nodes:
        raise HTTPException(status_code=400, detail="graph requires exactly one START and at least one END node")

    agent_ids = {agent.agentId for agent in assistant.agents}
    for node in nodes:
        if node.nodeKey in node_keys:
            raise HTTPException(status_code=400, detail=f"duplicate nodeKey: {node.nodeKey}")
        node_keys.add(node.nodeKey)
        if node.nodeType == "AGENT" and node.agentId not in agent_ids:
            raise HTTPException(status_code=400, detail=f"unknown agent for node {node.nodeKey}: {node.agentId}")
        if node.nodeType == "HUMAN" and node.humanNode is None:
            raise HTTPException(status_code=400, detail=f"human node {node.nodeKey} requires humanNode config")

    outgoing = edge_index(graph)
    for edge in edges:
        if edge.edgeKey in edge_keys:
            raise HTTPException(status_code=400, detail=f"duplicate edgeKey: {edge.edgeKey}")
        edge_keys.add(edge.edgeKey)
        if edge.sourceNodeKey not in node_keys or edge.targetNodeKey not in node_keys:
            raise HTTPException(status_code=400, detail=f"edge references missing nodes: {edge.edgeKey}")

    for node in nodes:
        node_edges = outgoing.get(node.nodeKey, [])
        if node.nodeType != "END" and not node_edges:
            raise HTTPException(status_code=400, detail=f"node has no outgoing edges: {node.nodeKey}")
        if len(node_edges) > 1 and not any(edge.defaultEdge for edge in node_edges):
            raise HTTPException(status_code=400, detail=f"branching node requires default edge: {node.nodeKey}")

    visited: set[str] = set()

    def walk(node_key: str) -> None:
        if node_key in visited:
            return
        visited.add(node_key)
        for outgoing_edge in outgoing.get(node_key, []):
            walk(outgoing_edge.targetNodeKey)

    walk(start_nodes[0].nodeKey)
    if len(visited) != len(nodes):
        raise HTTPException(status_code=400, detail="graph contains unreachable nodes")


def find_start_node(graph: GraphSnapshot) -> GraphNodeSnapshot:
    return next(node for node in graph.nodes if node.nodeType == "START")


def find_agent(assistant: AssistantRunSnapshot, agent_id: Optional[str]) -> AgentSnapshot:
    if not agent_id:
        raise HTTPException(status_code=500, detail="agent node missing agentId")
    for agent in assistant.agents:
        if agent.agentId == agent_id:
            return agent
    raise HTTPException(status_code=500, detail=f"agent not found: {agent_id}")


def resolve_resource(assistant: AssistantRunSnapshot, resource_version_id: Optional[str]) -> Optional[ResourceVersionSnapshot]:
    if not resource_version_id:
        return None
    for resource in assistant.resources:
        if resource.resourceVersionId == resource_version_id:
            return resource
    return None


def resolve_model_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> ResourceVersionSnapshot:
    model_version_id = (
        agent.executionPolicy.modelResourceVersionId
        if not agent.executionPolicy.inheritAssistantDefaults
        else assistant.assistantPolicy.providerResourceVersionId
    )
    if not model_version_id and agent.executionPolicy.modelResourceVersionId:
        model_version_id = agent.executionPolicy.modelResourceVersionId
    resource = resolve_resource(assistant, model_version_id or assistant.assistantPolicy.providerResourceVersionId)
    if not resource or not resource.configuration.llmModel:
        raise HTTPException(status_code=500, detail=f"No active model resource configured for agent {agent.agentId}")
    return resource


def resolve_prompt_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[ResourceVersionSnapshot]:
    prompt_version_id = agent.executionPolicy.promptTemplateResourceVersionId or assistant.assistantPolicy.promptTemplateResourceVersionId
    resource = resolve_resource(assistant, prompt_version_id)
    if resource and resource.configuration.promptTemplate:
        return resource
    return None


def resolve_knowledge_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[ResourceVersionSnapshot]:
    resource_version_id = agent.executionPolicy.knowledgeBaseResourceVersionId
    if agent.executionPolicy.inheritAssistantDefaults and not resource_version_id:
        resource_version_id = assistant.assistantPolicy.knowledgeBaseResourceVersionId
    resource = resolve_resource(assistant, resource_version_id)
    if resource and resource.configuration.knowledgeBase:
        return resource
    return None


def resolve_tool_resources(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> List[ResourceVersionSnapshot]:
    tools = []
    for resource_version_id in agent.executionPolicy.toolResourceVersionIds:
        resource = resolve_resource(assistant, resource_version_id)
        if resource:
            tools.append(resource)
    return tools


def build_prompt(
    prompt_config: Optional[PromptTemplateConfig],
    question: str,
    knowledge_context: List[str],
    tool_results: Dict[str, Any],
    human_input: Optional[Dict[str, Any]],
) -> str:
    template = prompt_config.userPromptTemplate if prompt_config else "{{question}}"
    return (
        template.replace("{{question}}", question)
        .replace("{{knowledge_context}}", "\n".join(knowledge_context))
        .replace("{{tool_results}}", json.dumps(tool_results, ensure_ascii=False))
        .replace("{{human_input}}", json.dumps(human_input or {}, ensure_ascii=False))
    )


def available_route_keys(graph: GraphSnapshot, node_key: str) -> List[str]:
    return [edge.routeKey for edge in graph.edges if edge.sourceNodeKey == node_key and edge.routeKey]


def tool_catalog_for_prompt(tool_resources: List[ResourceVersionSnapshot]) -> List[Dict[str, Any]]:
    catalog: List[Dict[str, Any]] = []
    for resource in tool_resources:
        tool = resource.configuration.tool
        if tool is None:
            continue
        catalog.append(
            {
                "toolResourceId": resource.resourceId,
                "toolResourceVersionId": resource.resourceVersionId,
                "toolResourceName": resource.resourceName,
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
        )
    return catalog


def build_structured_agent_prompt(
    prompt_config: Optional[PromptTemplateConfig],
    question: str,
    knowledge_context: List[str],
    tool_results: Dict[str, Any],
    human_input: Optional[Dict[str, Any]],
    tool_resources: List[ResourceVersionSnapshot],
    route_keys: List[str],
    loop_index: int,
) -> str:
    base_prompt = build_prompt(prompt_config, question, knowledge_context, tool_results, human_input)
    tool_catalog = tool_catalog_for_prompt(tool_resources)
    response_schema = {
        "message": "string",
        "routeDecision": "string | null",
        "toolRequests": [
            {
                "toolResourceVersionId": "string",
                "operation": "string",
                "arguments": {"key": "value"},
            }
        ],
        "finish": "boolean",
        "humanRequest": {
            "title": "string",
            "instruction": "string",
            "expectedAction": "string",
        },
    }
    guidance = {
        "loopIndex": loop_index,
        "availableRouteKeys": route_keys,
        "availableTools": tool_catalog,
        "toolCallRules": [
            "Only request tools listed in availableTools.",
            "Use toolRequests when external capability is needed.",
            "When tool results are sufficient, set finish=true.",
            "Return JSON only.",
        ],
    }
    return (
        f"{base_prompt}\n\n"
        f"可用路由与工具信息：\n{json.dumps(guidance, ensure_ascii=False, indent=2)}\n\n"
        f"请严格输出 JSON，字段结构如下：\n{json.dumps(response_schema, ensure_ascii=False, indent=2)}"
    )


def strip_json_fence(text: str) -> str:
    candidate = text.strip()
    if candidate.startswith("```"):
        lines = candidate.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        candidate = "\n".join(lines).strip()
    return candidate


def extract_json_object(text: str) -> Optional[Dict[str, Any]]:
    candidate = strip_json_fence(text)
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        start = candidate.find("{")
        end = candidate.rfind("}")
        if start >= 0 and end > start:
            try:
                parsed = json.loads(candidate[start : end + 1])
                return parsed if isinstance(parsed, dict) else None
            except json.JSONDecodeError:
                return None
    return None

async def call_llm(model_resource: ResourceVersionSnapshot, prompt: str, system_prompt: str) -> str:
    model_config = model_resource.configuration.llmModel
    if model_config is None:
        raise HTTPException(status_code=500, detail=f"resource {model_resource.resourceId} is not an llm model")

    api_key = os.getenv(model_config.apiKeyEnvVar, "")
    if not api_key:
        raise HTTPException(status_code=500, detail=f"Missing API key env var: {model_config.apiKeyEnvVar}")

    try:
        async with httpx.AsyncClient(timeout=LLM_REQUEST_TIMEOUT_SECONDS) as client:
            if model_config.providerType in {"OPENAI", "OPENAI_COMPATIBLE"}:
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/chat/completions",
                    headers={"Authorization": f"Bearer {api_key}"},
                    json={
                        "model": model_config.modelId,
                        "temperature": model_config.temperature,
                        "max_tokens": model_config.maxTokens,
                        "messages": [
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": prompt},
                        ],
                    },
                )
                response.raise_for_status()
                return response.json()["choices"][0]["message"]["content"]

            if model_config.providerType == "ANTHROPIC":
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/messages",
                    headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
                    json={
                        "model": model_config.modelId,
                        "max_tokens": model_config.maxTokens,
                        "system": system_prompt,
                        "messages": [{"role": "user", "content": prompt}],
                    },
                )
                response.raise_for_status()
                return response.json()["content"][0]["text"]

            if model_config.providerType == "GEMINI":
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/models/{model_config.modelId}:generateContent?key={api_key}",
                    json={
                        "contents": [{"parts": [{"text": f"{system_prompt}\n\n{prompt}"}]}],
                        "generationConfig": {
                            "temperature": model_config.temperature,
                            "maxOutputTokens": model_config.maxTokens,
                        },
                    },
                )
                response.raise_for_status()
                return response.json()["candidates"][0]["content"]["parts"][0]["text"]
    except httpx.TimeoutException as exc:
        raise HTTPException(
            status_code=504,
            detail=f"LLM request timed out for {model_config.baseUrl} model={model_config.modelId}: {exc}"
        ) from exc
    except httpx.HTTPStatusError as exc:
        response_text = exc.response.text[:500] if exc.response is not None else ""
        raise HTTPException(
            status_code=502,
            detail=f"LLM provider returned HTTP {exc.response.status_code if exc.response is not None else 'unknown'} for {model_config.baseUrl} model={model_config.modelId}: {response_text}"
        ) from exc
    except httpx.TransportError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"LLM transport error for {model_config.baseUrl} model={model_config.modelId}: {exc}"
        ) from exc

    raise HTTPException(status_code=400, detail=f"Unsupported provider: {model_config.providerType}")


def tokenize(text: str) -> List[str]:
    return [token for token in text.replace("？", " ").replace("，", " ").replace("。", " ").split() if token]


def knowledge_documents(config: KnowledgeBaseConfig) -> List[str]:
    return [
        f"{document.title}\n{document.content}".strip()
        for document in config.documents
        if document.content.strip()
    ]


def retrieve_knowledge(resource: Optional[ResourceVersionSnapshot], question: str) -> List[str]:
    if resource is None or resource.configuration.knowledgeBase is None:
        return []
    config = resource.configuration.knowledgeBase
    documents = knowledge_documents(config)
    if not documents:
        return []

    terms = tokenize(question) or list(question)
    scored: List[tuple[int, str]] = []
    for document in documents:
        score = sum(1 for term in terms if term and term in document)
        if score > 0:
            scored.append((score, document))
    if not scored:
        return documents[: config.defaultTopK]
    scored.sort(key=lambda item: item[0], reverse=True)
    return [document for _, document in scored[: config.defaultTopK]]


def resolve_tool_operation(resource: ResourceVersionSnapshot, operation_name: Optional[str] = None) -> ToolOperationConfig:
    config = resource.configuration.tool
    if config is None or not config.operations:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} has no tool operations configured")
    if operation_name:
        for operation in config.operations:
            if operation.name == operation_name:
                return operation
    return config.operations[0]


async def call_http_tool(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.tool
    if config is None or config.http is None:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} is not an HTTP tool")

    parsed = urlparse(config.http.endpoint)
    if parsed.hostname == "demo.local":
        question = payload.get("question", "")
        if operation.name == "evaluate_refund" and any(word in question for word in ["投诉", "争议", "人工", "升级"]):
            return {
                "eligibility": "REQUIRES_REVIEW",
                "routeKey": "manual_review",
                "actionPlan": "涉及争议和投诉，需要人工复核后再决定退款策略。",
            }
        if operation.name == "evaluate_refund":
            return {
                "eligibility": "APPROVED",
                "routeKey": "resolved",
                "actionPlan": "订单符合规则，可直接按标准退款流程处理。",
            }
        return {"status": "COMPLETED", "detail": "工具执行成功。"}

    async with httpx.AsyncClient(timeout=config.timeoutSeconds) as client:
        response = await client.request(config.http.method.upper(), config.http.endpoint, json=payload)
        response.raise_for_status()
        return response.json()


async def call_mcp_tool(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.tool
    if config is None or config.mcp is None:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} is not an MCP-backed tool")
    remote_tool_name = config.mcp.operationMappings.get(operation.name, operation.name)

    parsed = urlparse(config.mcp.connectionUri)
    if parsed.hostname == "demo.local":
        ticket_id = f"TICKET-{abs(hash((remote_tool_name, payload.get('question', ''), payload.get('operator', '')))) % 100000}"
        return {
            "ticketId": ticket_id,
            "status": "ACCEPTED",
            "detail": "已创建人工协同工单，并记录人工处理意见。",
        }

    async with httpx.AsyncClient(timeout=config.timeoutSeconds) as client:
        response = await client.post(
            config.mcp.connectionUri,
            json={
                "namespace": config.mcp.namespace,
                "tool": remote_tool_name,
                "arguments": payload,
            },
        )
        response.raise_for_status()
        return response.json()


async def call_tool(resource: ResourceVersionSnapshot, operation_name: Optional[str], payload: Dict[str, Any]) -> tuple[ToolOperationConfig, Dict[str, Any]]:
    config = resource.configuration.tool
    if config is None:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} is not a tool")
    operation = resolve_tool_operation(resource, operation_name)
    if config.providerType == "HTTP":
        return operation, await call_http_tool(resource, operation, payload)
    if config.providerType == "MCP":
        return operation, await call_mcp_tool(resource, operation, payload)
    raise HTTPException(status_code=400, detail=f"Unsupported tool provider: {config.providerType}")


def build_tool_payload(state: AgentState, hits: List[str]) -> Dict[str, Any]:
    human_input = state["human_input"] or {}
    latest_tool_result = next(reversed(list(state["tool_results"].values())), {}) if state["tool_results"] else {}
    return {
        "question": state["question"],
        "knowledgeHits": hits,
        "operator": human_input.get("operatorId", ""),
        "comment": human_input.get("comment", ""),
        "ticketId": latest_tool_result.get("ticketId", ""),
    }


def choose_tool_request(agent: AgentSnapshot, tool_resources: List[ResourceVersionSnapshot], state: AgentState, hits: List[str]) -> Optional[tuple[ResourceVersionSnapshot, str, Dict[str, Any]]]:
    if not tool_resources:
        return None
    question = state["question"]
    has_human_input = bool(state["human_input"])
    has_ticket_context = any(result.get("ticketId") for result in state["tool_results"].values() if isinstance(result, dict))

    preferred_operation_names: List[str] = []
    if has_human_input and has_ticket_context:
        preferred_operation_names.extend(["append_comment", "update_ticket", "sync_ticket"])
    if any(word in question for word in ["投诉", "人工", "升级", "工单", "协同"]):
        preferred_operation_names.extend(["create_ticket", "open_ticket"])
    if any(word in question for word in ["退款", "补偿", "退货", "售后"]):
        preferred_operation_names.extend(["evaluate_refund", "review_after_sales", "calculate_compensation"])
    preferred_operation_names.extend(["invoke"])

    for tool_resource in tool_resources:
        config = tool_resource.configuration.tool
        if config is None:
            continue
        operations = config.operations or []
        for preferred_name in preferred_operation_names:
            if any(operation.name == preferred_name for operation in operations):
                return tool_resource, preferred_name, build_tool_payload(state, hits)
        if operations:
            return tool_resource, operations[0].name, build_tool_payload(state, hits)
    return None


def infer_route_decision(graph: GraphSnapshot, node: GraphNodeSnapshot, state: AgentState) -> str:
    route_keys = available_route_keys(graph, node.nodeKey)
    if not route_keys:
        return "default"

    question = state["question"]
    latest_outcome = state["latest_tool_outcome"] or {}
    recommended_action = str(latest_outcome.get("recommendedAction", "")).lower()

    if "manual_review" in route_keys and (
        recommended_action in {"manual_review", "human_handoff"} or any(word in question for word in ["投诉", "人工", "升级"])
    ):
        return "manual_review"
    if "human_handoff" in route_keys and (
        recommended_action in {"human_handoff", "manual_review"} or any(word in question for word in ["投诉", "人工", "升级"])
    ):
        return "human_handoff"
    if "after_sales" in route_keys and any(word in question for word in ["退款", "补偿", "退货", "售后"]):
        return "after_sales"
    if "resolved" in route_keys and (
        recommended_action in {"resolved", "auto_resolve", "recorded"} or any(word in question for word in ["退款", "补偿", "退货", "售后"])
    ):
        return "resolved"
    if "faq" in route_keys:
        return "faq"
    if "default" in route_keys:
        return "default"
    return route_keys[0]


def normalize_tool_requests(
    raw_requests: List[Dict[str, Any]],
    tool_resources: List[ResourceVersionSnapshot],
    fallback_payload: Dict[str, Any],
) -> List[ToolRequest]:
    by_version_id = {resource.resourceVersionId: resource for resource in tool_resources}
    normalized: List[ToolRequest] = []
    for raw_request in raw_requests:
        if not isinstance(raw_request, dict):
            continue
        resource_version_id = str(raw_request.get("toolResourceVersionId", "")).strip()
        operation = str(raw_request.get("operation", "")).strip()
        arguments = raw_request.get("arguments", {})
        if resource_version_id not in by_version_id or not operation:
            continue
        normalized.append(
            ToolRequest(
                toolResourceVersionId=resource_version_id,
                operation=operation,
                arguments={**fallback_payload, **(arguments if isinstance(arguments, dict) else {})},
            )
        )
    return normalized


def parse_agent_structured_response(
    llm_output: str,
    graph: GraphSnapshot,
    node: GraphNodeSnapshot,
    agent: AgentSnapshot,
    tool_resources: List[ResourceVersionSnapshot],
    state: AgentState,
    hits: List[str],
) -> AgentStructuredResponse:
    payload = build_tool_payload(state, hits)
    agent_has_tool_result = any(key.startswith(f"{agent.agentId}:") for key in state["tool_results"].keys())
    parsed = extract_json_object(llm_output)
    if parsed is not None:
        route_decision = parsed.get("routeDecision")
        route_keys = set(available_route_keys(graph, node.nodeKey))
        if route_decision and route_keys and route_decision not in route_keys:
            route_decision = None
        tool_requests = normalize_tool_requests(parsed.get("toolRequests", []), tool_resources, payload)
        return AgentStructuredResponse(
            message=str(parsed.get("message", "")).strip(),
            routeDecision=str(route_decision).strip() if route_decision else None,
            toolRequests=tool_requests,
            finish=bool(parsed.get("finish", False)),
            humanRequest=HumanRequest.model_validate(parsed.get("humanRequest")) if parsed.get("humanRequest") else None,
        )

    fallback_tool_request = choose_tool_request(agent, tool_resources, state, hits)
    fallback_requests = []
    if fallback_tool_request is not None and not agent_has_tool_result:
        fallback_resource, fallback_operation, fallback_payload = fallback_tool_request
        fallback_requests = [
            ToolRequest(
                toolResourceVersionId=fallback_resource.resourceVersionId,
                operation=fallback_operation,
                arguments=fallback_payload,
            )
        ]
    return AgentStructuredResponse(
        message=llm_output.strip(),
        routeDecision=infer_route_decision(graph, node, state),
        toolRequests=fallback_requests,
        finish=not bool(fallback_requests),
        humanRequest=None,
    )


def store_tool_result(
    state: AgentState,
    agent: AgentSnapshot,
    resource: ResourceVersionSnapshot,
    operation: ToolOperationConfig,
    result: Dict[str, Any],
) -> None:
    key = f"{agent.agentId}:{operation.name}:{len(state['tool_results']) + 1}"
    state["tool_results"][key] = {
        "toolResourceId": resource.resourceId,
        "toolResourceVersionId": resource.resourceVersionId,
        "toolResourceName": resource.resourceName,
        "providerType": resource.configuration.tool.providerType if resource.configuration.tool else "UNKNOWN",
        "operation": operation.name,
        "result": result,
    }


def build_tool_outcome(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, result: Dict[str, Any]) -> Dict[str, Any]:
    provider_type = resource.configuration.tool.providerType if resource.configuration.tool else "UNKNOWN"
    external_reference = str(result.get("ticketId", result.get("externalReference", "")))
    recommended_action = str(result.get("recommendedAction", result.get("routeKey", "DEFAULT")))
    detail = str(result.get("detail", result.get("actionPlan", json.dumps(result, ensure_ascii=False))))
    return {
        "toolResourceId": resource.resourceId,
        "toolResourceName": resource.resourceName,
        "operation": operation.name,
        "providerType": provider_type,
        "status": str(result.get("status", "COMPLETED")),
        "externalReference": external_reference,
        "recommendedAction": recommended_action,
        "detail": detail,
    }


def export_state(state: AgentState) -> Dict[str, Any]:
    return {
        "question": state["question"],
        "session_context": state["session_context"],
        "assistant": state["assistant"],
        "graph": state["graph"],
        "final_reply": state["final_reply"],
        "summary": state["summary"],
        "retrieval_hits": state["retrieval_hits"],
        "retrieval_cache": state["retrieval_cache"],
        "tool_results": state["tool_results"],
        "tool_calls": state["tool_calls"],
        "node_snapshots": state["node_snapshots"],
        "latest_tool_outcome": state["latest_tool_outcome"],
        "escalation_required": state["escalation_required"],
        "resume_count": state["resume_count"],
    }


def restore_state(data: Dict[str, Any], resume_request: WorkflowResumeRequest) -> AgentState:
    return {
        "workflow_instance_id": resume_request.workflowInstanceId,
        "question": data["question"],
        "session_context": resume_request.sessionContext.model_dump(mode="json"),
        "assistant": resume_request.assistant.model_dump(mode="json"),
        "graph": resume_request.assistant.graph.model_dump(mode="json"),
        "entry_node_key": resume_request.checkpoint.currentNodeKey or "end",
        "current_node_key": resume_request.checkpoint.currentNodeKey,
        "next_node_key": None,
        "route_key": None,
        "final_reply": data.get("final_reply", ""),
        "summary": data.get("summary", ""),
        "retrieval_hits": data.get("retrieval_hits", []),
        "retrieval_cache": data.get("retrieval_cache", {}),
        "tool_results": data.get("tool_results", {}),
        "tool_calls": data.get("tool_calls", []),
        "node_snapshots": data.get("node_snapshots", []),
        "human_task": None,
        "checkpoint": None,
        "escalation_required": data.get("escalation_required", False),
        "latest_tool_outcome": data.get("latest_tool_outcome"),
        "human_input": resume_request.action.model_dump(mode="json"),
        "resume_count": resume_request.checkpoint.resumeCount + 1,
    }


def resolve_next_node(graph: GraphSnapshot, source_node_key: str, route_key: Optional[str]) -> Optional[str]:
    candidates = [edge for edge in graph.edges if edge.sourceNodeKey == source_node_key]
    if not candidates:
        return None
    if route_key:
        for edge in candidates:
            if edge.routeKey == route_key:
                return edge.targetNodeKey
    for edge in candidates:
        if edge.defaultEdge:
            return edge.targetNodeKey
    return candidates[0].targetNodeKey


async def execute_agent_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    assistant = assistant_from_state(state)
    graph = graph_from_state(state)
    agent = find_agent(assistant, node.agentId)
    kb_resource = resolve_knowledge_resource(assistant, agent)
    hits = retrieve_knowledge(kb_resource, state["question"]) if (agent.executionPolicy.ragEnabled or assistant.assistantPolicy.ragEnabled) else []
    state["retrieval_hits"] = hits
    state["retrieval_cache"][agent.agentId] = hits

    prompt_resource = resolve_prompt_resource(assistant, agent)
    model_resource = resolve_model_resource(assistant, agent)
    system_prompt = prompt_resource.configuration.promptTemplate.systemPrompt if prompt_resource and prompt_resource.configuration.promptTemplate else agent.instructions
    tool_resources = resolve_tool_resources(assistant, agent)
    route_key = infer_route_decision(graph, node, state)
    detail_lines = [agent.instructions]

    final_message = ""
    last_llm_output = ""
    loop_count = 0
    for loop_index in range(3):
        loop_count = loop_index + 1
        prompt = build_structured_agent_prompt(
            prompt_resource.configuration.promptTemplate if prompt_resource else None,
            state["question"],
            hits,
            state["tool_results"],
            state["human_input"],
            tool_resources,
            available_route_keys(graph, node.nodeKey),
            loop_index,
        )
        last_llm_output = await call_llm(model_resource, prompt, system_prompt)
        structured = parse_agent_structured_response(last_llm_output, graph, node, agent, tool_resources, state, hits)
        if structured.message:
            final_message = structured.message
        if structured.routeDecision:
            route_key = structured.routeDecision
        if structured.humanRequest and not state["summary"]:
            state["summary"] = structured.humanRequest.instruction or state["summary"]

        if structured.toolRequests and loop_index < 2:
            detail_lines.append(f"tool_requests={len(structured.toolRequests)}")
            tool_failed = False
            for tool_request in structured.toolRequests:
                tool_resource = next(
                    (resource for resource in tool_resources if resource.resourceVersionId == tool_request.toolResourceVersionId),
                    None,
                )
                if tool_resource is None:
                    continue
                try:
                    operation, tool_result = await call_tool(tool_resource, tool_request.operation, tool_request.arguments)
                    store_tool_result(state, agent, tool_resource, operation, tool_result)
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, operation.name, "COMPLETED", json.dumps(tool_result, ensure_ascii=False))
                    state["latest_tool_outcome"] = build_tool_outcome(tool_resource, operation, tool_result)
                    detail_lines.append(f"tool_operation={operation.name}")
                    route_key = str(tool_result.get("routeKey", route_key or "default"))
                except Exception as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", str(exc))
                    route_key = "manual_review"
                    state["summary"] = f"工具调用失败，转人工处理：{exc}"
                    state["escalation_required"] = True
                    detail_lines.append("tool_failed=manual_review")
                    tool_failed = True
                    break
            if tool_failed:
                break
            continue

        if structured.finish or not structured.toolRequests:
            break

    message = final_message or last_llm_output or agent.instructions
    human_comment = state["human_input"]["comment"] if state["human_input"] else ""
    latest_outcome = state["latest_tool_outcome"] or {}
    suggestion = str(latest_outcome.get("detail", ""))
    suffix_parts = [part for part in [suggestion, f"人工处理说明：{human_comment}" if human_comment else ""] if part]
    suffix = "\n\n".join(suffix_parts)
    state["final_reply"] = f"{message}\n\n{suffix}".strip() if suffix and suggestion not in message else message
    if state["summary"]:
        state["summary"] = state["summary"]
    elif suggestion:
        state["summary"] = suggestion
    else:
        state["summary"] = state["final_reply"]
    detail_lines.append(f"loop_count={loop_count}")
    detail_lines.append(f"route_key={route_key}")

    state["route_key"] = route_key
    state["next_node_key"] = resolve_next_node(graph, node.nodeKey, route_key)
    state["current_node_key"] = node.nodeKey
    append_node(state, node.nodeKey, node.nodeName, "\n".join(detail_lines))


def execute_start_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    state["current_node_key"] = node.nodeKey
    state["next_node_key"] = resolve_next_node(graph_from_state(state), node.nodeKey, "default")
    append_node(state, node.nodeKey, node.nodeName, state["question"])


def execute_end_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    state["current_node_key"] = node.nodeKey
    state["next_node_key"] = "__end__"
    final_reply = state["final_reply"] or state["summary"] or "流程已完成。"
    state["final_reply"] = final_reply
    state["summary"] = state["summary"] or final_reply
    append_node(state, node.nodeKey, node.nodeName, state["summary"])


def execute_human_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    if node.humanNode is None:
        raise HTTPException(status_code=500, detail=f"human node missing config: {node.nodeKey}")
    state["current_node_key"] = node.nodeKey
    next_node_key = resolve_next_node(graph_from_state(state), node.nodeKey, node.humanNode.resumeRouteKey)
    state["human_task"] = {
        "nodeKey": node.nodeKey,
        "title": node.humanNode.title,
        "instruction": node.humanNode.instruction,
        "expectedAction": node.humanNode.expectedAction,
    }
    state["escalation_required"] = True
    state["summary"] = f"等待人工处理：{node.humanNode.instruction}"
    state["checkpoint"] = {
        "checkpointId": next_id("checkpoint"),
        "currentNodeKey": next_node_key,
        "waitingNodeKey": node.nodeKey,
        "statePayload": json.dumps(export_state(state), ensure_ascii=False),
        "resumeCount": state["resume_count"],
    }
    state["next_node_key"] = "__end__"
    append_node(state, node.nodeKey, node.nodeName, state["summary"], status="WAITING_HUMAN")
    logger.info(
        "workflow %s paused for human action waitingNode=%s resumeNode=%s",
        state["workflow_instance_id"],
        node.nodeKey,
        next_node_key,
    )


def make_node_executor(node: GraphNodeSnapshot) -> Callable[[AgentState], Awaitable[AgentState]]:
    async def executor(state: AgentState) -> AgentState:
        if node.nodeType == "START":
            execute_start_node(state, node)
        elif node.nodeType == "AGENT":
            await execute_agent_node(state, node)
        elif node.nodeType == "HUMAN":
            execute_human_node(state, node)
        elif node.nodeType == "END":
            execute_end_node(state, node)
        else:  # pragma: no cover
            raise HTTPException(status_code=500, detail=f"Unsupported node type: {node.nodeType}")
        return state

    return executor


def make_route_selector(node: GraphNodeSnapshot) -> Callable[[AgentState], str]:
    def selector(state: AgentState) -> str:
        next_node_key = state.get("next_node_key")
        if not next_node_key:
            return "__end__"
        return next_node_key

    return selector


def prune_graph_from_entry(graph_snapshot: GraphSnapshot, entry_node_key: str) -> GraphSnapshot:
    outgoing = edge_index(graph_snapshot)
    reachable: set[str] = set()
    stack = [entry_node_key]
    while stack:
        node_key = stack.pop()
        if node_key in reachable:
            continue
        reachable.add(node_key)
        for edge in outgoing.get(node_key, []):
            stack.append(edge.targetNodeKey)

    return GraphSnapshot(
        executionMode=graph_snapshot.executionMode,
        nodes=[node for node in graph_snapshot.nodes if node.nodeKey in reachable],
        edges=[
            edge
            for edge in graph_snapshot.edges
            if edge.sourceNodeKey in reachable and edge.targetNodeKey in reachable
        ],
    )


def compile_graph(graph_snapshot: GraphSnapshot, entry_node_key: str):
    active_graph = prune_graph_from_entry(graph_snapshot, entry_node_key)
    graph = StateGraph(AgentState)
    outgoing = edge_index(active_graph)
    for node in active_graph.nodes:
        graph.add_node(node.nodeKey, make_node_executor(node))
    graph.set_entry_point(entry_node_key)

    for node in active_graph.nodes:
        if node.nodeType == "END":
            graph.add_edge(node.nodeKey, END)
            continue
        mapping = {"__end__": END}
        for edge in outgoing.get(node.nodeKey, []):
            mapping[edge.targetNodeKey] = edge.targetNodeKey
        graph.add_conditional_edges(node.nodeKey, make_route_selector(node), mapping)

    return graph.compile()


def workflow_result_from_state(state: AgentState) -> WorkflowResult:
    status = "WAITING_HUMAN" if state["human_task"] else "COMPLETED"
    return WorkflowResult(
        workflowInstanceId=state["workflow_instance_id"],
        status=status,
        summary=state["summary"] or state["final_reply"] or "流程已执行。",
        finalReply=state["final_reply"] or None,
        currentNodeKey=state["current_node_key"],
        checkpoint=ExecutionCheckpoint(**state["checkpoint"]) if state["checkpoint"] else None,
        humanTask=HumanTaskSnapshot(**state["human_task"]) if state["human_task"] else None,
        nodes=[NodeSnapshot(**node) for node in state["node_snapshots"]],
        toolCalls=[ToolInvocationSnapshot(**tool) for tool in state["tool_calls"]],
        escalationRequired=state["escalation_required"],
        latestToolOutcome=ToolOutcomeSummary(**state["latest_tool_outcome"]) if state["latest_tool_outcome"] else None,
    )


@app.post("/agent-runs/start", response_model=WorkflowResult)
async def start_agent_run(request: WorkflowStartRequest) -> WorkflowResult:
    logger.info("workflow %s start request received", request.workflowInstanceId)
    validate_graph(request.assistant.graph, request.assistant)
    entry_node = find_start_node(request.assistant.graph)
    state: AgentState = {
        "workflow_instance_id": request.workflowInstanceId,
        "question": request.question,
        "session_context": request.sessionContext.model_dump(mode="json"),
        "assistant": request.assistant.model_dump(mode="json"),
        "graph": request.assistant.graph.model_dump(mode="json"),
        "entry_node_key": entry_node.nodeKey,
        "current_node_key": None,
        "next_node_key": None,
        "route_key": None,
        "final_reply": "",
        "summary": "",
        "retrieval_hits": [],
        "retrieval_cache": {},
        "tool_results": {},
        "tool_calls": [],
        "node_snapshots": [],
        "human_task": None,
        "checkpoint": None,
        "escalation_required": False,
        "latest_tool_outcome": None,
        "human_input": None,
        "resume_count": 0,
    }
    graph = compile_graph(request.assistant.graph, entry_node.nodeKey)
    result_state = await graph.ainvoke(state)
    result = workflow_result_from_state(result_state)
    logger.info(
        "workflow %s start request completed status=%s currentNode=%s summary=%s",
        request.workflowInstanceId,
        result.status,
        result.currentNodeKey,
        result.summary,
    )
    return result


@app.post("/agent-runs/resume", response_model=WorkflowResult)
async def resume_agent_run(request: WorkflowResumeRequest) -> WorkflowResult:
    logger.info(
        "workflow %s resume request received action=%s operator=%s",
        request.workflowInstanceId,
        request.action.action,
        request.action.operatorId,
    )
    validate_graph(request.assistant.graph, request.assistant)
    payload = json.loads(request.checkpoint.statePayload or "{}")
    state = restore_state(payload, request)
    entry_node_key = request.checkpoint.currentNodeKey or "end"
    graph = compile_graph(request.assistant.graph, entry_node_key)
    result_state = await graph.ainvoke(state)
    result = workflow_result_from_state(result_state)
    logger.info(
        "workflow %s resume request completed status=%s currentNode=%s summary=%s",
        request.workflowInstanceId,
        result.status,
        result.currentNodeKey,
        result.summary,
    )
    if result.status == "WAITING_HUMAN":
        return result
    return result.model_copy(update={"escalationRequired": False})
