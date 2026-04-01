from __future__ import annotations

import json
import logging
import os
import secrets
import time
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Dict, List, Optional, TypedDict
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from lynxus_common import (
    TRACEPARENT_HEADER,
    bind_log_context,
    bind_request_log_context,
    clear_log_context,
    configure_structured_logging,
)
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

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


class SharedSessionState(BaseModel):
    facts: Dict[str, Any] = Field(default_factory=dict)
    artifacts: Dict[str, Any] = Field(default_factory=dict)
    agentScopes: Dict[str, Dict[str, Any]] = Field(default_factory=dict)

    @field_validator("facts", "artifacts", mode="before")
    @classmethod
    def normalize_object_bucket(cls, value: Any) -> Dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("shared state buckets must be objects")
        return value

    @field_validator("agentScopes", mode="before")
    @classmethod
    def normalize_agent_scopes(cls, value: Any) -> Dict[str, Dict[str, Any]]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("agentScopes must be an object")
        normalized: Dict[str, Dict[str, Any]] = {}
        for key, scope in value.items():
            agent_id = str(key).strip()
            if not agent_id:
                raise TypeError("agentScopes keys must be non-empty strings")
            if scope is None:
                normalized[agent_id] = {}
                continue
            if not isinstance(scope, dict):
                raise TypeError(f"agentScopes[{agent_id}] must be an object")
            normalized[agent_id] = scope
        return normalized


class SessionContext(BaseModel):
    sessionId: str
    customerId: str
    latestMessage: str
    history: List[SessionMessageSnapshot]
    loadedSkillResourceVersionIds: List[str] = Field(default_factory=list)
    sharedState: SharedSessionState = Field(default_factory=SharedSessionState)


class SessionStatePatchOp(BaseModel):
    target: str
    op: str
    path: List[str]
    value: Any = None

    @field_validator("target", mode="before")
    @classmethod
    def normalize_target(cls, value: Any) -> str:
        return str(value or "").strip().upper()

    @field_validator("op", mode="before")
    @classmethod
    def normalize_op(cls, value: Any) -> str:
        return str(value or "").strip().upper()

    @field_validator("path", mode="before")
    @classmethod
    def normalize_path(cls, value: Any) -> List[str]:
        if not isinstance(value, list):
            raise TypeError("sessionStatePatch.ops.path must be a list")
        normalized: List[str] = []
        for item in value:
            segment = str(item).strip()
            if not segment:
                raise TypeError("sessionStatePatch.ops.path items must be non-empty strings")
            normalized.append(segment)
        if not normalized:
            raise TypeError("sessionStatePatch.ops.path must not be empty")
        return normalized

    @model_validator(mode="after")
    def validate_semantics(self) -> "SessionStatePatchOp":
        if self.target not in {"FACTS", "ARTIFACTS", "AGENT_SCOPE"}:
            raise ValueError(f"unsupported sessionStatePatch target={self.target or '<empty>'}")
        if self.op not in {"UPSERT", "REMOVE"}:
            raise ValueError(f"unsupported sessionStatePatch op={self.op or '<empty>'}")
        if self.op == "UPSERT" and "value" not in self.model_fields_set:
            raise ValueError("sessionStatePatch UPSERT op requires value")
        return self


class SessionStatePatch(BaseModel):
    ops: List[SessionStatePatchOp] = Field(default_factory=list)


class LogContext(BaseModel):
    traceId: Optional[str] = None
    sessionId: Optional[str] = None
    workflowId: Optional[str] = None
    customerId: Optional[str] = None
    userId: Optional[str] = None


class WorkflowStartRequest(BaseModel):
    taskId: str
    workflowInstanceId: str
    scenarioId: str
    question: str
    customerId: str
    sessionContext: SessionContext
    assistant: AssistantRunSnapshot
    logContext: Optional[LogContext] = None


class ResumeAction(BaseModel):
    type: str
    source: str
    comment: str
    userId: str
    attributes: Dict[str, str] = Field(default_factory=dict)


class ResumeContextSnapshot(BaseModel):
    source: str
    reasonCode: str
    interactionTaskId: Optional[str] = None
    interactionType: Optional[str] = None
    timeoutPolicyKey: Optional[str] = None


class ExecutionCheckpoint(BaseModel):
    checkpointId: str
    currentNodeKey: Optional[str] = None
    waitingNodeKey: Optional[str] = None
    statePayload: str
    resumeContext: Optional[ResumeContextSnapshot] = None
    resumeCount: int


class WorkflowResumeRequest(BaseModel):
    taskId: str
    workflowInstanceId: str
    scenarioId: str
    action: ResumeAction
    sessionContext: SessionContext
    assistant: AssistantRunSnapshot
    checkpoint: ExecutionCheckpoint
    logContext: Optional[LogContext] = None


class ToolInvocationSnapshot(BaseModel):
    id: str
    providerType: str
    resourceId: str
    resourceName: str
    operation: str
    status: str
    detail: str
    createdAt: str


class ResumeTaskSnapshot(BaseModel):
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


class WorkflowFailureSnapshot(BaseModel):
    category: str
    code: str
    rootCause: str
    detail: str
    failedNodeKey: Optional[str] = None
    failedNodeName: Optional[str] = None
    failedResourceId: Optional[str] = None
    failedResourceName: Optional[str] = None
    occurredAt: str


class ToolOutcomeSummary(BaseModel):
    toolResourceId: str
    toolResourceName: str
    operation: str
    providerType: str
    result: Dict[str, Any] = Field(default_factory=dict)


class ToolExecutionRecord(BaseModel):
    agentId: str
    toolResourceId: str
    toolResourceVersionId: str
    toolResourceName: str
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)
    result: Dict[str, Any] = Field(default_factory=dict)
    createdAt: str


class ToolRequest(BaseModel):
    toolResourceVersionId: str
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class HumanRequest(BaseModel):
    title: str = ""
    instruction: str = ""
    expectedAction: str = ""


class StructuredAgentDecision(BaseModel):
    decisionType: str
    message: str = ""
    routeDecision: Optional[str] = None
    skillReads: List[str] = Field(default_factory=list)
    toolRequests: List[ToolRequest] = Field(default_factory=list)
    humanRequest: Optional[HumanRequest] = None
    sessionStatePatch: Optional[SessionStatePatch] = None


class AgentTurnLog(BaseModel):
    turnIndex: int
    phase: str
    decisionType: Optional[str] = None
    loadedSkillsDelta: int = 0
    sessionStateOpsDelta: int = 0
    toolCallsDelta: int = 0
    routeSource: str = ""
    failureReason: str = ""


