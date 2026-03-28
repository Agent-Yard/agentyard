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


class KnowledgeBindingSnapshot(BaseModel):
    knowledgeBaseId: str
    knowledgeBaseName: str
    knowledgeReleaseId: str
    knowledgeReleaseVersion: str
    snapshotId: str
    defaultTopK: int
    retrievalMode: str = "HYBRID"
    minScore: float = 0.1


class KnowledgeHit(BaseModel):
    chunkId: str
    documentId: str
    documentTitle: str
    sourceUri: str
    snippet: str
    score: float
    pageNumber: Optional[int] = None
    headingPath: str = ""


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


class SkillConfig(BaseModel):
    skillName: str
    skillDesc: str
    skillPrompt: str


class ResourceConfigurationSnapshot(BaseModel):
    type: str
    tool: Optional[ToolConfig] = None
    llmModel: Optional[LlmModelConfig] = None
    skill: Optional[SkillConfig] = None


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
    memoryEnabled: bool
    memoryWindowSize: int


class AgentExecutionPolicySnapshot(BaseModel):
    inheritAssistantDefaults: bool
    modelResourceId: Optional[str] = None
    modelResourceVersionId: Optional[str] = None
    systemPrompt: str = ""
    ragEnabled: bool
    inheritAssistantKnowledge: bool
    knowledge: Optional[KnowledgeBindingSnapshot] = None
    memoryWindowSize: int
    skillResourceIds: List[str]
    skillResourceVersionIds: List[str]
    toolResourceIds: List[str]
    toolResourceVersionIds: List[str]


class AgentSnapshot(BaseModel):
    agentId: str
    name: str
    role: str
    responsibility: str
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
    assistantKnowledge: Optional[KnowledgeBindingSnapshot] = None
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
    loadedSkillResourceVersionIds: List[str] = Field(default_factory=list)


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
    source: str = "GRAPH_NODE"
    allowedActions: List[str] = Field(default_factory=list)


class PauseReasonSnapshot(BaseModel):
    code: str
    detail: str
    source: str


class ToolOutcomeSummary(BaseModel):
    toolResourceId: str
    toolResourceName: str
    operation: str
    providerType: str
    status: str
    externalReference: str
    recommendedAction: str
    detail: str


class ToolExecutionRecord(BaseModel):
    agentId: str
    toolResourceVersionId: str
    toolResourceName: str
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)
    rawResult: Dict[str, Any] = Field(default_factory=dict)
    normalizedOutcome: ToolOutcomeSummary
    status: str
    createdAt: str


class ToolRequest(BaseModel):
    toolResourceVersionId: str
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class HumanRequest(BaseModel):
    title: str = ""
    instruction: str = ""
    expectedAction: str = ""


class AgentStructuredResponse(BaseModel):
    decisionType: str
    message: str = ""
    routeDecision: Optional[str] = None
    skillReads: List[str] = Field(default_factory=list)
    toolRequests: List[ToolRequest] = Field(default_factory=list)
    humanRequest: Optional[HumanRequest] = None


class AgentTurnLog(BaseModel):
    turnIndex: int
    phase: str
    decisionType: Optional[str] = None
    loadedSkillsDelta: int = 0
    toolCallsDelta: int = 0
    routeSource: str = ""
    failureReason: str = ""


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
    pauseReason: Optional[PauseReasonSnapshot] = None
    nodes: List[NodeSnapshot]
    toolCalls: List[ToolInvocationSnapshot]
    escalationRequired: bool
    latestToolOutcome: Optional[ToolOutcomeSummary] = None
    loadedSkillResourceVersionIds: List[str] = Field(default_factory=list)


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
    retrieval_hits: List[Dict[str, Any]]
    retrieval_cache: Dict[str, List[Dict[str, Any]]]
    tool_history: List[Dict[str, Any]]
    tool_calls: List[Dict[str, Any]]
    node_snapshots: List[Dict[str, Any]]
    human_task: Optional[Dict[str, Any]]
    checkpoint: Optional[Dict[str, Any]]
    escalation_required: bool
    latest_tool_outcome: Optional[Dict[str, Any]]
    human_input: Optional[Dict[str, Any]]
    resume_count: int
    agent_turn_state: Dict[str, Any]
    pause_reason: Optional[Dict[str, Any]]
    workflow_status: str


def configure_runtime_logger() -> logging.Logger:
    logger = logging.getLogger("lynxus.agent_runtime")
    level_name = os.getenv("LYNXUS_AGENT_RUNTIME_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    logger.setLevel(level)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s"))
        logger.addHandler(handler)
    logger.propagate = False
    return logger


app = FastAPI(title="lynxus-agent-runtime", version="1.0.0")
logger = configure_runtime_logger()
LLM_REQUEST_TIMEOUT_SECONDS = 30
AGENT_MAX_TURNS = 6
AGENT_DECISION_FINAL = "FINAL"
AGENT_DECISION_TOOL_CALL = "TOOL_CALL"
AGENT_DECISION_SKILL_READ = "SKILL_READ"
AGENT_DECISION_HUMAN_HANDOFF = "HUMAN_HANDOFF"
GRAPH_HUMAN_TASK_SOURCE = "GRAPH_NODE"
AGENT_HUMAN_TASK_SOURCE = "AGENT_REQUEST"
HUMAN_ACTION_CONFIRM = "CONFIRM"
HUMAN_ACTION_TERMINATE = "TERMINATE"
DEFAULT_HUMAN_ACTIONS = [HUMAN_ACTION_CONFIRM, HUMAN_ACTION_TERMINATE]


class AgentTurnError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def next_id(prefix: str) -> str:
    return f"{prefix}-{abs(hash((prefix, time.time_ns()))) % 1_000_000:06d}"


def normalize_human_action(action: Optional[str]) -> str:
    return (action or "").strip().upper()


