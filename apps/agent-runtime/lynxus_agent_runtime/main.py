from __future__ import annotations

import json
import logging
import os
import secrets
import time
from contextlib import asynccontextmanager
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Dict, List, NotRequired, Optional, TypedDict
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


class KnowledgeChunkRead(BaseModel):
    chunkId: str
    documentId: str
    documentTitle: str
    sourceUri: str
    headingPath: str = ""
    pageNumber: Optional[int] = None
    content: str


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
    knowledgeEnabled: bool
    inheritAssistantKnowledge: bool
    knowledgeBinding: Optional[KnowledgeBindingSnapshot] = None
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
    assistantKnowledgeBinding: Optional[KnowledgeBindingSnapshot] = None
    agents: List[AgentSnapshot]
    resources: List[ResourceVersionSnapshot]
    graph: GraphSnapshot


class StructuredAgentPrompt(TypedDict):
    instruction_block: str
    capability_block: str
    runtime_context_block: str


class LlmPromptPayload(TypedDict):
    system_prompt: str
    instruction_block: str
    capability_block: str
    runtime_context_block: str
    history_truncated: NotRequired[bool]
    history_dropped_messages: NotRequired[int]
    tool_history_kept_items: NotRequired[int]
    tool_history_dropped_items: NotRequired[int]
    tool_history_arguments_truncated: NotRequired[int]
    tool_history_results_truncated: NotRequired[int]


class SessionMessageSnapshot(BaseModel):
    role: str
    senderName: str
    payloadType: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    content: str
    createdAt: str

    @field_validator("payload", mode="before")
    @classmethod
    def normalize_payload(cls, value: Any) -> Dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("payload must be an object")
        return value

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


class ConversationAction(BaseModel):
    label: str
    actionType: str
    url: Optional[str] = None
    target: Optional[str] = None
    parameters: Dict[str, Any] = Field(default_factory=dict)
    disabled: bool = False


class WorkflowOutputMessage(BaseModel):
    messageKey: str
    payloadType: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    createdAt: str

    @field_validator("payload", mode="before")
    @classmethod
    def normalize_payload(cls, value: Any) -> Dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("payload must be an object")
        return value

    @field_validator("createdAt", mode="before")
    @classmethod
    def normalize_created_at(cls, value: Any) -> str:
        return SessionMessageSnapshot.normalize_created_at(value)


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
    latestMessage: SessionMessageSnapshot
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
    callId: str
    toolId: str
    toolName: str
    toolKind: str
    providerType: str
    resourceId: Optional[str] = None
    resourceName: Optional[str] = None
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
    callId: str
    toolId: str
    toolName: str
    toolKind: str
    operation: str
    providerType: str
    resourceId: Optional[str] = None
    resourceName: Optional[str] = None
    result: Dict[str, Any] = Field(default_factory=dict)


class ToolExecutionRecord(BaseModel):
    agentId: str
    callId: str
    toolId: str
    toolName: str
    toolKind: str
    providerType: str
    resourceId: Optional[str] = None
    resourceVersionId: Optional[str] = None
    resourceName: Optional[str] = None
    operation: str
    arguments: Dict[str, Any] = Field(default_factory=dict)
    result: Dict[str, Any] = Field(default_factory=dict)
    createdAt: str


class ToolRequest(BaseModel):
    callId: str
    toolId: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class AvailableTool(BaseModel):
    toolId: str
    toolName: str
    toolKind: str
    providerType: str
    operation: str
    description: str = ""
    inputSchema: str = ""
    outputSchema: str = ""
    resourceId: Optional[str] = None
    resourceVersionId: Optional[str] = None
    resourceName: Optional[str] = None


class HumanRequest(BaseModel):
    title: str = ""
    instruction: str = ""
    expectedAction: str = ""


class OutputMessageDraft(BaseModel):
    payloadType: str
    payload: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("payloadType", mode="before")
    @classmethod
    def normalize_payload_type(cls, value: Any) -> str:
        return str(value or "").strip().upper()

    @field_validator("payload", mode="before")
    @classmethod
    def normalize_payload(cls, value: Any) -> Dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise TypeError("outputMessages.payload must be an object")
        return value


class StructuredAgentDecision(BaseModel):
    decisionType: str
    routeDecision: Optional[str] = None
    outputMessages: List[OutputMessageDraft] = Field(default_factory=list)
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


class ModelHitSnapshot(BaseModel):
    agentId: str
    agentName: str
    nodeKey: str
    nodeName: str
    source: str
    resourceId: str
    resourceName: str
    resourceVersionId: str
    resourceVersion: str
    providerType: str
    modelId: str
    turnIndex: int
    capturedAt: str


class WorkflowResult(BaseModel):
    workflowInstanceId: str
    status: str
    summary: str
    currentNodeKey: Optional[str] = None
    checkpoint: Optional[ExecutionCheckpoint] = None
    resumeTask: Optional[ResumeTaskSnapshot] = None
    pauseReason: Optional[PauseReasonSnapshot] = None
    latestFailure: Optional[WorkflowFailureSnapshot] = None
    nodes: List[NodeSnapshot]
    toolCalls: List[ToolInvocationSnapshot]
    escalationRequired: bool
    latestToolOutcome: Optional[ToolOutcomeSummary] = None
    modelHits: List[ModelHitSnapshot] = Field(default_factory=list)
    outputMessages: List[WorkflowOutputMessage] = Field(default_factory=list)
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
    summary: str
    tool_history: List[Dict[str, Any]]
    tool_calls: List[Dict[str, Any]]
    model_hits: List[Dict[str, Any]]
    output_messages: List[Dict[str, Any]]
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


logger = configure_runtime_logger()
LLM_REQUEST_TIMEOUT_SECONDS = 30
AGENT_MAX_TURNS = 6
AGENT_DECISION_FINAL = "FINAL"
AGENT_DECISION_TOOL_CALL = "TOOL_CALL"
AGENT_DECISION_SKILL_READ = "SKILL_READ"
AGENT_DECISION_HUMAN_HANDOFF = "HUMAN_HANDOFF"
TOOL_KIND_RESOURCE = "RESOURCE"
TOOL_KIND_BUILTIN = "BUILTIN"
PROVIDER_TYPE_BUILTIN = "BUILTIN"
BUILTIN_TOOL_KNOWLEDGE_SEARCH = "builtin:knowledge_search"
BUILTIN_TOOL_KNOWLEDGE_READ = "builtin:knowledge_read"
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


@asynccontextmanager
async def lifespan(_: FastAPI):
    internal_auth_token()
    yield


app = FastAPI(title="lynxus-agent-runtime", version="1.0.0", lifespan=lifespan)
SESSION_STATE_TARGET_FACTS = "FACTS"
SESSION_STATE_TARGET_ARTIFACTS = "ARTIFACTS"
SESSION_STATE_TARGET_AGENT_SCOPE = "AGENT_SCOPE"
SESSION_STATE_OP_UPSERT = "UPSERT"
SESSION_STATE_OP_REMOVE = "REMOVE"
MAX_SESSION_STATE_PATCH_VALUE_BYTES = 16 * 1024
MAX_SHARED_SESSION_STATE_BYTES = 64 * 1024
MAX_PROMPT_HISTORY_BYTES = 8 * 1024
MAX_PROMPT_TOOL_HISTORY_ITEMS = 4
MAX_PROMPT_TOOL_ARGUMENT_BYTES = 1024
MAX_PROMPT_TOOL_RESULT_BYTES = 3 * 1024
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