class AgentTurnState(BaseModel):
    phase: str = "IDLE"
    turnIndex: int = 0
    latestDecision: Optional[StructuredAgentDecision] = None
    turnLogs: List[AgentTurnLog] = Field(default_factory=list)


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
    resumeTask: Optional[ResumeTaskSnapshot] = None
    pauseReason: Optional[PauseReasonSnapshot] = None
    latestFailure: Optional[WorkflowFailureSnapshot] = None
    nodes: List[NodeSnapshot]
    toolCalls: List[ToolInvocationSnapshot]
    escalationRequired: bool
    latestToolOutcome: Optional[ToolOutcomeSummary] = None
    loadedSkillResourceVersionIds: List[str] = Field(default_factory=list)
    sharedState: SharedSessionState = Field(default_factory=SharedSessionState)
    agentTurnState: AgentTurnState = Field(default_factory=AgentTurnState)


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
    resume_task: Optional[Dict[str, Any]]
    checkpoint: Optional[Dict[str, Any]]
    escalation_required: bool
    latest_tool_outcome: Optional[Dict[str, Any]]
    resume_input: Optional[Dict[str, Any]]
    resume_count: int
    agent_turn_state: Dict[str, Any]
    pause_reason: Optional[Dict[str, Any]]
    latest_failure: Optional[Dict[str, Any]]
    workflow_status: str


def configure_runtime_logger() -> logging.Logger:
    return configure_structured_logging(
        service_name="lynxus-agent-runtime",
        level_env_var="LYNXUS_AGENT_RUNTIME_LOG_LEVEL",
        logger_name="lynxus.agent_runtime",
    )


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
PAUSE_SOURCE_EXTERNAL_INTERACTION = "EXTERNAL_INTERACTION"
PAUSE_SOURCE_TIMEOUT_POLICY = "TIMEOUT_POLICY"
RESUME_SOURCE_HUMAN = "HUMAN"
RESUME_SOURCE_EXTERNAL_SYSTEM = "EXTERNAL_SYSTEM"
RESUME_SOURCE_TIMEOUT_POLICY = "TIMEOUT_POLICY"
RESUME_ACTION_CONTINUE = "CONTINUE"
RESUME_ACTION_TERMINATE = "TERMINATE"
DEFAULT_RESUME_ACTIONS = [RESUME_ACTION_CONTINUE, RESUME_ACTION_TERMINATE]
SESSION_STATE_TARGET_FACTS = "FACTS"
SESSION_STATE_TARGET_ARTIFACTS = "ARTIFACTS"
SESSION_STATE_TARGET_AGENT_SCOPE = "AGENT_SCOPE"
SESSION_STATE_OP_UPSERT = "UPSERT"
SESSION_STATE_OP_REMOVE = "REMOVE"
MAX_SESSION_STATE_PATCH_VALUE_BYTES = 16 * 1024
MAX_SHARED_SESSION_STATE_BYTES = 64 * 1024
FAILURE_CATEGORY_TIMEOUT = "TIMEOUT"
FAILURE_CATEGORY_PROVIDER = "PROVIDER_FAILURE"
FAILURE_CATEGORY_TOOL = "TOOL_FAILURE"
FAILURE_CATEGORY_PARSING = "PARSING_FAILURE"
FAILURE_CATEGORY_VALIDATION = "VALIDATION_FAILURE"
FAILURE_CATEGORY_CONFIGURATION = "CONFIGURATION_FAILURE"
FAILURE_CATEGORY_RUNTIME = "RUNTIME_FAILURE"
FAILURE_CATEGORY_UNKNOWN = "UNKNOWN"


@app.middleware("http")
async def inject_log_context(request: Request, call_next):
    traceparent = bind_request_log_context(request.headers)
    try:
        response = await call_next(request)
    finally:
        clear_log_context()
    response.headers[TRACEPARENT_HEADER] = traceparent
    return response


def internal_auth_token() -> str:
    token = os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN", "").strip()
    if not token:
        raise RuntimeError("LYNXUS_INTERNAL_AUTH_TOKEN must be configured")
    return token


def require_internal_bearer(authorization: str | None = Header(default=None)) -> None:
    expected = internal_auth_token()
    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="internal authentication is required")
    actual = authorization.removeprefix("Bearer ").strip()
    if not actual or not secrets.compare_digest(actual, expected):
        raise HTTPException(status_code=401, detail="invalid internal authentication token")


@app.on_event("startup")
async def validate_internal_auth_configuration() -> None:
    internal_auth_token()


class AgentTurnError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class WorkflowFailureError(AgentTurnError):
    def __init__(
        self,
        category: str,
        code: str,
        message: str,
        *,
        root_cause: Optional[str] = None,
        failed_resource: Optional[ResourceVersionSnapshot] = None,
    ):
        super().__init__(code, message)
        self.category = category
        self.root_cause = root_cause or message
        self.failed_resource = failed_resource


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def next_id(prefix: str) -> str:
    return f"{prefix}-{abs(hash((prefix, time.time_ns()))) % 1_000_000:06d}"


def build_failure_snapshot(
    category: str,
    code: str,
    root_cause: str,
    detail: str,
    *,
    node_key: Optional[str] = None,
    node_name: Optional[str] = None,
    resource: Optional[ResourceVersionSnapshot] = None,
) -> Dict[str, Any]:
    return WorkflowFailureSnapshot(
        category=category,
        code=code,
        rootCause=root_cause,
        detail=detail,
        failedNodeKey=node_key,
        failedNodeName=node_name,
        failedResourceId=resource.resourceId if resource else None,
        failedResourceName=resource.resourceName if resource else None,
        occurredAt=now_iso(),
    ).model_dump(mode="json")


def categorize_agent_turn_error(code: str) -> str:
    if code == "MODEL_OUTPUT_INVALID":
        return FAILURE_CATEGORY_PARSING
    if code == "TOOL_SCHEMA_INVALID":
        return FAILURE_CATEGORY_CONFIGURATION
    if code in {"TOOL_REQUEST_INVALID", "TOOL_RESPONSE_INVALID"} or code.startswith("TOOL_REQUEST_INVALID:"):
        return FAILURE_CATEGORY_VALIDATION
    if code in {"MAX_TURNS_EXCEEDED", "ROUTE_INVALID"}:
        return FAILURE_CATEGORY_RUNTIME
    return FAILURE_CATEGORY_RUNTIME


def set_latest_failure(state: AgentState, failure: Dict[str, Any]) -> None:
    state["latest_failure"] = failure


def clear_latest_failure(state: AgentState) -> None:
    state["latest_failure"] = None