def allowed_human_actions(task: Optional[Dict[str, Any]]) -> List[str]:
    if not isinstance(task, dict):
        return list(DEFAULT_HUMAN_ACTIONS)
    actions = task.get("allowedActions")
    if not isinstance(actions, list) or not actions:
        return list(DEFAULT_HUMAN_ACTIONS)
    return [normalize_human_action(item) for item in actions if str(item).strip()]


def find_node_name(graph: GraphSnapshot, node_key: Optional[str]) -> str:
    if not node_key:
        return "人工处理中"
    for node in graph.nodes:
        if node.nodeKey == node_key:
            return node.nodeName
    return node_key


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
        if sum(1 for edge in node_edges if edge.defaultEdge) > 1:
            raise HTTPException(status_code=400, detail=f"node has multiple default edges: {node.nodeKey}")
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


def resolve_knowledge_binding(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[KnowledgeBindingSnapshot]:
    if not agent.executionPolicy.ragEnabled:
        return None
    if agent.executionPolicy.inheritAssistantKnowledge:
        return assistant.assistantKnowledge
    return agent.executionPolicy.knowledge or assistant.assistantKnowledge


def resolve_tool_resources(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> List[ResourceVersionSnapshot]:
    tools = []
    for resource_version_id in agent.executionPolicy.toolResourceVersionIds:
        resource = resolve_resource(assistant, resource_version_id)
        if resource:
            tools.append(resource)
    return tools


def resolve_skill_resources(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> List[ResourceVersionSnapshot]:
    skills = []
    for resource_version_id in agent.executionPolicy.skillResourceVersionIds:
        resource = resolve_resource(assistant, resource_version_id)
        if resource and resource.configuration.skill:
            skills.append(resource)
    return skills


def memory_window_for_agent(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> int:
    if not assistant.assistantPolicy.memoryEnabled:
        return 0
    if agent.executionPolicy.memoryWindowSize > 0:
        return agent.executionPolicy.memoryWindowSize
    return max(assistant.assistantPolicy.memoryWindowSize, 0)


def build_conversation_history(session_context: Dict[str, Any], question: str, window_size: int) -> str:
    if window_size <= 0:
        return ""
    raw_history = session_context.get("history", [])
    if not isinstance(raw_history, list):
        return ""

    history_entries: List[tuple[str, str, str]] = []
    for item in raw_history:
        if not isinstance(item, dict):
            continue
        content = str(item.get("content", "")).strip()
        if not content:
            continue
        role = str(item.get("role", "UNKNOWN")).strip().upper() or "UNKNOWN"
        sender_name = str(item.get("senderName", "")).strip() or role
        history_entries.append((role, sender_name, content))

    if history_entries and history_entries[-1][0] == "USER" and history_entries[-1][2] == question.strip():
        history_entries = history_entries[:-1]

    if not history_entries:
        return ""
    history_lines = [f"[{role}] {sender_name}: {content}" for role, sender_name, content in history_entries[-window_size:]]
    return "\n".join(history_lines)


def loaded_skill_version_ids(session_context: Dict[str, Any]) -> List[str]:
    loaded = session_context.get("loadedSkillResourceVersionIds", [])
    if not isinstance(loaded, list):
        return []
    normalized: List[str] = []
    for item in loaded:
        value = str(item).strip()
        if value and value not in normalized:
            normalized.append(value)
    return normalized


def available_skill_catalog(skill_resources: List[ResourceVersionSnapshot]) -> List[Dict[str, str]]:
    catalog: List[Dict[str, str]] = []
    for resource in skill_resources:
        skill = resource.configuration.skill
        if skill is None:
            continue
        catalog.append(
            {
                "skillResourceId": resource.resourceId,
                "skillResourceVersionId": resource.resourceVersionId,
                "skillName": skill.skillName,
                "skillDesc": skill.skillDesc,
            }
        )
    return catalog


def loaded_skill_details(skill_resources: List[ResourceVersionSnapshot], session_context: Dict[str, Any]) -> List[Dict[str, str]]:
    loaded = set(loaded_skill_version_ids(session_context))
    details: List[Dict[str, str]] = []
    for resource in skill_resources:
        if resource.resourceVersionId not in loaded:
            continue
        skill = resource.configuration.skill
        if skill is None:
            continue
        details.append(
            {
                "skillResourceId": resource.resourceId,
                "skillResourceVersionId": resource.resourceVersionId,
                "skillName": skill.skillName,
                "skillPrompt": skill.skillPrompt,
            }
        )
    return details


def format_default_user_prompt(
    question: str,
    conversation_history: str,
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    knowledge_context: List[Dict[str, Any]],
    tool_results: List[Dict[str, Any]],
    human_input: Optional[Dict[str, Any]],
) -> str:
    sections = [f"用户问题：\n{question}"]
    if conversation_history:
        sections.append(f"会话记忆：\n{conversation_history}")
    if available_skills:
        sections.append(f"可用技能目录：\n{json.dumps(available_skills, ensure_ascii=False, indent=2)}")
    if loaded_skills:
        sections.append(f"已加载技能详情：\n{json.dumps(loaded_skills, ensure_ascii=False, indent=2)}")
    if knowledge_context:
        sections.append(f"知识召回结果：\n{json.dumps(knowledge_context, ensure_ascii=False, indent=2)}")
    if tool_results:
        sections.append(f"工具结果：\n{json.dumps(tool_results, ensure_ascii=False, indent=2)}")
    if human_input:
        sections.append(f"人工输入：\n{json.dumps(human_input, ensure_ascii=False, indent=2)}")
    return "\n\n".join(sections)


def available_route_keys(graph: GraphSnapshot, node_key: str) -> List[str]:
    return [edge.routeKey for edge in graph.edges if edge.sourceNodeKey == node_key and edge.routeKey]


def available_routes_for_prompt(graph: GraphSnapshot, node_key: str) -> List[Dict[str, Any]]:
    routes: List[Dict[str, Any]] = []
    for edge in graph.edges:
        if edge.sourceNodeKey != node_key or not edge.routeKey:
            continue
        routes.append(
            {
                "routeKey": edge.routeKey,
                "label": edge.label,
                "targetNodeKey": edge.targetNodeKey,
                "defaultEdge": edge.defaultEdge,
            }
        )
    return routes


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
    question: str,
    conversation_history: str,
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    knowledge_context: List[Dict[str, Any]],
    tool_results: List[Dict[str, Any]],
    human_input: Optional[Dict[str, Any]],
    tool_resources: List[ResourceVersionSnapshot],
    routes: List[Dict[str, Any]],
    loop_index: int,
) -> str:
    base_prompt = format_default_user_prompt(
        question,
        conversation_history,
        available_skills,
        loaded_skills,
        knowledge_context,
        tool_results,
        human_input,
    )
    tool_catalog = tool_catalog_for_prompt(tool_resources)
    response_schema = {
        "decisionType": f"{AGENT_DECISION_FINAL} | {AGENT_DECISION_TOOL_CALL} | {AGENT_DECISION_SKILL_READ} | {AGENT_DECISION_HUMAN_HANDOFF}",
        "message": "string; if decisionType=FINAL, this must be the user-facing final reply; otherwise summarize the current decision or immediate next step",
        "routeDecision": "string | null; required only when FINAL and no unique default route exists",
        "skillReads": ["skillResourceVersionId; only when decisionType=SKILL_READ or TOOL_CALL"],
        "toolRequests": [
            {
                "toolResourceVersionId": "string",
                "operation": "string",
                "arguments": {"key": "value; only when decisionType=TOOL_CALL"},
            }
        ],
        "humanRequest": {
            "title": "string; only when decisionType=HUMAN_HANDOFF",
            "instruction": "string; only when decisionType=HUMAN_HANDOFF",
            "expectedAction": "string; only when decisionType=HUMAN_HANDOFF",
        },
    }
    guidance = {
        "loopIndex": loop_index,
        "availableRoutes": routes,
        "availableTools": tool_catalog,
        "decisionSemantics": {
            AGENT_DECISION_FINAL: {
                "whenToUse": "You already have enough information to finish this node.",
                "must": ["Return the user-facing answer in message."],
                "mustNot": ["Do not include skillReads.", "Do not include toolRequests.", "Do not include humanRequest."],
            },
            AGENT_DECISION_SKILL_READ: {
                "whenToUse": "You need one or more skill prompts before you can continue reasoning.",
                "must": ["Populate skillReads with the needed skillResourceVersionIds."],
                "mustNot": ["Do not include toolRequests.", "Do not include humanRequest.", "Do not pretend the task is finished."],
            },
            AGENT_DECISION_TOOL_CALL: {
                "whenToUse": "You need one or more tool calls before you can finish.",
                "must": ["Populate toolRequests.", "You may also include skillReads if the tool decision depends on new skill details."],
                "mustNot": ["Do not include humanRequest."],
            },
            AGENT_DECISION_HUMAN_HANDOFF: {
                "whenToUse": "A human must continue because automation is insufficient, unsafe, or blocked.",
                "must": ["Populate humanRequest with a concrete title, instruction, and expectedAction."],
                "mustNot": ["Do not include skillReads.", "Do not include toolRequests.", "Do not pretend the task is finished."],
            },
        },
        "outputRules": [
            "Only request tools listed in availableTools.",
            "Use skillReads to request needed skill details from the skill catalog in the prompt.",
            f"Use {AGENT_DECISION_SKILL_READ} for skill-only continuation turns.",
            f"Use {AGENT_DECISION_TOOL_CALL} for tool continuation turns.",
            f"Use {AGENT_DECISION_FINAL} only for a completed answer.",
            f"Use {AGENT_DECISION_HUMAN_HANDOFF} only when a human must take over.",
            "Return JSON only.",
        ],
    }
    return (
        f"{base_prompt}\n\n"
        f"决策规则与可用路由/工具：\n{json.dumps(guidance, ensure_ascii=False, indent=2)}\n\n"
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
        logger.info(f"llm request with sys prompt: \n{system_prompt}")
        logger.info(f"user prompt:\n{prompt}")
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
                        "enable_thinking": False
                    },
                )
                response.raise_for_status()
                reponse_json = response.json()
                logger.info(f"llm reponse:\n{json.dumps(reponse_json, indent=2)}")
                return reponse_json["choices"][0]["message"]["content"]

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


async def retrieve_knowledge(binding: Optional[KnowledgeBindingSnapshot], question: str) -> List[Dict[str, Any]]:
    if binding is None:
        return []
    if not binding.snapshotId.strip():
        return []
    knowledge_service_base_url = os.getenv("LYNXUS_KNOWLEDGE_SERVICE_BASE_URL", "http://localhost:8091").rstrip("/")
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.post(
            f"{knowledge_service_base_url}/internal/retrieve",
            json={
                "indexSnapshotId": binding.snapshotId,
                "query": question,
                "topK": binding.defaultTopK,
                "minScore": binding.minScore,
                "retrievalMode": binding.retrievalMode,
            },
        )
        response.raise_for_status()
    payload = response.json()
    if payload.get("lowConfidence"):
        return []
    return payload.get("hits", [])


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
    latest_tool_record = state["tool_history"][-1] if state["tool_history"] else {}
    latest_outcome = latest_tool_record.get("normalizedOutcome", {}) if isinstance(latest_tool_record, dict) else {}
    return {
        "question": state["question"],
        "knowledgeHits": hits,
        "operator": human_input.get("operatorId", ""),
        "comment": human_input.get("comment", ""),
        "ticketId": latest_outcome.get("externalReference", ""),
        "externalReference": latest_outcome.get("externalReference", ""),
        "recommendedAction": latest_outcome.get("recommendedAction", ""),
    }


def tool_history_for_prompt(state: AgentState) -> List[Dict[str, Any]]:
    prompt_rows: List[Dict[str, Any]] = []
    for item in state["tool_history"]:
        if not isinstance(item, dict):
            continue
        outcome = item.get("normalizedOutcome", {})
        prompt_rows.append(
            {
                "toolResourceVersionId": item.get("toolResourceVersionId", ""),
                "toolResourceName": item.get("toolResourceName", ""),
                "operation": item.get("operation", ""),
                "status": item.get("status", ""),
                "externalReference": outcome.get("externalReference", ""),
                "recommendedAction": outcome.get("recommendedAction", ""),
                "detail": outcome.get("detail", ""),
            }
        )
    return prompt_rows


def default_route_key(graph: GraphSnapshot, node_key: str) -> Optional[str]:
    defaults = [edge.routeKey for edge in graph.edges if edge.sourceNodeKey == node_key and edge.defaultEdge and edge.routeKey]
    if len(defaults) == 1:
        return defaults[0]
    return None


def resolve_route_or_raise(graph: GraphSnapshot, node_key: str, route_key: Optional[str], failure_code: str) -> tuple[str, str]:
    route_keys = set(available_route_keys(graph, node_key))
    if route_key:
        if route_key not in route_keys:
            raise AgentTurnError(failure_code, f"invalid routeDecision={route_key} for node {node_key}")
        return route_key, "explicit"
    default_key = default_route_key(graph, node_key)
    if default_key:
        return default_key, "default"
    raise AgentTurnError(failure_code, f"missing routeDecision for node {node_key} without unique default edge")


def normalize_tool_requests(
    raw_requests: Any,
    tool_resources: List[ResourceVersionSnapshot],
    base_payload: Dict[str, Any],
) -> List[ToolRequest]:
    by_version_id = {resource.resourceVersionId: resource for resource in tool_resources}
    if not isinstance(raw_requests, list):
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "toolRequests must be a list")
    normalized: List[ToolRequest] = []
    for raw_request in raw_requests:
        if not isinstance(raw_request, dict):
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests items must be objects")
        resource_version_id = str(raw_request.get("toolResourceVersionId", "")).strip()
        operation = str(raw_request.get("operation", "")).strip()
        arguments = raw_request.get("arguments", {})
        if resource_version_id not in by_version_id:
            raise AgentTurnError("TOOL_REQUEST_INVALID", f"unknown toolResourceVersionId={resource_version_id}")
        if not operation:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests.operation is required")
        if not isinstance(arguments, dict):
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests.arguments must be an object")
        resolve_tool_operation(by_version_id[resource_version_id], operation)
        normalized.append(
            ToolRequest(
                toolResourceVersionId=resource_version_id,
                operation=operation,
                arguments={**base_payload, **arguments},
            )
        )
    return normalized


def normalize_skill_reads(raw_skill_reads: Any, skill_resources: List[ResourceVersionSnapshot]) -> List[str]:
    if raw_skill_reads is None:
        return []
    if not isinstance(raw_skill_reads, list):
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "skillReads must be a list")
    allowed = {resource.resourceVersionId for resource in skill_resources}
    normalized: List[str] = []
    for item in raw_skill_reads:
        resource_version_id = str(item).strip()
        if not resource_version_id:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "skillReads items must be non-empty strings")
        if resource_version_id not in allowed:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"unknown skillResourceVersionId={resource_version_id}")
        if resource_version_id not in normalized:
            normalized.append(resource_version_id)
    return normalized


