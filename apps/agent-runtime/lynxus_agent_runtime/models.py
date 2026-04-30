from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .privacy_contracts import PrivacyMappingTelemetry


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


class ToolConnectorAccountSnapshot(BaseModel):
    accountId: str
    externalSecretRef: str | None = None


RetryPolicyMode = Literal["NONE", "FIXED", "EXPONENTIAL"]
RetryableErrorCategory = Literal[
    "AUTH",
    "BAD_REQUEST",
    "REMOTE_TIMEOUT",
    "REMOTE_UNAVAILABLE",
    "REMOTE_RATE_LIMITED",
    "REMOTE_BUSINESS_REJECTED",
    "PROTOCOL_ERROR",
    "CIRCUIT_OPEN",
    "UNKNOWN",
]


class ToolConnectorRuntimeRetryPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mode: RetryPolicyMode
    maxAttempts: int = Field(ge=1)
    initialDelayMs: int = Field(ge=0)
    maxDelayMs: int = Field(ge=0)
    backoffMultiplier: float = Field(ge=1.0)
    retryableCategories: list[RetryableErrorCategory] = Field(default_factory=list)
    retryableErrorCodes: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_none_policy(self) -> "ToolConnectorRuntimeRetryPolicy":
        if self.mode == "NONE" and self.maxAttempts != 1:
            raise ValueError("retryPolicy.mode NONE requires maxAttempts=1")
        return self


class ToolConnectorDescriptor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    connectorType: str
    accountSnapshot: ToolConnectorAccountSnapshot | None = None
    timeoutSeconds: int = 15
    retryPolicy: ToolConnectorRuntimeRetryPolicy
    config: dict[str, Any] = Field(default_factory=dict)
    operationMappings: dict[str, dict[str, Any]] = Field(default_factory=dict)


class ToolDescriptor(BaseModel):
    resourceId: str
    resourceName: str
    resourceVersionId: str
    resourceVersion: str
    operations: list[ToolOperationDescriptor] = Field(default_factory=list)
    connector: ToolConnectorDescriptor | None = None

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


class SessionMessageSender(BaseModel):
    senderType: Literal["CUSTOMER", "AGENT", "HUMAN_OPERATOR", "SYSTEM"]
    senderId: str | None = None
    senderName: str | None = None


class TextMessageBlock(BaseModel):
    type: Literal["TEXT"]
    text: str


class ImageMessageBlock(BaseModel):
    type: Literal["IMAGE"]
    url: str
    mimeType: str | None = None
    width: int | None = None
    height: int | None = None
    alt: str | None = None


class RichTextMessageBlock(BaseModel):
    type: Literal["RICH_TEXT"]
    format: Literal["MARKDOWN"]
    content: str


class CardLinkAction(BaseModel):
    actionType: Literal["LINK"]
    label: str
    url: str


class CardMessageBlock(BaseModel):
    type: Literal["CARD"]
    cardType: str
    version: str
    data: dict[str, Any] = Field(default_factory=dict)
    actions: list[CardLinkAction] = Field(default_factory=list)


SessionMessageBlock = TextMessageBlock | ImageMessageBlock | RichTextMessageBlock | CardMessageBlock


class SessionMessageInput(BaseModel):
    blocks: list[SessionMessageBlock] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class SessionMessage(BaseModel):
    messageId: str
    sessionId: str
    sequence: int
    role: Literal["USER", "ASSISTANT", "HUMAN_OPERATOR", "SYSTEM"]
    sender: SessionMessageSender
    status: Literal["SENT", "STREAMING", "DELIVERED", "FAILED"]
    blocks: list[SessionMessageBlock] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
    relatedPlaybookRunId: str | None = None
    relatedOwnerAgentId: str | None = None
    sourceEventId: str | None = None
    createdAt: str
    updatedAt: str


class SessionTrigger(BaseModel):
    triggerType: Literal["USER_MESSAGE", "PLAYBOOK_COMPLETED"]
    eventId: str
    triggerMessageId: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


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
    relatedMessageId: str | None = None
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
    replyMessage: SessionMessageInput | None = None
    targetAgentId: str | None = None
    playbookId: str | None = None
    playbookInput: dict[str, Any] | None = None
    accompanyingMessage: SessionMessageInput | None = None

    @model_validator(mode="after")
    def validate_action_payload(self) -> "AgentDecision":
        if self.action == "REPLY" and not _has_message_content(self.replyMessage):
            raise ValueError("REPLY requires replyMessage")
        if self.action == "SWITCH_OWNER" and not (self.targetAgentId or "").strip():
            raise ValueError("SWITCH_OWNER requires targetAgentId")
        if self.action == "RUN_PLAYBOOK" and not (self.playbookId or "").strip():
            raise ValueError("RUN_PLAYBOOK requires playbookId")
        if self.action == "RUN_PLAYBOOK" and self.playbookInput is None:
            raise ValueError("RUN_PLAYBOOK requires playbookInput")
        return self


class SecurityAssessment(BaseModel):
    action: Literal["ALLOW", "BLOCK"] = "ALLOW"
    categories: list[str] = Field(default_factory=list)
    reason: str | None = None
    confidence: float | None = None


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
    recentMessages: list[SessionMessage] = Field(default_factory=list)
    recentEvents: list[SessionEvent] = Field(default_factory=list)


class AgentTurnResult(BaseModel):
    decision: AgentDecision
    sharedState: dict[str, Any] = Field(default_factory=dict)
    mappingTelemetry: PrivacyMappingTelemetry | None = None
    securityAssessment: SecurityAssessment | None = None


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


def _has_message_content(message: SessionMessageInput | None) -> bool:
    if message is None:
        return False
    for block in message.blocks:
        if isinstance(block, TextMessageBlock) and block.text.strip():
            return True
        if isinstance(block, RichTextMessageBlock) and block.content.strip():
            return True
        if isinstance(block, (ImageMessageBlock, CardMessageBlock)):
            return True
    return False