def pause_agent_node_with_workflow_failure(
    state: AgentState,
    node: GraphNodeSnapshot,
    turn_logs: List[Dict[str, Any]],
    detail_lines: List[str],
    turn_index: int,
    error: WorkflowFailureError,
    title: str,
    expected_action: str,
    detail_key: str,
    resource: Optional[ResourceVersionSnapshot] = None,
) -> None:
    state["agent_turn_state"]["phase"] = "FAIL"
    state["agent_turn_state"]["turnIndex"] = turn_index
    turn_log = AgentTurnLog(
        turnIndex=turn_index,
        phase="FAIL",
        failureReason=error.code,
    ).model_dump(mode="json")
    turn_logs.append(turn_log)
    state["agent_turn_state"]["turnLogs"] = turn_logs
    detail_lines.append(turn_log_line(turn_log))
    log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, error.code, error.message)
    failure = build_failure_snapshot(
        error.category,
        error.code,
        error.root_cause,
        error.message,
        node_key=node.nodeKey,
        node_name=node.nodeName,
        resource=resource or error.failed_resource,
    )
    pause_for_failure(
        state,
        node.nodeKey,
        node.nodeName,
        title,
        f"{error.code}: {error.message}",
        expected_action,
        "\n".join([*detail_lines, f"{detail_key}={error.message}"]),
        failure,
    )


def pause_for_failure(
    state: AgentState,
    node_key: str,
    node_name: str,
    title: str,
    instruction: str,
    expected_action: str,
    detail: str,
    failure: Dict[str, Any],
) -> None:
    set_latest_failure(state, failure)
    pause_for_resume(
        state,
        node_key,
        node_name,
        title,
        instruction,
        expected_action,
        AGENT_HUMAN_TASK_SOURCE,
        node_key,
        detail,
        allowed_actions=list(DEFAULT_RESUME_ACTIONS),
        reason_code=str(failure.get("code", "")),
        reason_detail=str(failure.get("rootCause", failure.get("detail", ""))),
    )


def normalize_resume_action(action: Optional[str]) -> str:
    return (action or "").strip().upper()


def normalize_resume_source(source: Optional[str]) -> str:
    return (source or "").strip().upper()


def allowed_resume_actions(task: Optional[Dict[str, Any]]) -> List[str]:
    if not isinstance(task, dict):
        return list(DEFAULT_RESUME_ACTIONS)
    actions = task.get("allowedActions")
    if not isinstance(actions, list) or not actions:
        return list(DEFAULT_RESUME_ACTIONS)
    return [normalize_resume_action(item) for item in actions if str(item).strip()]


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
        if node.nodeType == "START":
            if len(node_edges) != 1:
                raise HTTPException(status_code=400, detail=f"START node must have exactly one outgoing edge: {node.nodeKey}")
            start_edge = node_edges[0]
            if not start_edge.defaultEdge or start_edge.routeKey.strip() != "default":
                raise HTTPException(
                    status_code=400,
                    detail=f"START node outgoing edge must be routeKey=default and defaultEdge=true: {start_edge.edgeKey}",
                )
        seen_route_keys: set[str] = set()
        for edge in node_edges:
            route_key = edge.routeKey.strip()
            if not route_key:
                raise HTTPException(status_code=400, detail=f"edge routeKey is required: {edge.edgeKey}")
            if edge.defaultEdge and route_key != "default":
                raise HTTPException(status_code=400, detail=f"default edge must use routeKey=default: {edge.edgeKey}")
            if not edge.defaultEdge and route_key == "default":
                raise HTTPException(status_code=400, detail=f"non-default edge cannot use routeKey=default: {edge.edgeKey}")
            if route_key in seen_route_keys:
                raise HTTPException(status_code=400, detail=f"duplicate routeKey for node {node.nodeKey}: {route_key}")
            seen_route_keys.add(route_key)
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
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "MODEL_RESOURCE_MISSING",
            f"No active model resource configured for agent {agent.agentId}",
        )
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


def normalize_shared_state(data: Any) -> Dict[str, Any]:
    try:
        normalized = SharedSessionState.model_validate(data or {})
    except ValidationError as exc:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", f"sharedState is invalid: {exc}") from exc
    return normalized.model_dump(mode="json")


def shared_state_from_session_context(session_context: Dict[str, Any]) -> Dict[str, Any]:
    normalized = normalize_shared_state(session_context.get("sharedState"))
    validate_shared_state_size(normalized)
    session_context["sharedState"] = normalized
    return normalized


def shared_facts(session_context: Dict[str, Any]) -> Dict[str, Any]:
    return shared_state_from_session_context(session_context)["facts"]


def shared_artifacts(session_context: Dict[str, Any]) -> Dict[str, Any]:
    return shared_state_from_session_context(session_context)["artifacts"]


def agent_scope(session_context: Dict[str, Any], agent_id: str) -> Dict[str, Any]:
    scopes = shared_state_from_session_context(session_context)["agentScopes"]
    scope = scopes.get(agent_id)
    if not isinstance(scope, dict):
        scope = {}
        scopes[agent_id] = scope
    return scope


def ensure_json_compatible(value: Any, path: str = "$") -> None:
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path} must be a finite number")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            ensure_json_compatible(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or not key:
                raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path} keys must be non-empty strings")
            ensure_json_compatible(item, f"{path}.{key}")
        return
    raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path} must be JSON-compatible")


def serialized_size(value: Any) -> int:
    try:
        return len(json.dumps(value, ensure_ascii=False).encode("utf-8"))
    except (TypeError, ValueError) as exc:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", f"value must be JSON-serializable: {exc}") from exc


def validate_shared_state_size(shared_state: Dict[str, Any]) -> None:
    ensure_json_compatible(shared_state, "$.sharedState")
    size = serialized_size(shared_state)
    if size > MAX_SHARED_SESSION_STATE_BYTES:
        raise AgentTurnError(
            "MODEL_OUTPUT_INVALID",
            f"sharedState exceeds size limit {MAX_SHARED_SESSION_STATE_BYTES} bytes",
        )


def patch_bucket(shared_state: Dict[str, Any], agent_id: str, target: str) -> Dict[str, Any]:
    if target == SESSION_STATE_TARGET_FACTS:
        return shared_state["facts"]
    if target == SESSION_STATE_TARGET_ARTIFACTS:
        return shared_state["artifacts"]
    scopes = shared_state["agentScopes"]
    if agent_id not in scopes or not isinstance(scopes[agent_id], dict):
        scopes[agent_id] = {}
    return scopes[agent_id]


def upsert_path(root: Dict[str, Any], path: List[str], value: Any) -> None:
    current = root
    for segment in path[:-1]:
        existing = current.get(segment)
        if existing is None:
            next_obj: Dict[str, Any] = {}
            current[segment] = next_obj
            current = next_obj
            continue
        if not isinstance(existing, dict):
            raise AgentTurnError(
                "MODEL_OUTPUT_INVALID",
                f"sessionStatePatch path {'/'.join(path)} crosses non-object segment {segment}",
            )
        current = existing
    current[path[-1]] = value