def output_message_key(node_key: str, turn_index: int, ordinal: int) -> str:
    return f"{node_key}:{turn_index}:{ordinal}"


def summarize_output_payload(payload_type: str, payload: Dict[str, Any]) -> str:
    if payload_type == "TEXT":
        return str(payload.get("text", "")).strip()
    if payload_type == "EXTERNAL_INTERACTION":
        spec = payload.get("spec", {})
        projection = payload.get("projection", {})
        if not isinstance(spec, dict):
            spec = {}
        if not isinstance(projection, dict):
            projection = {}
        title = str(spec.get("title", "")).strip()
        instruction = str(spec.get("instruction", "")).strip()
        status = str(projection.get("status", "")).strip()
        return " · ".join([item for item in [title, instruction, status] if item])
    return ""


def append_output_message(
    state: AgentState,
    *,
    message_key: str,
    payload_type: str,
    payload: Dict[str, Any],
    created_at: Optional[str] = None,
) -> None:
    normalized = WorkflowOutputMessage(
        messageKey=message_key,
        payloadType=payload_type,
        payload=payload,
        createdAt=created_at or now_iso(),
    ).model_dump(mode="json")
    existing_index = next(
        (index for index, item in enumerate(state["output_messages"]) if item.get("messageKey") == message_key),
        None,
    )
    if existing_index is None:
        state["output_messages"].append(normalized)
        return
    existing = WorkflowOutputMessage.model_validate(state["output_messages"][existing_index]).model_dump(mode="json")
    if existing["payloadType"] != normalized["payloadType"] or existing["payload"] != normalized["payload"]:
        raise HTTPException(status_code=500, detail=f"output message changed for key {message_key}")
    state["output_messages"][existing_index] = existing


def emit_output_messages(
    state: AgentState,
    node_key: str,
    turn_index: int,
    output_messages: List[OutputMessageDraft],
) -> List[WorkflowOutputMessage]:
    emitted: List[WorkflowOutputMessage] = []
    for ordinal, draft in enumerate(output_messages, start=1):
        message = WorkflowOutputMessage(
            messageKey=output_message_key(node_key, turn_index, ordinal),
            payloadType=draft.payloadType,
            payload=draft.payload,
            createdAt=now_iso(),
        )
        append_output_message(
            state,
            message_key=message.messageKey,
            payload_type=message.payloadType,
            payload=message.payload,
            created_at=message.createdAt,
        )
        emitted.append(message)
    if emitted:
        state["summary"] = summarize_output_payload(emitted[-1].payloadType, emitted[-1].payload) or state["summary"]
    return emitted


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
    call_id: str,
    tool_id: str,
    tool_name: str,
    tool_kind: str,
    provider_type: str,
    operation: str,
    status: str,
    detail: str,
    resource: Optional[ResourceVersionSnapshot] = None,
) -> None:
    state["tool_calls"].append(
        {
            "id": next_id("tool"),
            "callId": call_id,
            "toolId": tool_id,
            "toolName": tool_name,
            "toolKind": tool_kind,
            "providerType": provider_type,
            "resourceId": resource.resourceId if resource else None,
            "resourceName": resource.resourceName if resource else None,
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


def resolve_model_resource(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> tuple[ResourceVersionSnapshot, str]:
    override_version_id = agent.executionPolicy.modelResourceVersionId if not agent.executionPolicy.inheritAssistantDefaults else None
    source = "AGENT_OVERRIDE" if override_version_id else "ASSISTANT_DEFAULT"
    resource = resolve_resource(assistant, override_version_id or assistant.assistantPolicy.providerResourceVersionId)
    if not resource or not resource.configuration.llmModel:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "MODEL_RESOURCE_MISSING",
            f"No active model resource configured for agent {agent.agentId}",
        )
    return resource, source


def append_model_hit(
    state: AgentState,
    node: GraphNodeSnapshot,
    agent: AgentSnapshot,
    model_resource: ResourceVersionSnapshot,
    source: str,
    turn_index: int,
) -> None:
    model_config = model_resource.configuration.llmModel
    state["model_hits"].append(
        ModelHitSnapshot(
            agentId=agent.agentId,
            agentName=agent.name,
            nodeKey=node.nodeKey,
            nodeName=node.nodeName,
            source=source,
            resourceId=model_resource.resourceId,
            resourceName=model_resource.resourceName,
            resourceVersionId=model_resource.resourceVersionId,
            resourceVersion=model_resource.resourceVersion,
            providerType=model_config.providerType if model_config else "UNKNOWN",
            modelId=model_config.modelId if model_config else "",
            turnIndex=turn_index,
            capturedAt=now_iso(),
        ).model_dump(mode="json")
    )


def resolve_knowledge_binding(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> Optional[KnowledgeBindingSnapshot]:
    if not agent.executionPolicy.knowledgeEnabled:
        return None
    if agent.executionPolicy.inheritAssistantKnowledge:
        return assistant.assistantKnowledgeBinding
    return agent.executionPolicy.knowledgeBinding


def resolve_tool_resources(assistant: AssistantRunSnapshot, agent: AgentSnapshot) -> List[ResourceVersionSnapshot]:
    tools = []
    for resource_version_id in agent.executionPolicy.toolResourceVersionIds:
        resource = resolve_resource(assistant, resource_version_id)
        if resource:
            tools.append(resource)
    return tools


def resource_tool_id(resource: ResourceVersionSnapshot, operation_name: str) -> str:
    return f"resource:{resource.resourceVersionId}:{operation_name}"


def available_builtin_tools(binding: Optional[KnowledgeBindingSnapshot]) -> List[AvailableTool]:
    if binding is None:
        return []
    return [
        AvailableTool(
            toolId=BUILTIN_TOOL_KNOWLEDGE_SEARCH,
            toolName="knowledge_search",
            toolKind=TOOL_KIND_BUILTIN,
            providerType=PROVIDER_TYPE_BUILTIN,
            operation="knowledge_search",
            description="Search the bound knowledge base for relevant chunks before answering.",
            inputSchema=json.dumps(
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
                ensure_ascii=False,
            ),
            outputSchema=json.dumps(
                {
                    "type": "object",
                    "properties": {
                        "knowledgeBaseId": {"type": "string"},
                        "knowledgeBaseName": {"type": "string"},
                        "knowledgeReleaseId": {"type": "string"},
                        "knowledgeReleaseVersion": {"type": "string"},
                        "lowConfidence": {"type": "boolean"},
                        "hits": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "chunkId": {"type": "string"},
                                    "documentId": {"type": "string"},
                                    "documentTitle": {"type": "string"},
                                    "sourceUri": {"type": "string"},
                                    "snippet": {"type": "string"},
                                    "score": {"type": "number"},
                                    "pageNumber": {"type": ["integer", "null"]},
                                    "headingPath": {"type": "string"},
                                },
                                "required": ["chunkId", "documentId", "documentTitle", "sourceUri", "snippet", "score", "headingPath"],
                                "additionalProperties": False,
                            },
                        },
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
                ensure_ascii=False,
            ),
        ),
        AvailableTool(
            toolId=BUILTIN_TOOL_KNOWLEDGE_READ,
            toolName="knowledge_read",
            toolKind=TOOL_KIND_BUILTIN,
            providerType=PROVIDER_TYPE_BUILTIN,
            operation="knowledge_read",
            description="Read full content for selected chunks from the bound knowledge base.",
            inputSchema=json.dumps(
                {
                    "type": "object",
                    "properties": {
                        "chunkIds": {
                            "type": "array",
                            "items": {"type": "string"},
                        }
                    },
                    "required": ["chunkIds"],
                    "additionalProperties": False,
                },
                ensure_ascii=False,
            ),
            outputSchema=json.dumps(
                {
                    "type": "object",
                    "properties": {
                        "knowledgeBaseId": {"type": "string"},
                        "knowledgeBaseName": {"type": "string"},
                        "knowledgeReleaseId": {"type": "string"},
                        "knowledgeReleaseVersion": {"type": "string"},
                        "chunks": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "chunkId": {"type": "string"},
                                    "documentId": {"type": "string"},
                                    "documentTitle": {"type": "string"},
                                    "sourceUri": {"type": "string"},
                                    "headingPath": {"type": "string"},
                                    "pageNumber": {"type": ["integer", "null"]},
                                    "content": {"type": "string"},
                                },
                                "required": ["chunkId", "documentId", "documentTitle", "sourceUri", "headingPath", "content"],
                                "additionalProperties": False,
                            },
                        },
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
                ensure_ascii=False,
            ),
        ),
    ]


def available_tools_for_agent(
    assistant: AssistantRunSnapshot,
    agent: AgentSnapshot,
    binding: Optional[KnowledgeBindingSnapshot],
) -> List[AvailableTool]:
    catalog: List[AvailableTool] = []
    for resource in resolve_tool_resources(assistant, agent):
        tool = resource.configuration.tool
        if tool is None:
            continue
        for operation in tool.operations:
            catalog.append(
                AvailableTool(
                    toolId=resource_tool_id(resource, operation.name),
                    toolName=f"{resource.resourceName}.{operation.name}",
                    toolKind=TOOL_KIND_RESOURCE,
                    providerType=tool.providerType,
                    operation=operation.name,
                    description=operation.description,
                    inputSchema=operation.inputSchema,
                    outputSchema=operation.outputSchema,
                    resourceId=resource.resourceId,
                    resourceVersionId=resource.resourceVersionId,
                    resourceName=resource.resourceName,
                )
            )
    catalog.extend(available_builtin_tools(binding))
    return catalog


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


def format_timestamp_to_minute(value: Any) -> str:
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M")
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, tz=timezone.utc).strftime("%Y-%m-%d %H:%M")
    if isinstance(value, str):
        candidate = value.strip()
        if not candidate:
            return ""
        try:
            return datetime.fromisoformat(candidate.replace("Z", "+00:00")).strftime("%Y-%m-%d %H:%M")
        except ValueError:
            return candidate[:16]
    return ""