def parse_agent_structured_response(
    llm_output: str,
    graph: GraphSnapshot,
    node: GraphNodeSnapshot,
    agent: AgentSnapshot,
    skill_resources: List[ResourceVersionSnapshot],
    tool_resources: List[ResourceVersionSnapshot],
    state: AgentState,
    hits: List[str],
) -> AgentStructuredResponse:
    del agent
    payload = build_tool_payload(state, hits)
    parsed = extract_json_object(llm_output)
    if parsed is None:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "model output must be a JSON object")

    decision_type = str(parsed.get("decisionType", "")).strip().upper()
    if decision_type not in {AGENT_DECISION_FINAL, AGENT_DECISION_TOOL_CALL, AGENT_DECISION_SKILL_READ, AGENT_DECISION_HUMAN_HANDOFF}:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", f"unsupported decisionType={decision_type or '<empty>'}")

    route_decision = parsed.get("routeDecision")
    if route_decision is not None:
        route_decision = str(route_decision).strip() or None
        route_keys = set(available_route_keys(graph, node.nodeKey))
        if route_decision and route_decision not in route_keys:
            raise AgentTurnError("ROUTE_INVALID", f"invalid routeDecision={route_decision} for node {node.nodeKey}")

    skill_reads = normalize_skill_reads(parsed.get("skillReads", []), skill_resources)
    tool_requests = normalize_tool_requests(parsed.get("toolRequests", []), tool_resources, payload)
    raw_human_request = parsed.get("humanRequest")
    human_request = HumanRequest.model_validate(raw_human_request) if raw_human_request is not None else None

    if decision_type == AGENT_DECISION_FINAL:
        if tool_requests:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "FINAL decisionType must not include toolRequests")
        if skill_reads:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "FINAL decisionType must not include skillReads")
    if decision_type == AGENT_DECISION_TOOL_CALL and not tool_requests:
        raise AgentTurnError("TOOL_REQUEST_INVALID", "TOOL_CALL decisionType requires toolRequests")
    if decision_type == AGENT_DECISION_SKILL_READ:
        if not skill_reads:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "SKILL_READ decisionType requires skillReads")
        if tool_requests:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "SKILL_READ decisionType must not include toolRequests")
        if human_request is not None:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "SKILL_READ decisionType must not include humanRequest")
    if decision_type == AGENT_DECISION_HUMAN_HANDOFF:
        if human_request is None:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "HUMAN_HANDOFF decisionType requires humanRequest")
        if tool_requests:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "HUMAN_HANDOFF decisionType must not include toolRequests")
        if skill_reads:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", "HUMAN_HANDOFF decisionType must not include skillReads")

    return AgentStructuredResponse(
        decisionType=decision_type,
        message=str(parsed.get("message", "")).strip(),
        routeDecision=route_decision,
        skillReads=skill_reads,
        toolRequests=tool_requests,
        humanRequest=human_request,
    )