def remove_path(root: Dict[str, Any], path: List[str]) -> None:
    current = root
    for segment in path[:-1]:
        existing = current.get(segment)
        if existing is None:
            return
        if not isinstance(existing, dict):
            raise AgentTurnError(
                "MODEL_OUTPUT_INVALID",
                f"sessionStatePatch path {'/'.join(path)} crosses non-object segment {segment}",
            )
        current = existing
    current.pop(path[-1], None)


def apply_session_state_patch(state: AgentState, agent: AgentSnapshot, patch: SessionStatePatch) -> None:
    shared_state = shared_state_from_session_context(state["session_context"])
    for index, item in enumerate(patch.ops):
        bucket = patch_bucket(shared_state, agent.agentId, item.target)
        if item.op == SESSION_STATE_OP_UPSERT:
            ensure_json_compatible(item.value, f"$.sessionStatePatch.ops[{index}].value")
            if serialized_size(item.value) > MAX_SESSION_STATE_PATCH_VALUE_BYTES:
                raise AgentTurnError(
                    "MODEL_OUTPUT_INVALID",
                    f"sessionStatePatch value exceeds size limit {MAX_SESSION_STATE_PATCH_VALUE_BYTES} bytes",
                )
            upsert_path(bucket, item.path, item.value)
        else:
            remove_path(bucket, item.path)
    validate_shared_state_size(shared_state)


def format_default_user_prompt(
    question: str,
    conversation_history: str,
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    knowledge_context: List[Dict[str, Any]],
    tool_results: List[Dict[str, Any]],
    resume_input: Optional[Dict[str, Any]],
    shared_facts_payload: Dict[str, Any],
    shared_artifacts_payload: Dict[str, Any],
    agent_scope_payload: Dict[str, Any],
) -> str:
    cur_dt = datetime.now().strftime("%Y-%m-%d %H:%M")
    sections = [f"用户消息[{cur_dt}]：\n{question}"]
    if conversation_history:
        sections.append(f"会话记忆：\n{conversation_history}")
    if shared_facts_payload:
        sections.append(f"共享事实（facts）：\n{json.dumps(shared_facts_payload, ensure_ascii=False, indent=2)}")
    if shared_artifacts_payload:
        sections.append(f"共享产物（artifacts）：\n{json.dumps(shared_artifacts_payload, ensure_ascii=False, indent=2)}")
    if agent_scope_payload:
        sections.append(f"当前智能体私有上下文（agentScope）：\n{json.dumps(agent_scope_payload, ensure_ascii=False, indent=2)}")
    if available_skills:
        sections.append(f"可用技能目录：\n{json.dumps(available_skills, ensure_ascii=False, indent=2)}")
    if loaded_skills:
        sections.append(f"已加载技能详情：\n{json.dumps(loaded_skills, ensure_ascii=False, indent=2)}")
    if knowledge_context:
        sections.append(f"知识召回结果：\n{json.dumps(knowledge_context, ensure_ascii=False, indent=2)}")
    if tool_results:
        sections.append(f"工具结果：\n{json.dumps(tool_results, ensure_ascii=False, indent=2)}")
    if resume_input:
        sections.append(f"恢复输入：\n{json.dumps(resume_input, ensure_ascii=False, indent=2)}")
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
    shared_facts_payload: Dict[str, Any],
    shared_artifacts_payload: Dict[str, Any],
    agent_scope_payload: Dict[str, Any],
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    knowledge_context: List[Dict[str, Any]],
    tool_results: List[Dict[str, Any]],
    resume_input: Optional[Dict[str, Any]],
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
        resume_input,
        shared_facts_payload,
        shared_artifacts_payload,
        agent_scope_payload,
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
        "sessionStatePatch": {
            "ops": [
                {
                    "target": f"{SESSION_STATE_TARGET_FACTS} | {SESSION_STATE_TARGET_ARTIFACTS} | {SESSION_STATE_TARGET_AGENT_SCOPE}",
                    "op": f"{SESSION_STATE_OP_UPSERT} | {SESSION_STATE_OP_REMOVE}",
                    "path": ["string", "nestedKey"],
                    "value": "JSON-compatible value; required only when op=UPSERT",
                }
            ]
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
                "must": [
                    "Populate toolRequests.",
                    "You may also include skillReads if the tool decision depends on new skill details.",
                    "Treat tools as external business systems: request only business arguments that the tool contract requires.",
                ],
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
            "Tool results are business data only. Do not expect them to return orchestration fields such as routeKey.",
            "After tool calls complete, inspect the returned business result and make the routeDecision yourself when needed.",
            "Use sessionStatePatch to persist reusable session facts, artifacts, or your own agentScope.",
            "You can read facts/artifacts and only your own agentScope from the prompt. Do not assume access to other agents' scopes.",
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
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "MODEL_CONFIGURATION_INVALID",
            f"resource {model_resource.resourceId} is not an llm model",
            failed_resource=model_resource,
        )

    api_key = os.getenv(model_config.apiKeyEnvVar, "")
    if not api_key:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "MODEL_API_KEY_MISSING",
            f"Missing API key env var: {model_config.apiKeyEnvVar}",
            failed_resource=model_resource,
        )

    try:
        logger.info(
            "llm request prepared provider=%s model=%s systemPromptChars=%s userPromptChars=%s",
            model_config.providerType,
            model_config.modelId,
            len(system_prompt),
            len(prompt),
        )
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
                logger.info(
                    "llm response received provider=%s model=%s choiceCount=%s",
                    model_config.providerType,
                    model_config.modelId,
                    len(reponse_json.get("choices", [])),
                )
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
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TIMEOUT,
            "LLM_REQUEST_TIMEOUT",
            f"LLM request timed out for {model_config.baseUrl} model={model_config.modelId}: {exc}",
            root_cause=str(exc),
            failed_resource=model_resource,
        ) from exc
    except httpx.HTTPStatusError as exc:
        response_text = exc.response.text[:500] if exc.response is not None else ""
        raise WorkflowFailureError(
            FAILURE_CATEGORY_PROVIDER,
            "LLM_PROVIDER_HTTP_ERROR",
            f"LLM provider returned HTTP {exc.response.status_code if exc.response is not None else 'unknown'} for {model_config.baseUrl} model={model_config.modelId}: {response_text}",
            root_cause=response_text or str(exc),
            failed_resource=model_resource,
        ) from exc
    except httpx.TransportError as exc:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_PROVIDER,
            "LLM_PROVIDER_TRANSPORT_ERROR",
            f"LLM transport error for {model_config.baseUrl} model={model_config.modelId}: {exc}",
            root_cause=str(exc),
            failed_resource=model_resource,
        ) from exc

    raise WorkflowFailureError(
        FAILURE_CATEGORY_CONFIGURATION,
        "MODEL_PROVIDER_UNSUPPORTED",
        f"Unsupported provider: {model_config.providerType}",
        failed_resource=model_resource,
    )


def tokenize(text: str) -> List[str]:
    return [token for token in text.replace("？", " ").replace("，", " ").replace("。", " ").split() if token]