def latest_message_timestamp_for_prompt(session_context: Dict[str, Any]) -> str:
    latest_message = session_context.get("latestMessage")
    if isinstance(latest_message, dict):
        created_at = format_timestamp_to_minute(latest_message.get("createdAt"))
        if created_at:
            return created_at
    return format_timestamp_to_minute(datetime.now())


def build_conversation_history(session_context: Dict[str, Any], question: str, window_size: int) -> str:
    history, _ = build_conversation_history_with_budget(session_context, question, window_size)
    return history


def build_conversation_history_with_budget(session_context: Dict[str, Any], question: str, window_size: int) -> tuple[str, int]:
    if window_size <= 0:
        return "", 0
    raw_history = session_context.get("history", [])
    if not isinstance(raw_history, list):
        return "", 0

    history_entries: List[tuple[str, str, str, str]] = []
    for item in raw_history:
        if not isinstance(item, dict):
            continue
        content = str(item.get("content", "")).strip()
        if not content:
            continue
        role = str(item.get("role", "UNKNOWN")).strip().upper() or "UNKNOWN"
        sender_name = str(item.get("senderName", "")).strip() or role
        created_at = format_timestamp_to_minute(item.get("createdAt"))
        history_entries.append((role, sender_name, content, created_at))

    if history_entries and history_entries[-1][0] == "USER" and history_entries[-1][2] == question.strip():
        history_entries = history_entries[:-1]

    if not history_entries:
        return "", 0
    history_lines = []
    visible_entries = history_entries[-window_size:]
    for role, sender_name, content, created_at in visible_entries:
        prefix = f"[{role}][{created_at}] " if created_at else f"[{role}] "
        history_lines.append(f"{prefix}{sender_name}: {content}")
    dropped_messages = max(0, len(history_entries) - len(visible_entries))
    while history_lines and len("\n".join(history_lines).encode("utf-8")) > MAX_PROMPT_HISTORY_BYTES:
        history_lines.pop(0)
        dropped_messages += 1
    return "\n".join(history_lines), dropped_messages


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


def truncate_text_to_bytes(text: str, max_bytes: int) -> str:
    if max_bytes <= 0:
        return ""
    encoded = text.encode("utf-8")
    if len(encoded) <= max_bytes:
        return text
    return encoded[:max_bytes].decode("utf-8", errors="ignore").rstrip()


def truncate_prompt_value(value: Any, max_bytes: int) -> tuple[Any, bool]:
    try:
        serialized = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        serialized = str(value)
    if len(serialized.encode("utf-8")) <= max_bytes:
        return value, False
    suffix = "...(truncated)"
    allowed_bytes = max(max_bytes - len(suffix.encode("utf-8")), 0)
    return truncate_text_to_bytes(serialized, allowed_bytes) + suffix, True


def build_runtime_context_block(
    question: str,
    question_created_at: str,
    conversation_history: str,
    tool_results: List[Dict[str, Any]],
    resume_input: Optional[Dict[str, Any]],
    shared_facts_payload: Dict[str, Any],
    shared_artifacts_payload: Dict[str, Any],
    agent_scope_payload: Dict[str, Any],
    loop_index: int,
) -> str:
    sections = [f"当前用户消息：\n[{question_created_at}] 用户: {question}"]
    if conversation_history:
        sections.append(f"会话记忆：\n{conversation_history}")
    if shared_facts_payload:
        sections.append(f"共享事实（facts）：\n{json.dumps(shared_facts_payload, ensure_ascii=False, indent=2)}")
    if shared_artifacts_payload:
        sections.append(f"共享产物（artifacts）：\n{json.dumps(shared_artifacts_payload, ensure_ascii=False, indent=2)}")
    if agent_scope_payload:
        sections.append(f"当前智能体私有上下文（agentScope）：\n{json.dumps(agent_scope_payload, ensure_ascii=False, indent=2)}")
    if tool_results:
        sections.append(f"工具结果：\n{json.dumps(tool_results, ensure_ascii=False, indent=2)}")
    if resume_input:
        sections.append(f"恢复输入：\n{json.dumps(resume_input, ensure_ascii=False, indent=2)}")
    sections.append(f"当前执行轮次：\n{loop_index}")
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


def tool_catalog_for_prompt(available_tools: List[AvailableTool]) -> List[Dict[str, Any]]:
    return [
        {
            "toolId": tool.toolId,
            "toolName": tool.toolName,
            "toolKind": tool.toolKind,
            "providerType": tool.providerType,
            "operation": tool.operation,
            "description": tool.description,
            "inputSchema": tool.inputSchema,
            "outputSchema": tool.outputSchema,
            "resourceId": tool.resourceId,
            "resourceVersionId": tool.resourceVersionId,
            "resourceName": tool.resourceName,
        }
        for tool in available_tools
    ]