def store_tool_result(
    state: AgentState,
    agent: AgentSnapshot,
    resource: ResourceVersionSnapshot,
    operation: ToolOperationConfig,
    arguments: Dict[str, Any],
    result: Dict[str, Any],
) -> None:
    normalized_outcome = build_tool_outcome(resource, operation, result)
    state["tool_history"].append(
        {
            "agentId": agent.agentId,
            "toolResourceId": resource.resourceId,
            "toolResourceVersionId": resource.resourceVersionId,
            "toolResourceName": resource.resourceName,
            "operation": operation.name,
            "arguments": arguments,
            "rawResult": result,
            "normalizedOutcome": normalized_outcome,
            "status": normalized_outcome["status"],
            "createdAt": now_iso(),
        }
    )
    state["latest_tool_outcome"] = normalized_outcome


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
        "tool_history": state["tool_history"],
        "tool_calls": state["tool_calls"],
        "node_snapshots": state["node_snapshots"],
        "human_task": state["human_task"],
        "latest_tool_outcome": state["latest_tool_outcome"],
        "escalation_required": state["escalation_required"],
        "resume_count": state["resume_count"],
        "agent_turn_state": state["agent_turn_state"],
        "pause_reason": state["pause_reason"],
        "workflow_status": state["workflow_status"],
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
        "tool_history": data.get("tool_history", []),
        "tool_calls": data.get("tool_calls", []),
        "node_snapshots": data.get("node_snapshots", []),
        "human_task": None,
        "checkpoint": None,
        "escalation_required": data.get("escalation_required", False),
        "latest_tool_outcome": data.get("latest_tool_outcome"),
        "human_input": resume_request.action.model_dump(mode="json"),
        "resume_count": resume_request.checkpoint.resumeCount + 1,
        "agent_turn_state": data.get("agent_turn_state", {"phase": "IDLE", "turnIndex": 0, "turnLogs": []}),
        "pause_reason": data.get("pause_reason"),
        "workflow_status": "RUNNING",
    }