async def retrieve_knowledge(binding: Optional[KnowledgeBindingSnapshot], question: str) -> List[Dict[str, Any]]:
    if binding is None:
        return []
    if not binding.snapshotId.strip():
        return []
    knowledge_service_base_url = os.getenv("LYNXUS_KNOWLEDGE_SERVICE_BASE_URL", "http://localhost:8091").rstrip("/")
    auth_headers = {"Authorization": f"Bearer {internal_auth_token()}"}
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.post(
            f"{knowledge_service_base_url}/internal/retrieve",
            headers=auth_headers,
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
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_CONFIGURATION_INVALID",
            f"resource {resource.resourceId} has no tool operations configured",
            failed_resource=resource,
        )
    if operation_name:
        for operation in config.operations:
            if operation.name == operation_name:
                return operation
    return config.operations[0]


async def call_http_tool(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.tool
    if config is None or config.http is None:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_CONFIGURATION_INVALID",
            f"resource {resource.resourceId} is not an HTTP tool",
            failed_resource=resource,
        )

    request_method = config.http.method.upper()
    request_kwargs: Dict[str, Any] = {}
    if request_method == "GET":
        request_kwargs["params"] = payload
    else:
        request_kwargs["json"] = payload

    try:
        async with httpx.AsyncClient(timeout=config.timeoutSeconds) as client:
            response = await client.request(request_method, config.http.endpoint, **request_kwargs)
            response.raise_for_status()
            return response.json()
    except httpx.TimeoutException as exc:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_TIMEOUT",
            f"tool request timed out for {resource.resourceId} operation={operation.name}: {exc}",
            root_cause=str(exc),
            failed_resource=resource,
        ) from exc
    except httpx.HTTPStatusError as exc:
        response_text = exc.response.text[:500] if exc.response is not None else ""
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_HTTP_ERROR",
            f"tool returned HTTP {exc.response.status_code if exc.response is not None else 'unknown'} for {resource.resourceId} operation={operation.name}: {response_text}",
            root_cause=response_text or str(exc),
            failed_resource=resource,
        ) from exc
    except httpx.TransportError as exc:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_TRANSPORT_ERROR",
            f"tool transport error for {resource.resourceId} operation={operation.name}: {exc}",
            root_cause=str(exc),
            failed_resource=resource,
        ) from exc


async def call_mcp_tool(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = resource.configuration.tool
    if config is None or config.mcp is None:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_CONFIGURATION_INVALID",
            f"resource {resource.resourceId} is not an MCP-backed tool",
            failed_resource=resource,
        )
    remote_tool_name = config.mcp.operationMappings.get(operation.name, operation.name)

    try:
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
    except httpx.TimeoutException as exc:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_TIMEOUT",
            f"tool request timed out for {resource.resourceId} operation={operation.name}: {exc}",
            root_cause=str(exc),
            failed_resource=resource,
        ) from exc
    except httpx.HTTPStatusError as exc:
        response_text = exc.response.text[:500] if exc.response is not None else ""
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_HTTP_ERROR",
            f"tool returned HTTP {exc.response.status_code if exc.response is not None else 'unknown'} for {resource.resourceId} operation={operation.name}: {response_text}",
            root_cause=response_text or str(exc),
            failed_resource=resource,
        ) from exc
    except httpx.TransportError as exc:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_TOOL,
            "TOOL_TRANSPORT_ERROR",
            f"tool transport error for {resource.resourceId} operation={operation.name}: {exc}",
            root_cause=str(exc),
            failed_resource=resource,
        ) from exc


async def call_tool(resource: ResourceVersionSnapshot, operation_name: Optional[str], payload: Dict[str, Any]) -> tuple[ToolOperationConfig, Dict[str, Any]]:
    config = resource.configuration.tool
    if config is None:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_CONFIGURATION_INVALID",
            f"resource {resource.resourceId} is not a tool",
            failed_resource=resource,
        )
    operation = resolve_tool_operation(resource, operation_name)
    if config.providerType == "HTTP":
        result = await call_http_tool(resource, operation, payload)
    elif config.providerType == "MCP":
        result = await call_mcp_tool(resource, operation, payload)
    else:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_PROVIDER_UNSUPPORTED",
            f"Unsupported tool provider: {config.providerType}",
            failed_resource=resource,
        )
    validate_tool_result(resource, operation, result)
    return operation, result


def tool_history_for_prompt(state: AgentState) -> List[Dict[str, Any]]:
    prompt_rows: List[Dict[str, Any]] = []
    for item in state["tool_history"]:
        if not isinstance(item, dict):
            continue
        prompt_rows.append(
            {
                "toolResourceId": item.get("toolResourceId", ""),
                "toolResourceVersionId": item.get("toolResourceVersionId", ""),
                "toolResourceName": item.get("toolResourceName", ""),
                "operation": item.get("operation", ""),
                "arguments": item.get("arguments", {}),
                "result": item.get("result", {}),
            }
        )
    return prompt_rows


def default_route_key(graph: GraphSnapshot, node_key: str) -> Optional[str]:
    defaults = [edge.routeKey for edge in graph.edges if edge.sourceNodeKey == node_key and edge.defaultEdge and edge.routeKey]
    if len(defaults) == 1:
        return defaults[0]
    return None


def validate_tool_result(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, result: Any) -> None:
    if not isinstance(result, dict):
        raise AgentTurnError(
            "TOOL_RESPONSE_INVALID",
            f"tool {resource.resourceName}/{operation.name} must return a JSON object",
        )
    output_schema = operation.outputSchema.strip()
    if not output_schema:
        return
    try:
        schema = json.loads(output_schema)
    except json.JSONDecodeError as exc:
        raise AgentTurnError(
            "TOOL_SCHEMA_INVALID",
            f"tool {resource.resourceName}/{operation.name} has invalid outputSchema: {exc}",
        ) from exc
    validate_json_schema_value(result, schema, path="$")