def build_instruction_block() -> str:
    response_schema = {
        "decisionType": f"{AGENT_DECISION_FINAL} | {AGENT_DECISION_TOOL_CALL} | {AGENT_DECISION_SKILL_READ} | {AGENT_DECISION_HUMAN_HANDOFF}",
        "routeDecision": "string | null; required only when FINAL and no unique default route exists",
        "outputMessages": [
            {
                "payloadType": "TEXT | EXTERNAL_INTERACTION; only when decisionType=FINAL",
                "payload": "object; shape depends on payloadType",
            }
        ],
        "outputMessageExamples": {
            "TEXT": {
                "payloadType": "TEXT",
                "payload": {
                    "text": "string"
                },
            },
            "EXTERNAL_INTERACTION": {
                "payloadType": "EXTERNAL_INTERACTION",
                "payload": {
                    "spec": {
                        "interactionType": "GENERIC_REDIRECT | PAYMENT_REDIRECT | FORM_REDIRECT | OAUTH_REDIRECT | EXTERNAL_CONFIRMATION | FILE_UPLOAD_PORTAL",
                        "title": "string",
                        "instruction": "string",
                        "provider": "string | null",
                        "providerReference": "string | null",
                        "launchUrl": "string | null",
                        "returnPath": "string | null",
                        "expiresAt": "RFC3339 datetime string | null",
                        "primaryActionLabel": "string | null",
                        "secondaryActions": [
                            {
                                "label": "string",
                                "actionType": "string",
                                "url": "string | null",
                                "target": "string | null",
                                "parameters": {"key": "value"},
                                "disabled": "boolean",
                            }
                        ],
                        "displayHints": {"key": "value"},
                    }
                },
            },
        },
        "skillReads": ["skillResourceVersionId; only when decisionType=SKILL_READ or TOOL_CALL"],
        "toolRequests": [
            {
                "callId": "string; unique within this decision",
                "toolId": "string",
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
        "decisionSemantics": {
            AGENT_DECISION_FINAL: {
                "whenToUse": "You already have enough information to finish this node.",
                "must": ["Populate outputMessages with any user-facing outputs for this turn."],
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
            "Use builtin:knowledge_search before citing knowledge-base facts, and builtin:knowledge_read when you need full chunk content.",
            "Do not claim knowledge-base confirmation unless you actually called a knowledge tool in this node.",
            "Use sessionStatePatch to persist reusable session facts, artifacts, or your own agentScope.",
            "You can read facts/artifacts and only your own agentScope from the prompt. Do not assume access to other agents' scopes.",
            "For TEXT outputMessages, payload must be exactly {\"text\": \"...\"}. Do not wrap it as {\"TEXT\": {...}}.",
            "For EXTERNAL_INTERACTION outputMessages, payload must be exactly {\"spec\": {...}}. Do not wrap it as {\"EXTERNAL_INTERACTION\": {...}}.",
            "Do not copy customerId, userId, sessionId, workflowId, or similar context metadata into outputMessages payloads.",
            f"Use {AGENT_DECISION_SKILL_READ} for skill-only continuation turns.",
            f"Use {AGENT_DECISION_TOOL_CALL} for tool continuation turns.",
            f"Use {AGENT_DECISION_FINAL} only for a completed node decision.",
            f"Use {AGENT_DECISION_HUMAN_HANDOFF} only when a human must take over.",
            "outputMessages is incremental for this decision only; never repeat historical messages.",
            "At most one EXTERNAL_INTERACTION output is allowed, and if present it must be the last outputMessages item.",
            "If you emit EXTERNAL_INTERACTION, you must choose the routeDecision that should run after the external interaction resumes.",
            "Return JSON only.",
        ],
    }
    return (
        f"决策规则：\n{json.dumps(guidance, ensure_ascii=False, indent=2)}\n\n"
        f"请严格输出 JSON，字段结构如下：\n{json.dumps(response_schema, ensure_ascii=False, indent=2)}"
    )


def build_capability_block(
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    available_tools: List[AvailableTool],
    routes: List[Dict[str, Any]],
) -> str:
    sections: List[str] = []
    if routes:
        sections.append(f"可用路由：\n{json.dumps(routes, ensure_ascii=False, indent=2)}")
    tool_catalog = tool_catalog_for_prompt(available_tools)
    if tool_catalog:
        sections.append(f"可用工具：\n{json.dumps(tool_catalog, ensure_ascii=False, indent=2)}")
    if available_skills:
        sections.append(f"可用技能目录：\n{json.dumps(available_skills, ensure_ascii=False, indent=2)}")
    if loaded_skills:
        sections.append(f"已加载技能详情：\n{json.dumps(loaded_skills, ensure_ascii=False, indent=2)}")
    return "\n\n".join(sections)


def build_structured_agent_prompt(
    question: str,
    question_created_at: str,
    conversation_history: str,
    shared_facts_payload: Dict[str, Any],
    shared_artifacts_payload: Dict[str, Any],
    agent_scope_payload: Dict[str, Any],
    available_skills: List[Dict[str, str]],
    loaded_skills: List[Dict[str, str]],
    tool_results: List[Dict[str, Any]],
    resume_input: Optional[Dict[str, Any]],
    available_tools: List[AvailableTool],
    routes: List[Dict[str, Any]],
    loop_index: int,
) -> StructuredAgentPrompt:
    runtime_context_block = build_runtime_context_block(
        question,
        question_created_at,
        conversation_history,
        tool_results,
        resume_input,
        shared_facts_payload,
        shared_artifacts_payload,
        agent_scope_payload,
        loop_index,
    )
    return {
        "instruction_block": build_instruction_block(),
        "capability_block": build_capability_block(available_skills, loaded_skills, available_tools, routes),
        "runtime_context_block": runtime_context_block,
    }


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

def prompt_user_messages(prompt_payload: LlmPromptPayload) -> List[str]:
    return [
        block
        for block in [
            prompt_payload["instruction_block"],
            prompt_payload["capability_block"],
            prompt_payload["runtime_context_block"],
        ]
        if block.strip()
    ]


async def call_llm(model_resource: ResourceVersionSnapshot, prompt_payload: LlmPromptPayload) -> str:
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
            "llm request prepared provider=%s model=%s systemPromptChars=%s userPromptChars=%s historyTruncated=%s historyDroppedMessages=%s toolHistoryKeptItems=%s toolHistoryDroppedItems=%s toolHistoryArgumentsTruncated=%s toolHistoryResultsTruncated=%s",
            model_config.providerType,
            model_config.modelId,
            len(prompt_payload["system_prompt"]),
            sum(len(message) for message in prompt_user_messages(prompt_payload)),
            prompt_payload.get("history_truncated", False),
            prompt_payload.get("history_dropped_messages", 0),
            prompt_payload.get("tool_history_kept_items", 0),
            prompt_payload.get("tool_history_dropped_items", 0),
            prompt_payload.get("tool_history_arguments_truncated", 0),
            prompt_payload.get("tool_history_results_truncated", 0),
        )
        user_messages = prompt_user_messages(prompt_payload)
        async with httpx.AsyncClient(timeout=LLM_REQUEST_TIMEOUT_SECONDS) as client:
            if model_config.providerType in {"OPENAI", "OPENAI_COMPATIBLE"}:
                request_body = {
                    "model": model_config.modelId,
                    "temperature": model_config.temperature,
                    "max_tokens": model_config.maxTokens,
                    "messages": [{"role": "system", "content": prompt_payload["system_prompt"]}]
                    + [{"role": "user", "content": message} for message in user_messages],
                    "enable_thinking": False,
                }
                logger.warning(
                    "temporary llm request payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    json.dumps(request_body, ensure_ascii=False, indent=2),
                )
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/chat/completions",
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=request_body,
                )
                response.raise_for_status()
                reponse_json = response.json()
                logger.info(
                    "llm response received provider=%s model=%s choiceCount=%s",
                    model_config.providerType,
                    model_config.modelId,
                    len(reponse_json.get("choices", [])),
                )
                response_text = reponse_json["choices"][0]["message"]["content"]
                logger.warning(
                    "temporary llm response payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    response_text,
                )
                return response_text

            if model_config.providerType == "ANTHROPIC":
                request_body = {
                    "model": model_config.modelId,
                    "max_tokens": model_config.maxTokens,
                    "system": prompt_payload["system_prompt"],
                    "messages": [{"role": "user", "content": "\n\n".join(user_messages)}],
                }
                logger.warning(
                    "temporary llm request payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    json.dumps(request_body, ensure_ascii=False, indent=2),
                )
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/messages",
                    headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
                    json=request_body,
                )
                response.raise_for_status()
                response_text = response.json()["content"][0]["text"]
                logger.warning(
                    "temporary llm response payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    response_text,
                )
                return response_text

            if model_config.providerType == "GEMINI":
                request_body = {
                    "contents": [
                        {
                            "parts": [
                                {
                                    "text": "\n\n".join(
                                        [
                                            prompt_payload["system_prompt"],
                                            *user_messages,
                                        ]
                                    )
                                }
                            ]
                        }
                    ],
                    "generationConfig": {
                        "temperature": model_config.temperature,
                        "maxOutputTokens": model_config.maxTokens,
                    },
                }
                logger.warning(
                    "temporary llm request payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    json.dumps(request_body, ensure_ascii=False, indent=2),
                )
                response = await client.post(
                    f"{model_config.baseUrl.rstrip('/')}/models/{model_config.modelId}:generateContent?key={api_key}",
                    json=request_body,
                )
                response.raise_for_status()
                response_text = response.json()["candidates"][0]["content"]["parts"][0]["text"]
                logger.warning(
                    "temporary llm response payload provider=%s model=%s payload=%s",
                    model_config.providerType,
                    model_config.modelId,
                    response_text,
                )
                return response_text
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