def resolve_next_node(graph: GraphSnapshot, source_node_key: str, route_key: Optional[str]) -> Optional[str]:
    candidates = [edge for edge in graph.edges if edge.sourceNodeKey == source_node_key]
    if not candidates:
        return None
    if route_key:
        for edge in candidates:
            if edge.routeKey == route_key:
                return edge.targetNodeKey
        raise HTTPException(status_code=500, detail=f"route not found for node {source_node_key}: {route_key}")
    defaults = [edge for edge in candidates if edge.defaultEdge]
    if len(defaults) == 1:
        return defaults[0].targetNodeKey
    raise HTTPException(status_code=500, detail=f"node {source_node_key} requires an explicit route decision")


def build_system_prompt(agent: AgentSnapshot) -> str:
    platform_rules = [
        "你是企业级智能体执行节点，可能扮演不同角色。",
        "只依据给定上下文、技能、工具结果和路由约束行动。不要回答无关问题。",
        "如需读取技能详情，请使用 skillReads 请求，不要自行臆造技能内容。",
        "如需外部能力，请仅请求 availableTools 中声明的工具。",
    ]
    agent_prompt = agent.executionPolicy.systemPrompt.strip()
    return "\n".join([*platform_rules, *([agent_prompt] if agent_prompt else [])]).strip()


def merge_loaded_skills(session_context: Dict[str, Any], requested_skill_ids: List[str]) -> List[str]:
    current = loaded_skill_version_ids(session_context)
    merged = list(current)
    for resource_version_id in requested_skill_ids:
        if resource_version_id not in merged:
            merged.append(resource_version_id)
    session_context["loadedSkillResourceVersionIds"] = merged
    return merged


def turn_log_line(turn_log: Dict[str, Any]) -> str:
    parts = [
        f"turn={turn_log.get('turnIndex', 0)}",
        f"phase={turn_log.get('phase', '')}",
    ]
    if turn_log.get("decisionType"):
        parts.append(f"decision={turn_log['decisionType']}")
    if turn_log.get("loadedSkillsDelta"):
        parts.append(f"loaded_skills_delta={turn_log['loadedSkillsDelta']}")
    if turn_log.get("toolCallsDelta"):
        parts.append(f"tool_calls_delta={turn_log['toolCallsDelta']}")
    if turn_log.get("routeSource"):
        parts.append(f"route_source={turn_log['routeSource']}")
    if turn_log.get("failureReason"):
        parts.append(f"failure_reason={turn_log['failureReason']}")
    return " ".join(parts)


def log_turn_failure(workflow_instance_id: str, node_key: str, turn_index: int, failure_code: str, detail: str) -> None:
    logger.warning(
        "workflow %s node %s turn %s failed code=%s detail=%s",
        workflow_instance_id,
        node_key,
        turn_index,
        failure_code,
        detail,
    )