def validate_json_schema_value(value: Any, schema: Any, path: str = "$") -> None:
    if not isinstance(schema, dict):
        return
    schema_type = schema.get("type")
    if isinstance(schema_type, list):
        last_error: Optional[AgentTurnError] = None
        for candidate_type in schema_type:
            try:
                validate_json_schema_value(value, {**schema, "type": candidate_type}, path)
                return
            except AgentTurnError as exc:
                last_error = exc
        if last_error is not None:
            raise last_error
        return
    if schema_type == "object":
        if not isinstance(value, dict):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be an object")
        required = schema.get("required", [])
        if isinstance(required, list):
            for key in required:
                if isinstance(key, str) and key not in value:
                    raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path}.{key} is required")
        properties = schema.get("properties", {})
        if isinstance(properties, dict):
            for key, property_schema in properties.items():
                if key in value:
                    validate_json_schema_value(value[key], property_schema, f"{path}.{key}")
        additional_properties = schema.get("additionalProperties", True)
        if additional_properties is False and isinstance(properties, dict):
            extras = [key for key in value if key not in properties]
            if extras:
                raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} has unexpected properties: {', '.join(sorted(extras))}")
        if isinstance(additional_properties, dict):
            for key, extra_value in value.items():
                if not isinstance(properties, dict) or key not in properties:
                    validate_json_schema_value(extra_value, additional_properties, f"{path}.{key}")
        return
    if schema_type == "array":
        if not isinstance(value, list):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be an array")
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(value):
                validate_json_schema_value(item, item_schema, f"{path}[{index}]")
        return
    if schema_type == "string":
        if not isinstance(value, str):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be a string")
        return
    if schema_type == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be an integer")
        return
    if schema_type == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be a number")
        return
    if schema_type == "boolean":
        if not isinstance(value, bool):
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be a boolean")
        return
    if schema_type == "null":
        if value is not None:
            raise AgentTurnError("TOOL_RESPONSE_INVALID", f"{path} must be null")
        return


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
                arguments=arguments,
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
    skill_resources: List[ResourceVersionSnapshot],
    tool_resources: List[ResourceVersionSnapshot],
) -> StructuredAgentDecision:
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
    tool_requests = normalize_tool_requests(parsed.get("toolRequests", []), tool_resources)
    raw_human_request = parsed.get("humanRequest")
    human_request = HumanRequest.model_validate(raw_human_request) if raw_human_request is not None else None
    raw_session_state_patch = parsed.get("sessionStatePatch")
    try:
        session_state_patch = SessionStatePatch.model_validate(raw_session_state_patch) if raw_session_state_patch is not None else None
    except ValidationError as exc:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", f"sessionStatePatch is invalid: {exc}") from exc

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

    return StructuredAgentDecision(
        decisionType=decision_type,
        message=str(parsed.get("message", "")).strip(),
        routeDecision=route_decision,
        skillReads=skill_reads,
        toolRequests=tool_requests,
        humanRequest=human_request,
        sessionStatePatch=session_state_patch,
    )


def store_tool_result(
    state: AgentState,
    agent: AgentSnapshot,
    resource: ResourceVersionSnapshot,
    operation: ToolOperationConfig,
    arguments: Dict[str, Any],
    result: Dict[str, Any],
) -> None:
    outcome_summary = build_tool_outcome(resource, operation, result)
    state["tool_history"].append(
        {
            "agentId": agent.agentId,
            "toolResourceId": resource.resourceId,
            "toolResourceVersionId": resource.resourceVersionId,
            "toolResourceName": resource.resourceName,
            "operation": operation.name,
            "arguments": arguments,
            "result": result,
            "createdAt": now_iso(),
        }
    )
    state["latest_tool_outcome"] = outcome_summary


def build_tool_outcome(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, result: Dict[str, Any]) -> Dict[str, Any]:
    provider_type = resource.configuration.tool.providerType if resource.configuration.tool else "UNKNOWN"
    return {
        "toolResourceId": resource.resourceId,
        "toolResourceName": resource.resourceName,
        "operation": operation.name,
        "providerType": provider_type,
        "result": result,
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
        "resume_task": state["resume_task"],
        "latest_tool_outcome": state["latest_tool_outcome"],
        "escalation_required": state["escalation_required"],
        "resume_count": state["resume_count"],
        "agent_turn_state": state["agent_turn_state"],
        "pause_reason": state["pause_reason"],
        "latest_failure": state["latest_failure"],
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
        "resume_task": None,
        "checkpoint": None,
        "escalation_required": data.get("escalation_required", False),
        "latest_tool_outcome": data.get("latest_tool_outcome"),
        "resume_input": resume_request.action.model_dump(mode="json"),
        "resume_count": resume_request.checkpoint.resumeCount + 1,
        "agent_turn_state": data.get("agent_turn_state", {"phase": "IDLE", "turnIndex": 0, "latestDecision": None, "turnLogs": []}),
        "pause_reason": data.get("pause_reason"),
        "latest_failure": data.get("latest_failure"),
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
    if turn_log.get("sessionStateOpsDelta"):
        parts.append(f"session_state_ops_delta={turn_log['sessionStateOpsDelta']}")
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


def pause_for_resume(
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
    resume_source: str = RESUME_SOURCE_HUMAN,
    interaction_task_id: Optional[str] = None,
    interaction_type: Optional[str] = None,
    timeout_policy_key: Optional[str] = None,
) -> None:
    state["current_node_key"] = node_key
    state["resume_task"] = {
        "nodeKey": node_key,
        "title": title,
        "instruction": instruction,
        "expectedAction": expected_action,
        "source": source,
        "allowedActions": allowed_actions or list(DEFAULT_RESUME_ACTIONS),
    }
    state["escalation_required"] = True
    state["workflow_status"] = "WAITING_RESUME"
    state["summary"] = instruction or state["summary"] or "等待恢复处理。"
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
        "resumeContext": {
            "source": resume_source,
            "reasonCode": reason_code,
            "interactionTaskId": interaction_task_id,
            "interactionType": interaction_type,
            "timeoutPolicyKey": timeout_policy_key,
        },
        "resumeCount": state["resume_count"],
    }
    state["next_node_key"] = "__end__"
    append_node(state, node_key, node_name, detail or state["summary"], status="WAITING_RESUME")