def knowledge_service_base_url() -> str:
    return os.getenv("LYNXUS_KNOWLEDGE_SERVICE_BASE_URL", "http://127.0.0.1:8091").rstrip("/")


async def knowledge_search(binding: KnowledgeBindingSnapshot, arguments: Dict[str, Any]) -> Dict[str, Any]:
    query = str(arguments.get("query", "")).strip()
    if not query:
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_search.query is required")
    top_k_raw = arguments.get("topK", binding.defaultTopK)
    min_score_raw = arguments.get("minScore", binding.minScore)
    retrieval_mode_raw = arguments.get("retrievalMode", binding.retrievalMode)
    if not isinstance(top_k_raw, int) or isinstance(top_k_raw, bool):
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_search.topK must be an integer")
    if not isinstance(min_score_raw, (int, float)) or isinstance(min_score_raw, bool):
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_search.minScore must be a number")
    retrieval_mode = str(retrieval_mode_raw or binding.retrievalMode).strip().upper()
    if retrieval_mode not in {"LEXICAL", "VECTOR", "HYBRID"}:
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_search.retrievalMode must be LEXICAL, VECTOR, or HYBRID")
    auth_headers = {"Authorization": f"Bearer {internal_auth_token()}"}
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.post(
            f"{knowledge_service_base_url()}/internal/retrieve",
            headers=auth_headers,
            json={
                "indexSnapshotId": binding.snapshotId,
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
        "knowledgeBaseId": binding.knowledgeBaseId,
        "knowledgeBaseName": binding.knowledgeBaseName,
        "knowledgeReleaseId": binding.knowledgeReleaseId,
        "knowledgeReleaseVersion": binding.knowledgeReleaseVersion,
        "lowConfidence": bool(payload.get("lowConfidence", False)),
        "hits": hits if isinstance(hits, list) else [],
    }


async def knowledge_read(binding: KnowledgeBindingSnapshot, arguments: Dict[str, Any]) -> Dict[str, Any]:
    raw_chunk_ids = arguments.get("chunkIds", [])
    if not isinstance(raw_chunk_ids, list):
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_read.chunkIds must be a list")
    chunk_ids: List[str] = []
    for item in raw_chunk_ids:
        chunk_id = str(item).strip()
        if not chunk_id:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_read.chunkIds items must be non-empty strings")
        if chunk_id not in chunk_ids:
            chunk_ids.append(chunk_id)
    if not chunk_ids:
        raise AgentTurnError("TOOL_REQUEST_INVALID", "knowledge_read.chunkIds must not be empty")

    auth_headers = {"Authorization": f"Bearer {internal_auth_token()}"}
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.post(
            f"{knowledge_service_base_url()}/internal/read-chunks",
            headers=auth_headers,
            json={
                "indexSnapshotId": binding.snapshotId,
                "chunkIds": chunk_ids,
            },
        )
        response.raise_for_status()
    payload = response.json()
    chunks = payload.get("chunks", [])
    return {
        "knowledgeBaseId": binding.knowledgeBaseId,
        "knowledgeBaseName": binding.knowledgeBaseName,
        "knowledgeReleaseId": binding.knowledgeReleaseId,
        "knowledgeReleaseVersion": binding.knowledgeReleaseVersion,
        "chunks": chunks if isinstance(chunks, list) else [],
    }


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


def resolve_available_tool(available_tools: List[AvailableTool], tool_id: str) -> AvailableTool:
    for tool in available_tools:
        if tool.toolId == tool_id:
            return tool
    raise AgentTurnError("TOOL_REQUEST_INVALID", f"unknown toolId={tool_id}")


async def call_tool(
    available_tool: AvailableTool,
    payload: Dict[str, Any],
    knowledge_binding: Optional[KnowledgeBindingSnapshot],
    resource_index_by_version: Dict[str, ResourceVersionSnapshot],
) -> Dict[str, Any]:
    if available_tool.toolKind == TOOL_KIND_BUILTIN:
        if knowledge_binding is None:
            raise WorkflowFailureError(
                FAILURE_CATEGORY_CONFIGURATION,
                "KNOWLEDGE_BINDING_MISSING",
                f"builtin knowledge tool unavailable without knowledge binding: {available_tool.toolId}",
            )
        if available_tool.toolId == BUILTIN_TOOL_KNOWLEDGE_SEARCH:
            result = await knowledge_search(knowledge_binding, payload)
        elif available_tool.toolId == BUILTIN_TOOL_KNOWLEDGE_READ:
            result = await knowledge_read(knowledge_binding, payload)
        else:
            raise WorkflowFailureError(
                FAILURE_CATEGORY_CONFIGURATION,
                "TOOL_PROVIDER_UNSUPPORTED",
                f"unsupported builtin tool: {available_tool.toolId}",
            )
        validate_tool_result_for_available_tool(available_tool, result)
        return result

    resource_version_id = available_tool.resourceVersionId or ""
    resource = resource_index_by_version.get(resource_version_id)
    if resource is None:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_RESOURCE_MISSING",
            f"tool resource not found for {available_tool.toolId}",
        )
    config = resource.configuration.tool
    if config is None:
        raise WorkflowFailureError(
            FAILURE_CATEGORY_CONFIGURATION,
            "TOOL_CONFIGURATION_INVALID",
            f"resource {resource.resourceId} is not a tool",
            failed_resource=resource,
        )
    operation = resolve_tool_operation(resource, available_tool.operation)
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
    validate_tool_result_for_available_tool(available_tool, result)
    return result