def pause_for_human(
    state: AgentState,
    node_key: str,
    node_name: str,
    title: str,
    instruction: str,
    expected_action: str,
    source: str,
    resume_node_key: str,
    detail: str,
    allowed_actions: Optional[List[str]] = None,
    reason_code: str = "",
    reason_detail: str = "",
) -> None:
    state["current_node_key"] = node_key
    state["human_task"] = {
        "nodeKey": node_key,
        "title": title,
        "instruction": instruction,
        "expectedAction": expected_action,
        "source": source,
        "allowedActions": allowed_actions or list(DEFAULT_HUMAN_ACTIONS),
    }
    state["escalation_required"] = True
    state["workflow_status"] = "WAITING_HUMAN"
    state["summary"] = instruction or state["summary"] or "等待人工处理。"
    state["pause_reason"] = {
        "code": reason_code,
        "detail": reason_detail or instruction or detail,
        "source": source,
    }
    state["checkpoint"] = {
        "checkpointId": next_id("checkpoint"),
        "currentNodeKey": resume_node_key,
        "waitingNodeKey": node_key,
        "statePayload": json.dumps(export_state(state), ensure_ascii=False),
        "resumeCount": state["resume_count"],
    }
    state["next_node_key"] = "__end__"
    append_node(state, node_key, node_name, detail or state["summary"], status="WAITING_HUMAN")


def cancel_workflow_from_human_action(
    state: AgentState,
    graph: GraphSnapshot,
    waiting_node_key: Optional[str],
    action: HumanAction,
) -> None:
    node_key = waiting_node_key or state["current_node_key"] or "workflow-cancelled"
    node_name = find_node_name(graph, node_key)
    detail = action.comment or "人工终止了当前流程。"
    state["current_node_key"] = node_key
    state["next_node_key"] = "__end__"
    state["human_task"] = None
    state["checkpoint"] = None
    state["pause_reason"] = None
    state["escalation_required"] = False
    state["workflow_status"] = "CANCELLED"
    state["summary"] = "人工终止了当前流程。"
    state["final_reply"] = "当前流程已由人工终止。"
    append_node(state, node_key, node_name, detail, status="CANCELLED")


