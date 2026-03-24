from __future__ import annotations

import json
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


class KnowledgeBaseConfig(BaseModel):
    sourceType: str
    sourceLocation: str
    syncMode: str
    retrievalMode: str
    embeddingModel: str
    chunkStrategy: str
    defaultTopK: int
    documentCount: int


class SkillConfig(BaseModel):
    runtime: str
    endpoint: str
    method: str
    authType: str
    timeoutSeconds: int
    retryPolicy: str
    inputSchema: str
    outputSchema: str


class McpConfig(BaseModel):
    serverName: str
    transport: str
    connectionUri: str
    namespace: str
    authType: str
    heartbeatSeconds: int
    exposedTools: List[str]


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
    skill: Optional[SkillConfig] = None
    mcp: Optional[McpConfig] = None
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
    promptTemplateResourceId: Optional[str] = None
    temperature: float
    maxTokens: int
    ragEnabled: bool
    knowledgeBaseResourceId: Optional[str] = None
    ragTopK: int
    memoryEnabled: bool
    memoryWindowSize: int


class AgentExecutionPolicySnapshot(BaseModel):
    inheritAssistantDefaults: bool
    modelResourceId: Optional[str] = None
    promptTemplateResourceId: Optional[str] = None
    inlinePrompt: str = ""
    ragEnabled: bool
    knowledgeBaseResourceId: Optional[str] = None
    memoryWindowSize: int
    toolResourceIds: List[str]


class AgentSnapshot(BaseModel):
    agentId: str
    name: str
    role: str
    instructions: str
    executionPolicy: AgentExecutionPolicySnapshot
    bindingResourceVersionIds: List[str]


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
    toolType: str
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


class McpInvocationSummary(BaseModel):
    capabilityName: str
    externalTicketId: str
    status: str
    recommendedAction: str
    detail: str


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
    mcpSummary: Optional[McpInvocationSummary] = None


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
    mcp_summary: Optional[Dict[str, Any]]
    human_input: Optional[Dict[str, Any]]
    resume_count: int


app = FastAPI(title="lynxus-agent-runtime", version="1.0.0")
LLM_REQUEST_TIMEOUT_SECONDS = 30