def tool_history_for_prompt(state: AgentState) -> List[Dict[str, Any]]:
    rows, _ = tool_history_for_prompt_with_budget(state)
    return rows


def tool_history_for_prompt_with_budget(state: AgentState) -> tuple[List[Dict[str, Any]], Dict[str, int]]:
    prompt_rows: List[Dict[str, Any]] = []
    raw_history = state["tool_history"]
    kept_items = raw_history[-MAX_PROMPT_TOOL_HISTORY_ITEMS:]
    dropped_items = max(0, len(raw_history) - len(kept_items))
    arguments_truncated = 0
    results_truncated = 0
    for item in kept_items:
        if not isinstance(item, dict):
            continue
        arguments, arguments_was_truncated = truncate_prompt_value(
            item.get("arguments", {}),
            MAX_PROMPT_TOOL_ARGUMENT_BYTES,
        )
        result, result_was_truncated = truncate_prompt_value(
            item.get("result", {}),
            MAX_PROMPT_TOOL_RESULT_BYTES,
        )
        if arguments_was_truncated:
            arguments_truncated += 1
        if result_was_truncated:
            results_truncated += 1
        prompt_rows.append(
            {
                "callId": item.get("callId", ""),
                "toolId": item.get("toolId", ""),
                "toolName": item.get("toolName", ""),
                "toolKind": item.get("toolKind", ""),
                "providerType": item.get("providerType", ""),
                "resourceId": item.get("resourceId"),
                "resourceVersionId": item.get("resourceVersionId"),
                "resourceName": item.get("resourceName"),
                "operation": item.get("operation", ""),
                "arguments": arguments,
                "result": result,
            }
        )
    return prompt_rows, {
        "kept_items": len(prompt_rows),
        "dropped_items": dropped_items,
        "arguments_truncated": arguments_truncated,
        "results_truncated": results_truncated,
    }


def default_route_key(graph: GraphSnapshot, node_key: str) -> Optional[str]:
    defaults = [edge.routeKey for edge in graph.edges if edge.sourceNodeKey == node_key and edge.defaultEdge and edge.routeKey]
    if len(defaults) == 1:
        return defaults[0]
    return None


def validate_tool_result_for_available_tool(available_tool: AvailableTool, result: Any) -> None:
    if not isinstance(result, dict):
        raise AgentTurnError(
            "TOOL_RESPONSE_INVALID",
            f"tool {available_tool.toolName} must return a JSON object",
        )
    output_schema = available_tool.outputSchema.strip()
    if not output_schema:
        return
    try:
        schema = json.loads(output_schema)
    except json.JSONDecodeError as exc:
        raise AgentTurnError(
            "TOOL_SCHEMA_INVALID",
            f"tool {available_tool.toolName} has invalid outputSchema: {exc}",
        ) from exc
    validate_json_schema_value(result, schema, path="$")


def validate_tool_result(resource: ResourceVersionSnapshot, operation: ToolOperationConfig, result: Any) -> None:
    validate_tool_result_for_available_tool(
        AvailableTool(
            toolId=resource_tool_id(resource, operation.name),
            toolName=f"{resource.resourceName}.{operation.name}",
            toolKind=TOOL_KIND_RESOURCE,
            providerType=resource.configuration.tool.providerType if resource.configuration.tool else "UNKNOWN",
            operation=operation.name,
            description=operation.description,
            inputSchema=operation.inputSchema,
            outputSchema=operation.outputSchema,
            resourceId=resource.resourceId,
            resourceVersionId=resource.resourceVersionId,
            resourceName=resource.resourceName,
        ),
        result,
    )


async def retrieve_knowledge(binding: Optional[KnowledgeBindingSnapshot], question: str) -> List[Dict[str, Any]]:
    if binding is None or not binding.snapshotId.strip():
        return []
    result = await knowledge_search(binding, {"query": question})
    if result.get("lowConfidence"):
        return []
    hits = result.get("hits", [])
    return hits if isinstance(hits, list) else []


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
    available_tools: List[AvailableTool],
) -> List[ToolRequest]:
    allowed_tool_ids = {tool.toolId for tool in available_tools}
    if not isinstance(raw_requests, list):
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "toolRequests must be a list")
    normalized: List[ToolRequest] = []
    seen_call_ids: set[str] = set()
    for raw_request in raw_requests:
        if not isinstance(raw_request, dict):
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests items must be objects")
        call_id = str(raw_request.get("callId", "")).strip()
        tool_id = str(raw_request.get("toolId", "")).strip()
        arguments = raw_request.get("arguments", {})
        if not call_id:
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests.callId must be a non-empty string")
        if call_id in seen_call_ids:
            raise AgentTurnError("TOOL_REQUEST_INVALID", f"duplicate toolRequests.callId={call_id}")
        seen_call_ids.add(call_id)
        if tool_id not in allowed_tool_ids:
            raise AgentTurnError("TOOL_REQUEST_INVALID", f"unknown toolId={tool_id}")
        if not isinstance(arguments, dict):
            raise AgentTurnError("TOOL_REQUEST_INVALID", "toolRequests.arguments must be an object")
        normalized.append(
            ToolRequest(
                callId=call_id,
                toolId=tool_id,
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


def validate_output_message_payload(payload_type: str, payload: Dict[str, Any], path: str) -> None:
    if payload_type == "TEXT":
        extra_fields = sorted(key for key in payload.keys() if key != "text")
        if extra_fields:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload has unsupported fields: {', '.join(extra_fields)}")
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.text must be a non-empty string")
        return
    if payload_type == "EXTERNAL_INTERACTION":
        extra_payload_fields = sorted(key for key in payload.keys() if key != "spec")
        if extra_payload_fields:
            raise AgentTurnError(
                "MODEL_OUTPUT_INVALID",
                f"{path}.payload has unsupported fields: {', '.join(extra_payload_fields)}",
            )
        spec = payload.get("spec")
        if not isinstance(spec, dict):
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec must be an object")
        allowed_fields = {
            "interactionType",
            "title",
            "instruction",
            "provider",
            "providerReference",
            "launchUrl",
            "returnPath",
            "expiresAt",
            "primaryActionLabel",
            "secondaryActions",
            "displayHints",
        }
        required_fields = allowed_fields
        extra_spec_fields = sorted(key for key in spec.keys() if key not in allowed_fields)
        if extra_spec_fields:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec has unsupported fields: {', '.join(extra_spec_fields)}")
        missing = [field for field in required_fields if field not in spec]
        if missing:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec missing fields: {', '.join(sorted(missing))}")
        if str(spec.get("interactionType", "")).strip().upper() not in {
            "GENERIC_REDIRECT",
            "PAYMENT_REDIRECT",
            "FORM_REDIRECT",
            "OAUTH_REDIRECT",
            "EXTERNAL_CONFIRMATION",
            "FILE_UPLOAD_PORTAL",
        }:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.interactionType is invalid")
        if not isinstance(spec.get("title"), str) or not str(spec.get("title")).strip():
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.title must be a non-empty string")
        if not isinstance(spec.get("instruction"), str) or not str(spec.get("instruction")).strip():
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.instruction must be a non-empty string")
        for nullable_field in ["provider", "providerReference", "launchUrl", "returnPath", "expiresAt", "primaryActionLabel"]:
            value = spec.get(nullable_field)
            if value is not None and not isinstance(value, str):
                raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.{nullable_field} must be a string or null")
        secondary_actions = spec.get("secondaryActions")
        if not isinstance(secondary_actions, list):
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.secondaryActions must be a list")
        for index, action in enumerate(secondary_actions):
            if not isinstance(action, dict):
                raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.secondaryActions[{index}] must be an object")
        display_hints = spec.get("displayHints")
        if not isinstance(display_hints, dict):
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payload.spec.displayHints must be an object")
        return
    raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{path}.payloadType is unsupported: {payload_type}")


def sanitize_output_message_payload(payload_type: str, payload: Dict[str, Any], path: str) -> Dict[str, Any]:
    if payload_type == "TEXT":
        extra_fields = sorted(key for key in payload.keys() if key != "text")
        if extra_fields:
            logger.warning(
                "%s dropped unsupported TEXT payload fields: %s",
                path,
                ", ".join(extra_fields),
            )
        # TEXT messages are display-only. Strip leaked context metadata from model output
        # and keep only the field the runtime contract actually consumes.
        return {"text": payload.get("text")}
    return payload


def normalize_output_messages(raw_messages: Any) -> List[OutputMessageDraft]:
    if raw_messages is None:
        return []
    if not isinstance(raw_messages, list):
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "outputMessages must be a list")
    normalized: List[OutputMessageDraft] = []
    interaction_indexes: List[int] = []
    for index, raw_message in enumerate(raw_messages):
        try:
            message = OutputMessageDraft.model_validate(raw_message)
        except ValidationError as exc:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"outputMessages[{index}] is invalid: {exc}") from exc
        sanitized_payload = sanitize_output_message_payload(message.payloadType, message.payload, f"outputMessages[{index}]")
        validate_output_message_payload(message.payloadType, sanitized_payload, f"outputMessages[{index}]")
        message = OutputMessageDraft(payloadType=message.payloadType, payload=sanitized_payload)
        if message.payloadType == "EXTERNAL_INTERACTION":
            interaction_indexes.append(index)
        normalized.append(message)
    if len(interaction_indexes) > 1:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "at most one EXTERNAL_INTERACTION output is allowed")
    if interaction_indexes and interaction_indexes[0] != len(raw_messages) - 1:
        raise AgentTurnError("MODEL_OUTPUT_INVALID", "EXTERNAL_INTERACTION output must be the last outputMessages item")
    return normalized