async def execute_agent_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    assistant = assistant_from_state(state)
    graph = graph_from_state(state)
    agent = find_agent(assistant, node.agentId)
    knowledge_binding = resolve_knowledge_binding(assistant, agent)
    skill_resources = resolve_skill_resources(assistant, agent)
    conversation_history = build_conversation_history(
        state["session_context"],
        state["question"],
        memory_window_for_agent(assistant, agent),
    )
    hits = await retrieve_knowledge(knowledge_binding, state["question"]) if knowledge_binding else []
    state["retrieval_hits"] = hits
    state["retrieval_cache"][agent.agentId] = hits

    model_resource = resolve_model_resource(assistant, agent)
    system_prompt = build_system_prompt(agent)
    tool_resources = resolve_tool_resources(assistant, agent)
    detail_lines = [agent.responsibility]
    route_key: Optional[str] = None
    route_source = ""
    final_message = ""
    turn_logs: List[Dict[str, Any]] = []
    state["agent_turn_state"] = {
        "phase": "PREPARE_CONTEXT",
        "turnIndex": 0,
        "turnLogs": turn_logs,
    }

    for turn_index in range(1, AGENT_MAX_TURNS + 1):
        state["agent_turn_state"]["phase"] = "PREPARE_CONTEXT"
        state["agent_turn_state"]["turnIndex"] = turn_index
        available_skills = available_skill_catalog(skill_resources)
        loaded_skills = loaded_skill_details(skill_resources, state["session_context"])
        prompt = build_structured_agent_prompt(
            state["question"],
            conversation_history,
            available_skills,
            loaded_skills,
            hits,
            tool_history_for_prompt(state),
            state["human_input"],
            tool_resources,
            available_routes_for_prompt(graph, node.nodeKey),
            turn_index - 1,
        )
        state["agent_turn_state"]["phase"] = "CALL_MODEL"
        llm_output = await call_llm(model_resource, prompt, system_prompt)
        state["agent_turn_state"]["phase"] = "VALIDATE_RESPONSE"
        try:
            structured = parse_agent_structured_response(llm_output, graph, node, agent, skill_resources, tool_resources, state, hits)
        except AgentTurnError as exc:
            turn_log = AgentTurnLog(
                turnIndex=turn_index,
                phase="FAIL",
                failureReason=exc.code,
            ).model_dump(mode="json")
            turn_logs.append(turn_log)
            state["agent_turn_state"]["turnLogs"] = turn_logs
            detail_lines.append(turn_log_line(turn_log))
            log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, exc.code, exc.message)
            pause_for_human(
                state,
                node.nodeKey,
                node.nodeName,
                "需要人工介入",
                f"{exc.code}: {exc.message}",
                "请人工确认后继续处理",
                AGENT_HUMAN_TASK_SOURCE,
                node.nodeKey,
                "\n".join([*detail_lines, f"failure={exc.code}", exc.message]),
                allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                reason_code=exc.code,
                reason_detail=exc.message,
            )
            return

        turn_log = AgentTurnLog(
            turnIndex=turn_index,
            phase="VALIDATE_RESPONSE",
            decisionType=structured.decisionType,
        )
        if structured.message:
            final_message = structured.message

        if structured.skillReads:
            state["agent_turn_state"]["phase"] = "APPLY_SKILL_READS"
            before = set(loaded_skill_version_ids(state["session_context"]))
            after = merge_loaded_skills(state["session_context"], structured.skillReads)
            newly_loaded = [item for item in after if item not in before]
            turn_log.loadedSkillsDelta = len(newly_loaded)

        if structured.decisionType == AGENT_DECISION_SKILL_READ:
            turn_log.phase = "APPLY_SKILL_READS"
            turn_logs.append(turn_log.model_dump(mode="json"))
            state["agent_turn_state"]["turnLogs"] = turn_logs
            detail_lines.append(turn_log_line(turn_logs[-1]))
            if turn_index >= AGENT_MAX_TURNS:
                log_turn_failure(
                    state["workflow_instance_id"],
                    node.nodeKey,
                    turn_index,
                    "MAX_TURNS_EXCEEDED",
                    f"agent exceeded max turns={AGENT_MAX_TURNS}",
                )
                pause_for_human(
                    state,
                    node.nodeKey,
                    node.nodeName,
                    "达到最大执行轮次",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    "请人工确认上下文后继续处理",
                    AGENT_HUMAN_TASK_SOURCE,
                    node.nodeKey,
                    "\n".join(detail_lines),
                    allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                    reason_code="MAX_TURNS_EXCEEDED",
                    reason_detail=f"agent exceeded max turns={AGENT_MAX_TURNS}",
                )
                return
            continue

        if structured.decisionType == AGENT_DECISION_HUMAN_HANDOFF:
            state["agent_turn_state"]["phase"] = "PAUSE_FOR_HUMAN"
            turn_log.phase = "PAUSE_FOR_HUMAN"
            turn_logs.append(turn_log.model_dump(mode="json"))
            state["agent_turn_state"]["turnLogs"] = turn_logs
            detail_lines.append(turn_log_line(turn_logs[-1]))
            human_request = structured.humanRequest or HumanRequest(
                title="需要人工介入",
                instruction="请人工继续处理当前会话",
                expectedAction="补充处理意见",
            )
            pause_for_human(
                state,
                node.nodeKey,
                node.nodeName,
                human_request.title or "需要人工介入",
                human_request.instruction or "请人工继续处理当前会话",
                human_request.expectedAction or "补充处理意见",
                AGENT_HUMAN_TASK_SOURCE,
                node.nodeKey,
                "\n".join(detail_lines),
                allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                reason_code="HUMAN_HANDOFF_REQUESTED",
                reason_detail=human_request.instruction or structured.message,
            )
            return

        if structured.decisionType == AGENT_DECISION_TOOL_CALL:
            state["agent_turn_state"]["phase"] = "EXECUTE_TOOL_REQUESTS"
            for tool_request in structured.toolRequests:
                tool_resource = next(
                    (resource for resource in tool_resources if resource.resourceVersionId == tool_request.toolResourceVersionId),
                    None,
                )
                if tool_resource is None:
                    raise HTTPException(status_code=500, detail=f"tool resource not found: {tool_request.toolResourceVersionId}")
                try:
                    operation, tool_result = await call_tool(tool_resource, tool_request.operation, tool_request.arguments)
                    store_tool_result(state, agent, tool_resource, operation, tool_request.arguments, tool_result)
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, operation.name, "COMPLETED", json.dumps(tool_result, ensure_ascii=False))
                    turn_log.toolCallsDelta += 1
                    tool_route_key = str(tool_result.get("routeKey", "")).strip()
                    if tool_route_key:
                        route_key, route_source = resolve_route_or_raise(graph, node.nodeKey, tool_route_key, "ROUTE_INVALID")
                except AgentTurnError as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", exc.message)
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = exc.code
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, exc.code, exc.message)
                    pause_for_human(
                        state,
                        node.nodeKey,
                        node.nodeName,
                        "工具路由异常，需要人工介入",
                        f"{exc.code}: {exc.message}",
                        "请人工确认工具结果并继续处理",
                        AGENT_HUMAN_TASK_SOURCE,
                        node.nodeKey,
                        "\n".join([*detail_lines, f"tool_error={exc.message}"]),
                        allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                        reason_code=exc.code,
                        reason_detail=exc.message,
                    )
                    return
                except Exception as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", str(exc))
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = f"TOOL_REQUEST_INVALID:{tool_request.operation}"
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(
                        state["workflow_instance_id"],
                        node.nodeKey,
                        turn_index,
                        "TOOL_REQUEST_INVALID",
                        str(exc),
                    )
                    pause_for_human(
                        state,
                        node.nodeKey,
                        node.nodeName,
                        "工具执行失败，需要人工介入",
                        f"工具调用失败：{exc}",
                        "请人工确认工具结果并继续处理",
                        AGENT_HUMAN_TASK_SOURCE,
                        node.nodeKey,
                        "\n".join([*detail_lines, f"tool_error={exc}"]),
                        allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                        reason_code="TOOL_REQUEST_INVALID",
                        reason_detail=str(exc),
                    )
                    return
            turn_log.phase = "EXECUTE_TOOL_REQUESTS"
            if route_source:
                turn_log.routeSource = route_source
            turn_logs.append(turn_log.model_dump(mode="json"))
            state["agent_turn_state"]["turnLogs"] = turn_logs
            detail_lines.append(turn_log_line(turn_logs[-1]))
            if turn_index >= AGENT_MAX_TURNS:
                log_turn_failure(
                    state["workflow_instance_id"],
                    node.nodeKey,
                    turn_index,
                    "MAX_TURNS_EXCEEDED",
                    f"agent exceeded max turns={AGENT_MAX_TURNS}",
                )
                pause_for_human(
                    state,
                    node.nodeKey,
                    node.nodeName,
                    "达到最大执行轮次",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    "请人工确认上下文后继续处理",
                    AGENT_HUMAN_TASK_SOURCE,
                    node.nodeKey,
                    "\n".join(detail_lines),
                    allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                    reason_code="MAX_TURNS_EXCEEDED",
                    reason_detail=f"agent exceeded max turns={AGENT_MAX_TURNS}",
                )
                return
            continue

        state["agent_turn_state"]["phase"] = "FINALIZE"
        try:
            if structured.routeDecision:
                route_key, route_source = resolve_route_or_raise(graph, node.nodeKey, structured.routeDecision, "ROUTE_INVALID")
            elif route_key:
                route_key, route_source = resolve_route_or_raise(graph, node.nodeKey, route_key, "ROUTE_INVALID")
            else:
                route_key, route_source = resolve_route_or_raise(graph, node.nodeKey, None, "ROUTE_INVALID")
        except AgentTurnError as exc:
            turn_log.phase = "FAIL"
            turn_log.failureReason = exc.code
            turn_logs.append(turn_log.model_dump(mode="json"))
            state["agent_turn_state"]["turnLogs"] = turn_logs
            detail_lines.append(turn_log_line(turn_logs[-1]))
            log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, exc.code, exc.message)
            pause_for_human(
                state,
                node.nodeKey,
                node.nodeName,
                "路由决策异常，需要人工介入",
                f"{exc.code}: {exc.message}",
                "请人工确认流转路由并继续处理",
                AGENT_HUMAN_TASK_SOURCE,
                node.nodeKey,
                "\n".join([*detail_lines, f"route_error={exc.message}"]),
                allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
                reason_code=exc.code,
                reason_detail=exc.message,
            )
            return
        turn_log.phase = "FINALIZE"
        turn_log.routeSource = route_source
        turn_logs.append(turn_log.model_dump(mode="json"))
        state["agent_turn_state"]["turnLogs"] = turn_logs
        detail_lines.append(turn_log_line(turn_logs[-1]))
        break
    else:
        log_turn_failure(
            state["workflow_instance_id"],
            node.nodeKey,
            AGENT_MAX_TURNS,
            "MAX_TURNS_EXCEEDED",
            f"agent exceeded max turns={AGENT_MAX_TURNS}",
        )
        pause_for_human(
            state,
            node.nodeKey,
            node.nodeName,
            "达到最大执行轮次",
            f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
            "请人工确认上下文后继续处理",
            AGENT_HUMAN_TASK_SOURCE,
            node.nodeKey,
            "\n".join(detail_lines),
            allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
            reason_code="MAX_TURNS_EXCEEDED",
            reason_detail=f"agent exceeded max turns={AGENT_MAX_TURNS}",
        )
        return

    message = final_message or agent.responsibility
    human_comment = state["human_input"]["comment"] if state["human_input"] else ""
    latest_outcome = state["latest_tool_outcome"] or {}
    suggestion = str(latest_outcome.get("detail", ""))
    human_suffix = f"人工处理说明：{human_comment}" if human_comment else ""
    state["final_reply"] = f"{message}\n\n{human_suffix}".strip() if human_suffix and human_suffix not in message else message
    if state["final_reply"]:
        state["summary"] = state["final_reply"]
    else:
        state["summary"] = suggestion
    detail_lines.append(f"loop_count={len(turn_logs)}")
    detail_lines.append(f"route_key={route_key}")

    state["route_key"] = route_key
    state["next_node_key"] = resolve_next_node(graph, node.nodeKey, route_key)
    state["current_node_key"] = node.nodeKey
    state["escalation_required"] = False
    state["pause_reason"] = None
    state["workflow_status"] = "COMPLETED"
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
    state["workflow_status"] = "COMPLETED"
    append_node(state, node.nodeKey, node.nodeName, state["summary"])