SEED_KB: Dict[str, List[str]] = {
    "seed://support-faq": [
        "密码重置可以通过登录页的忘记密码完成，若邮箱不可用则需要人工验证。",
        "售后退款通常需要结合订单状态、支付时间和投诉原因综合判定。",
        "涉及争议、投诉或升级字样的请求应优先进入人工协同分支。",
        "人工协同时应创建工单，并保留问题摘要、处理意见与回访结果。",
        "若订单满足七天无理由且未发货，可直接给出退款结论。",
        "若订单已发货或存在争议，需要人工进一步确认。",
    ]
}


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
    tool_type: str,
    resource: ResourceVersionSnapshot,
    operation: str,
    status: str,
    detail: str,
) -> None:
    state["tool_calls"].append(
        {
            "id": next_id("tool"),
            "toolType": tool_type,
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
    return {resource.resourceId: resource for resource in assistant.resources}


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


def resolve_resource(assistant: AssistantRunSnapshot, resource_id: Optional[str]) -> Optional[ResourceVersionSnapshot]:
    if not resource_id:
        return None
    for resource in assistant.resources:
        if resource.resourceId == resource_id:
            return resource
    return None


def resolve_model_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> ResourceVersionSnapshot:
    model_id = agent.executionPolicy.modelResourceId if not agent.executionPolicy.inheritAssistantDefaults else assistant.assistantPolicy.providerResourceId
    if not model_id and agent.executionPolicy.modelResourceId:
        model_id = agent.executionPolicy.modelResourceId
    resource = resolve_resource(assistant, model_id or assistant.assistantPolicy.providerResourceId)
    if not resource or not resource.configuration.llmModel:
        raise HTTPException(status_code=500, detail=f"No active model resource configured for agent {agent.agentId}")
    return resource


def resolve_prompt_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[ResourceVersionSnapshot]:
    prompt_id = agent.executionPolicy.promptTemplateResourceId or assistant.assistantPolicy.promptTemplateResourceId
    resource = resolve_resource(assistant, prompt_id)
    if resource and resource.configuration.promptTemplate:
        return resource
    return None


def resolve_knowledge_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[ResourceVersionSnapshot]:
    resource_id = agent.executionPolicy.knowledgeBaseResourceId
    if agent.executionPolicy.inheritAssistantDefaults and not resource_id:
        resource_id = assistant.assistantPolicy.knowledgeBaseResourceId
    resource = resolve_resource(assistant, resource_id)
    if resource and resource.configuration.knowledgeBase:
        return resource
    return None


def resolve_tool_resources(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> List[ResourceVersionSnapshot]:
    tools = []
    for resource_id in agent.executionPolicy.toolResourceIds:
        resource = resolve_resource(assistant, resource_id)
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


def retrieve_knowledge(resource: Optional[ResourceVersionSnapshot], question: str) -> List[str]:
    if resource is None or resource.configuration.knowledgeBase is None:
        return []
    config = resource.configuration.knowledgeBase
    documents = SEED_KB.get(config.sourceLocation, [])
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


async def call_skill(resource: ResourceVersionSnapshot, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.skill
    if config is None:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} is not a skill")

    parsed = urlparse(config.endpoint)
    if parsed.hostname == "demo.local":
        question = payload.get("question", "")
        if any(word in question for word in ["投诉", "争议", "人工", "升级"]):
            return {
                "eligibility": "REQUIRES_REVIEW",
                "routeKey": "manual_review",
                "actionPlan": "涉及争议和投诉，需要人工复核后再决定退款策略。",
            }
        return {
            "eligibility": "APPROVED",
            "routeKey": "resolved",
            "actionPlan": "订单符合规则，可直接按标准退款流程处理。",
        }

    async with httpx.AsyncClient(timeout=config.timeoutSeconds) as client:
        response = await client.request(config.method.upper(), config.endpoint, json=payload)
        response.raise_for_status()
        return response.json()


async def call_mcp(resource: ResourceVersionSnapshot, tool_name: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.mcp
    if config is None:
        raise HTTPException(status_code=500, detail=f"resource {resource.resourceId} is not an MCP resource")

    parsed = urlparse(config.connectionUri)
    if parsed.hostname == "demo.local":
        ticket_id = f"TICKET-{abs(hash((tool_name, payload.get('question', ''), payload.get('operator', '')))) % 100000}"
        return {
            "ticketId": ticket_id,
            "status": "ACCEPTED",
            "detail": "已创建人工协同工单，并记录人工处理意见。",
        }

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            config.connectionUri,
            json={
                "namespace": config.namespace,
                "tool": tool_name,
                "arguments": payload,
            },
        )
        response.raise_for_status()
        return response.json()


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
        "mcp_summary": state["mcp_summary"],
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
        "mcp_summary": data.get("mcp_summary"),
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
    agent = find_agent(assistant, node.agentId)
    kb_resource = resolve_knowledge_resource(assistant, agent)
    hits = retrieve_knowledge(kb_resource, state["question"]) if (agent.executionPolicy.ragEnabled or assistant.assistantPolicy.ragEnabled) else []
    state["retrieval_hits"] = hits
    state["retrieval_cache"][agent.agentId] = hits

    prompt_resource = resolve_prompt_resource(assistant, agent)
    model_resource = resolve_model_resource(assistant, agent)
    system_prompt = prompt_resource.configuration.promptTemplate.systemPrompt if prompt_resource and prompt_resource.configuration.promptTemplate else agent.instructions
    prompt = build_prompt(
        prompt_resource.configuration.promptTemplate if prompt_resource else None,
        state["question"],
        hits,
        state["tool_results"],
        state["human_input"],
    )

    route_key = "default"
    detail_lines = [agent.instructions]

    if agent.role == "router":
        question = state["question"]
        if any(word in question for word in ["投诉", "人工", "升级"]):
            route_key = "human_handoff"
            state["summary"] = "问题需要人工介入，已进入人工协同节点。"
        elif any(word in question for word in ["退款", "补偿", "售后"]):
            route_key = "after_sales"
            state["summary"] = "问题进入售后策略分支。"
        else:
            route_key = "faq"
            state["summary"] = "问题进入 FAQ 分支。"
        detail_lines.append(f"route_key={route_key}")
    elif agent.role == "policy":
        tool_resources = [tool for tool in resolve_tool_resources(assistant, agent) if tool.configuration.skill]
        if not tool_resources:
            raise HTTPException(status_code=500, detail=f"agent {agent.agentId} has no skill resource")
        skill_resource = tool_resources[0]
        try:
            result = await call_skill(skill_resource, {"question": state["question"], "knowledgeHits": hits})
            state["tool_results"][agent.agentId] = result
            record_tool_call(state, "SKILL", skill_resource, "invoke", "COMPLETED", json.dumps(result, ensure_ascii=False))
            route_key = result.get("routeKey", "default")
            llm_output = await call_llm(model_resource, prompt, system_prompt)
            state["final_reply"] = f"{llm_output}\n\n处理建议：{result.get('actionPlan', '')}".strip()
            state["summary"] = result.get("actionPlan", state["final_reply"])
            detail_lines.append(f"skill_route={route_key}")
        except Exception as exc:
            record_tool_call(state, "SKILL", skill_resource, "invoke", "FAILED", str(exc))
            route_key = "manual_review"
            state["summary"] = f"工具调用失败，转人工处理：{exc}"
            state["escalation_required"] = True
            detail_lines.append("skill_failed=manual_review")
    elif agent.role == "handoff":
        tool_resources = [tool for tool in resolve_tool_resources(assistant, agent) if tool.configuration.mcp]
        if not tool_resources:
            raise HTTPException(status_code=500, detail=f"agent {agent.agentId} has no MCP resource")
        mcp_resource = tool_resources[0]
        mcp_result = await call_mcp(
            mcp_resource,
            "create_ticket",
            {
                "question": state["question"],
                "operator": state["human_input"]["operatorId"] if state["human_input"] else "",
                "comment": state["human_input"]["comment"] if state["human_input"] else "",
            },
        )
        state["tool_results"][agent.agentId] = mcp_result
        record_tool_call(state, "MCP", mcp_resource, "create_ticket", "COMPLETED", json.dumps(mcp_result, ensure_ascii=False))
        state["mcp_summary"] = {
            "capabilityName": "创建协同工单",
            "externalTicketId": mcp_result.get("ticketId", ""),
            "status": mcp_result.get("status", "ACCEPTED"),
            "recommendedAction": "HUMAN_HANDOFF",
            "detail": mcp_result.get("detail", "已完成人工协同。"),
        }
        llm_output = await call_llm(model_resource, prompt, system_prompt)
        human_comment = state["human_input"]["comment"] if state["human_input"] else ""
        state["final_reply"] = f"{llm_output}\n\n人工处理说明：{human_comment}".strip()
        state["summary"] = state["mcp_summary"]["detail"]
        route_key = "default"
    else:
        llm_output = await call_llm(model_resource, prompt, system_prompt)
        state["final_reply"] = llm_output
        state["summary"] = llm_output
        route_key = "default"

    state["route_key"] = route_key
    state["next_node_key"] = resolve_next_node(graph_from_state(state), node.nodeKey, route_key)
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


def compile_graph(graph_snapshot: GraphSnapshot, entry_node_key: str):
    graph = StateGraph(AgentState)
    outgoing = edge_index(graph_snapshot)
    for node in graph_snapshot.nodes:
        graph.add_node(node.nodeKey, make_node_executor(node))
    graph.set_entry_point(entry_node_key)

    for node in graph_snapshot.nodes:
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
        mcpSummary=McpInvocationSummary(**state["mcp_summary"]) if state["mcp_summary"] else None,
    )


@app.post("/agent-runs/start", response_model=WorkflowResult)
async def start_agent_run(request: WorkflowStartRequest) -> WorkflowResult:
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
        "mcp_summary": None,
        "human_input": None,
        "resume_count": 0,
    }
    graph = compile_graph(request.assistant.graph, entry_node.nodeKey)
    result_state = await graph.ainvoke(state)
    return workflow_result_from_state(result_state)


@app.post("/agent-runs/resume", response_model=WorkflowResult)
async def resume_agent_run(request: WorkflowResumeRequest) -> WorkflowResult:
    validate_graph(request.assistant.graph, request.assistant)
    payload = json.loads(request.checkpoint.statePayload or "{}")
    state = restore_state(payload, request)
    entry_node_key = request.checkpoint.currentNodeKey or "end"
    graph = compile_graph(request.assistant.graph, entry_node_key)
    result_state = await graph.ainvoke(state)
    result = workflow_result_from_state(result_state)
    if result.status == "WAITING_HUMAN":
        return result
    return result.model_copy(update={"escalationRequired": False})