def cancel_workflow_from_resume_action(
    state: AgentState,
    graph: GraphSnapshot,
    waiting_node_key: Optional[str],
    action: ResumeAction,
) -> None:
    node_key = waiting_node_key or state["current_node_key"] or "workflow-cancelled"
    node_name = find_node_name(graph, node_key)
    detail = action.comment or "人工终止了当前流程。"
    state["current_node_key"] = node_key
    state["next_node_key"] = "__end__"
    state["resume_task"] = None
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

    detail_lines = [agent.responsibility]
    route_key: Optional[str] = None
    route_source = ""
    final_message = ""
    turn_logs: List[Dict[str, Any]] = []
    state["agent_turn_state"] = {
        "phase": "PREPARE_CONTEXT",
        "turnIndex": 0,
        "latestDecision": None,
        "turnLogs": turn_logs,
    }
    try:
        model_resource = resolve_model_resource(assistant, agent)
    except WorkflowFailureError as exc:
        pause_agent_node_with_workflow_failure(
            state,
            node,
            turn_logs,
            detail_lines,
            1,
            exc,
            "模型调用失败，需要人工介入",
            "请人工确认上下文并继续处理",
            "failure",
        )
        return
    system_prompt = build_system_prompt(agent)
    tool_resources = resolve_tool_resources(assistant, agent)

    for turn_index in range(1, AGENT_MAX_TURNS + 1):
        state["agent_turn_state"]["phase"] = "PREPARE_CONTEXT"
        state["agent_turn_state"]["turnIndex"] = turn_index
        available_skills = available_skill_catalog(skill_resources)
        loaded_skills = loaded_skill_details(skill_resources, state["session_context"])
        prompt = build_structured_agent_prompt(
            state["question"],
            conversation_history,
            shared_facts(state["session_context"]),
            shared_artifacts(state["session_context"]),
            agent_scope(state["session_context"], agent.agentId),
            available_skills,
            loaded_skills,
            hits,
            tool_history_for_prompt(state),
            state["resume_input"],
            tool_resources,
            available_routes_for_prompt(graph, node.nodeKey),
            turn_index - 1,
        )
        state["agent_turn_state"]["phase"] = "CALL_MODEL"
        try:
            llm_output = await call_llm(model_resource, prompt, system_prompt)
        except WorkflowFailureError as exc:
            pause_agent_node_with_workflow_failure(
                state,
                node,
                turn_logs,
                detail_lines,
                turn_index,
                exc,
                "模型调用失败，需要人工介入",
                "请人工确认上下文并继续处理",
                "failure",
                model_resource,
            )
            return
        state["agent_turn_state"]["phase"] = "VALIDATE_RESPONSE"
        try:
            structured = parse_agent_structured_response(llm_output, graph, node, skill_resources, tool_resources)
            state["agent_turn_state"]["latestDecision"] = structured.model_dump(mode="json")
            if structured.sessionStatePatch is not None:
                state["agent_turn_state"]["phase"] = "APPLY_SESSION_STATE_PATCH"
                apply_session_state_patch(state, agent, structured.sessionStatePatch)
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
            failure = build_failure_snapshot(
                categorize_agent_turn_error(exc.code),
                exc.code,
                exc.message,
                exc.message,
                node_key=node.nodeKey,
                node_name=node.nodeName,
            )
            pause_for_failure(
                state,
                node.nodeKey,
                node.nodeName,
                "需要人工介入",
                f"{exc.code}: {exc.message}",
                "请人工确认后继续处理",
                "\n".join([*detail_lines, f"failure={exc.code}", exc.message]),
                failure,
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
        if structured.sessionStatePatch is not None:
            turn_log.sessionStateOpsDelta = len(structured.sessionStatePatch.ops)

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
                failure = build_failure_snapshot(
                    FAILURE_CATEGORY_RUNTIME,
                    "MAX_TURNS_EXCEEDED",
                    f"agent exceeded max turns={AGENT_MAX_TURNS}",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    node_key=node.nodeKey,
                    node_name=node.nodeName,
                )
                pause_for_failure(
                    state,
                    node.nodeKey,
                    node.nodeName,
                    "达到最大执行轮次",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    "请人工确认上下文后继续处理",
                    "\n".join(detail_lines),
                    failure,
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
            pause_for_resume(
                state,
                node.nodeKey,
                node.nodeName,
                human_request.title or "需要人工介入",
                human_request.instruction or "请人工继续处理当前会话",
                human_request.expectedAction or "补充处理意见",
                AGENT_HUMAN_TASK_SOURCE,
                node.nodeKey,
                "\n".join(detail_lines),
                allowed_actions=list(DEFAULT_RESUME_ACTIONS),
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
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = "TOOL_RESOURCE_MISSING"
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(
                        state["workflow_instance_id"],
                        node.nodeKey,
                        turn_index,
                        "TOOL_RESOURCE_MISSING",
                        f"tool resource not found: {tool_request.toolResourceVersionId}",
                    )
                    failure = build_failure_snapshot(
                        FAILURE_CATEGORY_CONFIGURATION,
                        "TOOL_RESOURCE_MISSING",
                        f"tool resource not found: {tool_request.toolResourceVersionId}",
                        f"tool resource not found: {tool_request.toolResourceVersionId}",
                        node_key=node.nodeKey,
                        node_name=node.nodeName,
                    )
                    pause_for_failure(
                        state,
                        node.nodeKey,
                        node.nodeName,
                        "工具资源缺失，需要人工介入",
                        f"TOOL_RESOURCE_MISSING: tool resource not found: {tool_request.toolResourceVersionId}",
                        "请人工确认工具配置并继续处理",
                        "\n".join([*detail_lines, f"tool_error=tool resource not found: {tool_request.toolResourceVersionId}"]),
                        failure,
                    )
                    return
                try:
                    operation, tool_result = await call_tool(tool_resource, tool_request.operation, tool_request.arguments)
                    store_tool_result(state, agent, tool_resource, operation, tool_request.arguments, tool_result)
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, operation.name, "COMPLETED", json.dumps(tool_result, ensure_ascii=False))
                    turn_log.toolCallsDelta += 1
                except AgentTurnError as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", exc.message)
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = exc.code
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, exc.code, exc.message)
                    failure = build_failure_snapshot(
                        categorize_agent_turn_error(exc.code),
                        exc.code,
                        exc.message,
                        exc.message,
                        node_key=node.nodeKey,
                        node_name=node.nodeName,
                        resource=tool_resource,
                    )
                    pause_for_failure(
                        state,
                        node.nodeKey,
                        node.nodeName,
                        "工具结果不符合约定，需要人工介入",
                        f"{exc.code}: {exc.message}",
                        "请人工确认工具结果并继续处理",
                        "\n".join([*detail_lines, f"tool_error={exc.message}"]),
                        failure,
                    )
                    return
                except WorkflowFailureError as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", exc.message)
                    pause_agent_node_with_workflow_failure(
                        state,
                        node,
                        turn_logs,
                        detail_lines,
                        turn_index,
                        exc,
                        "工具执行失败，需要人工介入",
                        "请人工确认工具结果并继续处理",
                        "tool_error",
                        tool_resource,
                    )
                    return
                except Exception as exc:
                    provider_type = tool_resource.configuration.tool.providerType if tool_resource.configuration.tool else "UNKNOWN"
                    record_tool_call(state, provider_type, tool_resource, tool_request.operation, "FAILED", str(exc))
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = "TOOL_EXECUTION_FAILED"
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, "TOOL_EXECUTION_FAILED", str(exc))
                    failure = build_failure_snapshot(
                        FAILURE_CATEGORY_TOOL,
                        "TOOL_EXECUTION_FAILED",
                        str(exc),
                        f"工具调用失败：{exc}",
                        node_key=node.nodeKey,
                        node_name=node.nodeName,
                        resource=tool_resource,
                    )
                    pause_for_failure(
                        state,
                        node.nodeKey,
                        node.nodeName,
                        "工具执行失败，需要人工介入",
                        f"工具调用失败：{exc}",
                        "请人工确认工具结果并继续处理",
                        "\n".join([*detail_lines, f"tool_error={exc}"]),
                        failure,
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
                failure = build_failure_snapshot(
                    FAILURE_CATEGORY_RUNTIME,
                    "MAX_TURNS_EXCEEDED",
                    f"agent exceeded max turns={AGENT_MAX_TURNS}",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    node_key=node.nodeKey,
                    node_name=node.nodeName,
                )
                pause_for_failure(
                    state,
                    node.nodeKey,
                    node.nodeName,
                    "达到最大执行轮次",
                    f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
                    "请人工确认上下文后继续处理",
                    "\n".join(detail_lines),
                    failure,
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
            failure = build_failure_snapshot(
                FAILURE_CATEGORY_RUNTIME,
                exc.code,
                exc.message,
                exc.message,
                node_key=node.nodeKey,
                node_name=node.nodeName,
            )
            pause_for_failure(
                state,
                node.nodeKey,
                node.nodeName,
                "路由决策异常，需要人工介入",
                f"{exc.code}: {exc.message}",
                "请人工确认流转路由并继续处理",
                "\n".join([*detail_lines, f"route_error={exc.message}"]),
                failure,
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
        failure = build_failure_snapshot(
            FAILURE_CATEGORY_RUNTIME,
            "MAX_TURNS_EXCEEDED",
            f"agent exceeded max turns={AGENT_MAX_TURNS}",
            f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
            node_key=node.nodeKey,
            node_name=node.nodeName,
        )
        pause_for_failure(
            state,
            node.nodeKey,
            node.nodeName,
            "达到最大执行轮次",
            f"智能体在 {AGENT_MAX_TURNS} 轮内未完成，需要人工继续处理。",
            "请人工确认上下文后继续处理",
            "\n".join(detail_lines),
            failure,
        )
        return

    message = final_message or agent.responsibility
    human_comment = state["resume_input"]["comment"] if state["resume_input"] else ""
    latest_outcome = state["latest_tool_outcome"] or {}
    latest_result = latest_outcome.get("result", {}) if isinstance(latest_outcome, dict) else {}
    suggestion = json.dumps(latest_result, ensure_ascii=False) if latest_result else ""
    human_suffix = f"恢复说明：{human_comment}" if human_comment else ""
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
    pause_for_resume(
        state,
        node.nodeKey,
        node.nodeName,
        node.humanNode.title,
        node.humanNode.instruction,
        node.humanNode.expectedAction,
        GRAPH_HUMAN_TASK_SOURCE,
        next_node_key or "end",
        f"等待人工处理：{node.humanNode.instruction}",
        allowed_actions=list(DEFAULT_RESUME_ACTIONS),
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
    status = state.get("workflow_status") or ("WAITING_RESUME" if state["resume_task"] else "COMPLETED")
    return WorkflowResult(
        workflowInstanceId=state["workflow_instance_id"],
        status=status,
        summary=state["summary"] or state["final_reply"] or "流程已执行。",
        finalReply=state["final_reply"] or None,
        currentNodeKey=state["current_node_key"],
        checkpoint=ExecutionCheckpoint(**state["checkpoint"]) if state["checkpoint"] else None,
        resumeTask=ResumeTaskSnapshot(**state["resume_task"]) if state["resume_task"] else None,
        pauseReason=PauseReasonSnapshot(**state["pause_reason"]) if state["pause_reason"] else None,
        latestFailure=WorkflowFailureSnapshot(**state["latest_failure"]) if state["latest_failure"] else None,
        nodes=[NodeSnapshot(**node) for node in state["node_snapshots"]],
        toolCalls=[ToolInvocationSnapshot(**tool) for tool in state["tool_calls"]],
        escalationRequired=state["escalation_required"],
        latestToolOutcome=ToolOutcomeSummary(**state["latest_tool_outcome"]) if state["latest_tool_outcome"] else None,
        loadedSkillResourceVersionIds=loaded_skill_version_ids(state["session_context"]),
        sharedState=SharedSessionState.model_validate(shared_state_from_session_context(state["session_context"])),
        agentTurnState=AgentTurnState.model_validate(state["agent_turn_state"]),
    )


@app.post("/agent-runs/start", response_model=WorkflowResult)
async def start_agent_run(request: WorkflowStartRequest, _: None = Depends(require_internal_bearer)) -> WorkflowResult:
    bind_log_context(
        sessionId=request.sessionContext.sessionId,
        workflowId=request.workflowInstanceId,
        customerId=request.logContext.customerId if request.logContext else request.customerId,
        userId=request.logContext.userId if request.logContext else None,
    )
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
        "resume_task": None,
        "checkpoint": None,
        "escalation_required": False,
        "latest_tool_outcome": None,
        "resume_input": None,
        "resume_count": 0,
        "agent_turn_state": {"phase": "IDLE", "turnIndex": 0, "latestDecision": None, "turnLogs": []},
        "pause_reason": None,
        "latest_failure": None,
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
async def resume_agent_run(request: WorkflowResumeRequest, _: None = Depends(require_internal_bearer)) -> WorkflowResult:
    bind_log_context(
        sessionId=request.sessionContext.sessionId,
        workflowId=request.workflowInstanceId,
        customerId=request.logContext.customerId if request.logContext else request.sessionContext.customerId,
        userId=request.logContext.userId if request.logContext and request.logContext.userId else request.action.userId,
    )
    logger.info(
        "workflow %s resume request received type=%s source=%s userId=%s",
        request.workflowInstanceId,
        request.action.type,
        request.action.source,
        request.action.userId,
    )
    validate_graph(request.assistant.graph, request.assistant)
    payload = json.loads(request.checkpoint.statePayload or "{}")
    saved_resume_task = payload.get("resume_task")
    action = normalize_resume_action(request.action.type)
    action_source = normalize_resume_source(request.action.source)
    if action not in allowed_resume_actions(saved_resume_task):
        raise HTTPException(status_code=400, detail=f"unsupported resume action: {request.action.type}")
    if action_source not in {RESUME_SOURCE_HUMAN, RESUME_SOURCE_EXTERNAL_SYSTEM, RESUME_SOURCE_TIMEOUT_POLICY}:
        raise HTTPException(status_code=400, detail=f"unsupported resume source: {request.action.source}")
    state = restore_state(payload, request)
    state["resume_input"]["type"] = action
    state["resume_input"]["source"] = action_source
    if action == RESUME_ACTION_TERMINATE and action_source != RESUME_SOURCE_HUMAN:
        raise HTTPException(status_code=400, detail="TERMINATE is only supported for HUMAN resume actions")
    if action == RESUME_ACTION_TERMINATE:
        cancel_workflow_from_resume_action(
            state,
            GraphSnapshot(**state["graph"]),
            request.checkpoint.waitingNodeKey,
            request.action.model_copy(update={"type": action, "source": action_source}),
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
    if result.status == "WAITING_RESUME":
        return result
    return result.model_copy(update={"escalationRequired": False})