def execute_human_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    if node.humanNode is None:
        raise HTTPException(status_code=500, detail=f"human node missing config: {node.nodeKey}")
    next_node_key = resolve_next_node(graph_from_state(state), node.nodeKey, node.humanNode.resumeRouteKey)
    pause_for_human(
        state,
        node.nodeKey,
        node.nodeName,
        node.humanNode.title,
        node.humanNode.instruction,
        node.humanNode.expectedAction,
        GRAPH_HUMAN_TASK_SOURCE,
        next_node_key or "end",
        f"等待人工处理：{node.humanNode.instruction}",
        allowed_actions=list(DEFAULT_HUMAN_ACTIONS),
        reason_code="GRAPH_HUMAN_NODE",
        reason_detail=node.humanNode.instruction,
    )
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
    status = state.get("workflow_status") or ("WAITING_HUMAN" if state["human_task"] else "COMPLETED")
    return WorkflowResult(
        workflowInstanceId=state["workflow_instance_id"],
        status=status,
        summary=state["summary"] or state["final_reply"] or "流程已执行。",
        finalReply=state["final_reply"] or None,
        currentNodeKey=state["current_node_key"],
        checkpoint=ExecutionCheckpoint(**state["checkpoint"]) if state["checkpoint"] else None,
        humanTask=HumanTaskSnapshot(**state["human_task"]) if state["human_task"] else None,
        pauseReason=PauseReasonSnapshot(**state["pause_reason"]) if state["pause_reason"] else None,
        nodes=[NodeSnapshot(**node) for node in state["node_snapshots"]],
        toolCalls=[ToolInvocationSnapshot(**tool) for tool in state["tool_calls"]],
        escalationRequired=state["escalation_required"],
        latestToolOutcome=ToolOutcomeSummary(**state["latest_tool_outcome"]) if state["latest_tool_outcome"] else None,
        loadedSkillResourceVersionIds=loaded_skill_version_ids(state["session_context"]),
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
        "tool_history": [],
        "tool_calls": [],
        "node_snapshots": [],
        "human_task": None,
        "checkpoint": None,
        "escalation_required": False,
        "latest_tool_outcome": None,
        "human_input": None,
        "resume_count": 0,
        "agent_turn_state": {"phase": "IDLE", "turnIndex": 0, "turnLogs": []},
        "pause_reason": None,
        "workflow_status": "RUNNING",
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
    saved_human_task = payload.get("human_task")
    action = normalize_human_action(request.action.action)
    if action not in allowed_human_actions(saved_human_task):
        raise HTTPException(status_code=400, detail=f"unsupported human action: {request.action.action}")
    state = restore_state(payload, request)
    state["human_input"]["action"] = action
    if action == HUMAN_ACTION_TERMINATE:
        cancel_workflow_from_human_action(
            state,
            GraphSnapshot(**state["graph"]),
            request.checkpoint.waitingNodeKey,
            request.action.model_copy(update={"action": action}),
        )
        result = workflow_result_from_state(state)
        logger.info(
            "workflow %s resume request completed status=%s currentNode=%s summary=%s",
            request.workflowInstanceId,
            result.status,
            result.currentNodeKey,
            result.summary,
        )
        return result
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