def parse_agent_structured_response(
    llm_output: str,
    graph: GraphSnapshot,
    node: GraphNodeSnapshot,
    skill_resources: List[ResourceVersionSnapshot],
    available_tools: List[AvailableTool],
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
    tool_requests = normalize_tool_requests(parsed.get("toolRequests", []), available_tools)
    output_messages = normalize_output_messages(parsed.get("outputMessages", []))
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
    else:
        if output_messages:
            raise AgentTurnError("MODEL_OUTPUT_INVALID", f"{decision_type} decisionType must not include outputMessages")
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
        routeDecision=route_decision,
        outputMessages=output_messages,
        skillReads=skill_reads,
        toolRequests=tool_requests,
        humanRequest=human_request,
        sessionStatePatch=session_state_patch,
    )


def store_tool_result(
    state: AgentState,
    agent: AgentSnapshot,
    call_id: str,
    available_tool: AvailableTool,
    arguments: Dict[str, Any],
    result: Dict[str, Any],
) -> None:
    outcome_summary = build_tool_outcome(call_id, available_tool, result)
    state["tool_history"].append(
        {
            "agentId": agent.agentId,
            "callId": call_id,
            "toolId": available_tool.toolId,
            "toolName": available_tool.toolName,
            "toolKind": available_tool.toolKind,
            "providerType": available_tool.providerType,
            "resourceId": available_tool.resourceId,
            "resourceVersionId": available_tool.resourceVersionId,
            "resourceName": available_tool.resourceName,
            "operation": available_tool.operation,
            "arguments": arguments,
            "result": result,
            "createdAt": now_iso(),
        }
    )
    state["latest_tool_outcome"] = outcome_summary


def build_tool_outcome(call_id: str, available_tool: AvailableTool, result: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "callId": call_id,
        "toolId": available_tool.toolId,
        "toolName": available_tool.toolName,
        "toolKind": available_tool.toolKind,
        "operation": available_tool.operation,
        "providerType": available_tool.providerType,
        "resourceId": available_tool.resourceId,
        "resourceName": available_tool.resourceName,
        "result": result,
    }


