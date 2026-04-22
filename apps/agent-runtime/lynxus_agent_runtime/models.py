from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


class AgentConfig(BaseModel):
    agentId: str
    name: str
    role: str
    responsibility: str
    model: "LlmModelDescriptor | None" = None
    effectivePrivacyModelBinding: "LlmModelDescriptor | None" = None
    effectivePrivacyMappingEnabled: bool = False
    systemPrompt: str = ""
    knowledgeEnabled: bool = False
    knowledgeBaseId: str | None = None
    knowledgeBinding: "KnowledgeBindingDescriptor | None" = None
    memoryWindowSize: int = 0
    canOwnSession: bool = True
    allowedActions: list[str] = Field(default_factory=list)
    switchableOwnerAgentIds: list[str] = Field(default_factory=list)
    playbookIds: list[str] = Field(default_factory=list)
    skills: list["SkillDescriptor"] = Field(default_factory=list)
    tools: list["ToolDescriptor"] = Field(default_factory=list)


class LlmModelDescriptor(BaseModel):
    resourceId: str
    resourceName: str
    resourceVersionId: str
    resourceVersion: str
    providerType: str
    modelId: str
    baseUrl: str
    apiKeyEnvVar: str
    temperature: float = 0
    maxTokens: int = 0
    privateDeployment: bool = False


class SkillDescriptor(BaseModel):
    resourceId: str
    resourceName: str
    resourceVersionId: str
    resourceVersion: str
    skillName: str
    skillDesc: str = ""
    skillPrompt: str = ""


class KnowledgeBindingDescriptor(BaseModel):
    knowledgeBaseId: str
    knowledgeBaseName: str
    knowledgeReleaseId: str
    knowledgeReleaseVersion: str
    snapshotId: str
    defaultTopK: int
    retrievalMode: str = "HYBRID"
    minScore: float = 0.1


class ToolOperationDescriptor(BaseModel):
    name: str
    description: str = ""
    inputSchema: str = ""
    outputSchema: str = ""


class HttpToolProviderDescriptor(BaseModel):
    endpoint: str
    method: str = "POST"


class McpToolProviderDescriptor(BaseModel):
    serverName: str
    transport: str
    connectionUri: str
    namespace: str = ""
    heartbeatSeconds: int = 30
    operationMappings: dict[str, str] = Field(default_factory=dict)


class ToolDescriptor(BaseModel):
    resourceId: str
    resourceName: str
    resourceVersionId: str
    resourceVersion: str
    operations: list[ToolOperationDescriptor] = Field(default_factory=list)
    providerType: str
    authType: str = ""
    timeoutSeconds: int = 15
    retryPolicy: str = "NONE"
    http: HttpToolProviderDescriptor | None = None
    mcp: McpToolProviderDescriptor | None = None

class PlaybookConfig(BaseModel):
    playbookId: str
    name: str
    description: str = ""
    inputSchema: str = ""
    resultSchema: str = ""


class ActivePlaybookSummary(BaseModel):
    runId: str
    playbookId: str
    playbookName: str
    status: str
    waitingReason: str | None = None
    latestResult: dict[str, Any] = Field(default_factory=dict)


class SessionTrigger(BaseModel):
    triggerType: Literal["USER_MESSAGE", "PLAYBOOK_COMPLETED"]
    eventId: str
    payload: dict[str, Any] = Field(default_factory=dict)


class PrivacyMappingTelemetry(BaseModel):
    enabled: bool = False
    privacyModelResourceId: str | None = None
    privacyModelResourceName: str | None = None
    sanitizeCountByChannel: dict[str, int] = Field(default_factory=dict)
    restoreCountByChannel: dict[str, int] = Field(default_factory=dict)
    entityTypeBreakdown: dict[str, int] = Field(default_factory=dict)
    placeholderCount: int = 0
    unresolvedPlaceholderCount: int = 0
    blockedEventCount: int = 0
    lastProcessedAt: str | None = None