def export_state(state: AgentState) -> Dict[str, Any]:
    return {
        "question": state["question"],
        "session_context": state["session_context"],
        "assistant": state["assistant"],
        "graph": state["graph"],
        "summary": state["summary"],
        "tool_history": state["tool_history"],
        "tool_calls": state["tool_calls"],
        "model_hits": state["model_hits"],
        "output_messages": state["output_messages"],
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
        "summary": data.get("summary", ""),
        "tool_history": data.get("tool_history", []),
        "tool_calls": data.get("tool_calls", []),
        "model_hits": data.get("model_hits", []),
        "output_messages": data.get("output_messages", []),
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


def pause_for_external_interaction(
    state: AgentState,
    node: GraphNodeSnapshot,
    route_key: str,
    turn_index: int,
    output_messages: List[OutputMessageDraft],
    detail_lines: List[str],
) -> None:
    emitted = emit_output_messages(state, node.nodeKey, turn_index, output_messages)
    interaction_message = emitted[-1]
    spec = interaction_message.payload.get("spec", {})
    if not isinstance(spec, dict):
        raise HTTPException(status_code=500, detail="external interaction spec is missing")
    next_node_key = resolve_next_node(graph_from_state(state), node.nodeKey, route_key)
    title = str(spec.get("title", "")).strip() or "需要外部交互"
    instruction = str(spec.get("instruction", "")).strip() or "请完成外部交互后返回。"
    expected_action = str(spec.get("primaryActionLabel", "")).strip() or "完成外部交互后继续流程"
    state["route_key"] = route_key
    pause_for_resume(
        state,
        node.nodeKey,
        node.nodeName,
        title,
        instruction,
        expected_action,
        PAUSE_SOURCE_EXTERNAL_INTERACTION,
        next_node_key or "end",
        "\n".join(detail_lines),
        allowed_actions=[RESUME_ACTION_CONTINUE],
        reason_code="EXTERNAL_INTERACTION_REQUIRED",
        reason_detail=instruction,
        resume_source=RESUME_SOURCE_EXTERNAL_SYSTEM,
        interaction_type=str(spec.get("interactionType", "")).strip().upper() or None,
    )
    state["summary"] = summarize_output_payload(interaction_message.payloadType, interaction_message.payload) or state["summary"]


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
    append_node(state, node_key, node_name, detail, status="CANCELLED")


async def execute_agent_node(state: AgentState, node: GraphNodeSnapshot) -> None:
    assistant = assistant_from_state(state)
    graph = graph_from_state(state)
    agent = find_agent(assistant, node.agentId)
    knowledge_binding = resolve_knowledge_binding(assistant, agent)
    skill_resources = resolve_skill_resources(assistant, agent)
    available_tools = available_tools_for_agent(assistant, agent, knowledge_binding)
    resource_index_by_version = resource_index(assistant)
    conversation_history, history_dropped_messages = build_conversation_history_with_budget(
        state["session_context"],
        state["question"],
        memory_window_for_agent(assistant, agent),
    )
    history_truncated = history_dropped_messages > 0

    detail_lines = [agent.responsibility]
    route_key: Optional[str] = None
    route_source = ""
    turn_logs: List[Dict[str, Any]] = []
    state["agent_turn_state"] = {
        "phase": "PREPARE_CONTEXT",
        "turnIndex": 0,
        "latestDecision": None,
        "turnLogs": turn_logs,
    }
    try:
        model_resource, model_source = resolve_model_resource(assistant, agent)
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

    for turn_index in range(1, AGENT_MAX_TURNS + 1):
        state["agent_turn_state"]["phase"] = "PREPARE_CONTEXT"
        state["agent_turn_state"]["turnIndex"] = turn_index
        available_skills = available_skill_catalog(skill_resources)
        loaded_skills = loaded_skill_details(skill_resources, state["session_context"])
        prompt_tool_history, tool_history_budget = tool_history_for_prompt_with_budget(state)
        prompt_blocks = build_structured_agent_prompt(
            state["question"],
            latest_message_timestamp_for_prompt(state["session_context"]),
            conversation_history,
            shared_facts(state["session_context"]),
            shared_artifacts(state["session_context"]),
            agent_scope(state["session_context"], agent.agentId),
            available_skills,
            loaded_skills,
            prompt_tool_history,
            state["resume_input"],
            available_tools,
            available_routes_for_prompt(graph, node.nodeKey),
            turn_index - 1,
        )
        prompt_payload: LlmPromptPayload = {
            "system_prompt": build_system_prompt(agent),
            **prompt_blocks,
            "history_truncated": history_truncated,
            "history_dropped_messages": history_dropped_messages,
            "tool_history_kept_items": tool_history_budget["kept_items"],
            "tool_history_dropped_items": tool_history_budget["dropped_items"],
            "tool_history_arguments_truncated": tool_history_budget["arguments_truncated"],
            "tool_history_results_truncated": tool_history_budget["results_truncated"],
        }
        state["agent_turn_state"]["phase"] = "CALL_MODEL"
        try:
            append_model_hit(state, node, agent, model_resource, model_source, turn_index)
            llm_output = await call_llm(model_resource, prompt_payload)
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
            structured = parse_agent_structured_response(llm_output, graph, node, skill_resources, available_tools)
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
                reason_detail=human_request.instruction,
            )
            return

        if structured.decisionType == AGENT_DECISION_TOOL_CALL:
            state["agent_turn_state"]["phase"] = "EXECUTE_TOOL_REQUESTS"
            for tool_request in structured.toolRequests:
                try:
                    available_tool = resolve_available_tool(available_tools, tool_request.toolId)
                except AgentTurnError as exc:
                    turn_log.phase = "FAIL"
                    turn_log.failureReason = exc.code
                    turn_logs.append(turn_log.model_dump(mode="json"))
                    state["agent_turn_state"]["turnLogs"] = turn_logs
                    detail_lines.append(turn_log_line(turn_logs[-1]))
                    log_turn_failure(state["workflow_instance_id"], node.nodeKey, turn_index, exc.code, exc.message)
                    failure = build_failure_snapshot(
                        FAILURE_CATEGORY_CONFIGURATION,
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
                        "工具不可用，需要人工介入",
                        f"{exc.code}: {exc.message}",
                        "请人工确认工具配置并继续处理",
                        "\n".join([*detail_lines, f"tool_error={exc.message}"]),
                        failure,
                    )
                    return
                tool_resource = resource_index_by_version.get(available_tool.resourceVersionId or "")
                try:
                    tool_result = await call_tool(available_tool, tool_request.arguments, knowledge_binding, resource_index_by_version)
                    store_tool_result(state, agent, tool_request.callId, available_tool, tool_request.arguments, tool_result)
                    record_tool_call(
                        state,
                        tool_request.callId,
                        available_tool.toolId,
                        available_tool.toolName,
                        available_tool.toolKind,
                        available_tool.providerType,
                        available_tool.operation,
                        "COMPLETED",
                        json.dumps(tool_result, ensure_ascii=False),
                        tool_resource,
                    )
                    turn_log.toolCallsDelta += 1
                except AgentTurnError as exc:
                    record_tool_call(
                        state,
                        tool_request.callId,
                        available_tool.toolId,
                        available_tool.toolName,
                        available_tool.toolKind,
                        available_tool.providerType,
                        available_tool.operation,
                        "FAILED",
                        exc.message,
                        tool_resource,
                    )
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
                    record_tool_call(
                        state,
                        tool_request.callId,
                        available_tool.toolId,
                        available_tool.toolName,
                        available_tool.toolKind,
                        available_tool.providerType,
                        available_tool.operation,
                        "FAILED",
                        exc.message,
                        tool_resource,
                    )
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
                    record_tool_call(
                        state,
                        tool_request.callId,
                        available_tool.toolId,
                        available_tool.toolName,
                        available_tool.toolKind,
                        available_tool.providerType,
                        available_tool.operation,
                        "FAILED",
                        str(exc),
                        tool_resource,
                    )
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

    detail_lines.append(f"loop_count={len(turn_logs)}")
    detail_lines.append(f"route_key={route_key}")
    if structured.outputMessages and structured.outputMessages[-1].payloadType == "EXTERNAL_INTERACTION":
        pause_for_external_interaction(state, node, route_key, turn_index, structured.outputMessages, detail_lines)
        return

    emitted = emit_output_messages(state, node.nodeKey, turn_index, structured.outputMessages)
    if not emitted and not state["summary"]:
        latest_outcome = state["latest_tool_outcome"] or {}
        latest_result = latest_outcome.get("result", {}) if isinstance(latest_outcome, dict) else {}
        state["summary"] = json.dumps(latest_result, ensure_ascii=False) if latest_result else agent.responsibility
    state["route_key"] = route_key
    state["next_node_key"] = resolve_next_node(graph, node.nodeKey, route_key)
    state["current_node_key"] = node.nodeKey
    state["escalation_required"] = False
    state["resume_task"] = None
    state["checkpoint"] = None
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
    if not state["summary"] and state["output_messages"]:
        latest_output = WorkflowOutputMessage.model_validate(state["output_messages"][-1])
        state["summary"] = summarize_output_payload(latest_output.payloadType, latest_output.payload) or "流程已完成。"
    state["summary"] = state["summary"] or "流程已完成。"
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
        summary=state["summary"] or "流程已执行。",
        currentNodeKey=state["current_node_key"],
        checkpoint=ExecutionCheckpoint(**state["checkpoint"]) if state["checkpoint"] else None,
        resumeTask=ResumeTaskSnapshot(**state["resume_task"]) if state["resume_task"] else None,
        pauseReason=PauseReasonSnapshot(**state["pause_reason"]) if state["pause_reason"] else None,
        latestFailure=WorkflowFailureSnapshot(**state["latest_failure"]) if state["latest_failure"] else None,
        nodes=[NodeSnapshot(**node) for node in state["node_snapshots"]],
        toolCalls=[ToolInvocationSnapshot(**tool) for tool in state["tool_calls"]],
        escalationRequired=state["escalation_required"],
        latestToolOutcome=ToolOutcomeSummary(**state["latest_tool_outcome"]) if state["latest_tool_outcome"] else None,
        modelHits=[ModelHitSnapshot(**hit) for hit in state["model_hits"]],
        outputMessages=[WorkflowOutputMessage(**message) for message in state["output_messages"]],
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
        "summary": "",
        "tool_history": [],
        "tool_calls": [],
        "model_hits": [],
        "output_messages": [],
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