class LlmUsageEntry(BaseModel):
    sourceType: Literal["SESSION_OWNER_MODEL", "SESSION_PRIVACY_MODEL"]
    callSequence: int
    toolLoopStep: int = 0
    providerType: str
    modelResourceId: str | None = None
    modelResourceVersionId: str | None = None
    modelId: str
    usageAvailable: bool = False
    promptTokens: int | None = None
    completionTokens: int | None = None
    totalTokens: int | None = None
    rawUsage: dict[str, Any] = Field(default_factory=dict)
    occurredAt: str


class SessionEvent(BaseModel):
    eventId: str
    sessionId: str
    sequence: int
    eventType: str
    actorType: str
    actorId: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)
    relatedPlaybookRunId: str | None = None
    relatedOwnerAgentId: str | None = None


class AgentDecision(BaseModel):
    action: Literal[
        "REPLY",
        "NO_REPLY",
        "SWITCH_OWNER",
        "RUN_PLAYBOOK",
        "SESSION_HUMAN_HANDOFF",
    ]
    replyContent: str | None = None
    targetAgentId: str | None = None
    playbookId: str | None = None
    playbookInput: dict[str, Any] | None = None
    accompanyingReply: str | None = None

    @model_validator(mode="after")
    def validate_action_payload(self) -> "AgentDecision":
        if self.action == "REPLY" and not (self.replyContent or "").strip():
            raise ValueError("REPLY requires replyContent")
        if self.action == "SWITCH_OWNER" and not (self.targetAgentId or "").strip():
            raise ValueError("SWITCH_OWNER requires targetAgentId")
        if self.action == "RUN_PLAYBOOK" and not (self.playbookId or "").strip():
            raise ValueError("RUN_PLAYBOOK requires playbookId")
        if self.action == "RUN_PLAYBOOK" and self.playbookInput is None:
            raise ValueError("RUN_PLAYBOOK requires playbookInput")
        return self


class AgentTurnRequest(BaseModel):
    sessionId: str
    assistantId: str
    assistantReleaseVersion: str
    currentOwner: AgentConfig
    availableAgents: list[AgentConfig] = Field(default_factory=list)
    availablePlaybooks: list[PlaybookConfig] = Field(default_factory=list)
    activePlaybook: ActivePlaybookSummary | None = None
    sharedState: dict[str, Any] = Field(default_factory=dict)
    effectivePrivacyModelBinding: LlmModelDescriptor | None = None
    effectivePrivacyMappingEnabled: bool = False
    trigger: SessionTrigger
    recentEvents: list[SessionEvent] = Field(default_factory=list)


class AgentTurnResult(BaseModel):
    decision: AgentDecision
    sharedState: dict[str, Any] = Field(default_factory=dict)
    mappingTelemetry: PrivacyMappingTelemetry | None = None


class AgentTurnExecutionOutcome(BaseModel):
    success: bool
    result: AgentTurnResult | None = None
    failureReason: str | None = None
    llmUsage: list[LlmUsageEntry] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_outcome(self) -> "AgentTurnExecutionOutcome":
        if self.success and self.result is None:
            raise ValueError("successful outcome requires result")
        if not self.success and not (self.failureReason or "").strip():
            raise ValueError("failed outcome requires failureReason")
        return self


class PlaybookToolTaskRequest(BaseModel):
    sessionId: str
    playbookRunId: str
    playbookId: str
    nodeKey: str
    nodeName: str
    ownerAgent: AgentConfig
    toolId: str
    toolOperation: str
    input: dict[str, Any] = Field(default_factory=dict)
    config: dict[str, Any] = Field(default_factory=dict)


class PlaybookToolTaskResult(BaseModel):
    statePatch: dict[str, Any] = Field(default_factory=dict)
    routeKey: str | None = None
    terminalStatus: Literal["RUNNING", "WAITING", "SUCCEEDED", "FAILED", "CANCELLED"] | None = None
    failureReason: str | None = None


AgentConfig.model_rebuild()
