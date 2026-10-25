from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .privacy_contracts import PrivacyMappingTelemetry


class AgentProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str
    role: str
    responsibility: str
    model: "LlmModelDescriptor | None" = None
    effectivePrivacyModelBinding: "LlmModelDescriptor | None" = None
    effectivePrivacyMappingEnabled: bool = False
    systemPrompt: str = ""
    knowledgeBinding: "KnowledgeBindingDescriptor | None" = None
    memoryWindowSize: int = 0
    playbooks: list["PlaybookConfig"] = Field(default_factory=list)
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
    enableThinking: bool | None = None
    reasoningEffort: str | None = None


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


MessageBlock = TextMessageBlock | ImageMessageBlock | RichTextMessageBlock | CardMessageBlock


class MessageContent(BaseModel):
    blocks: list[MessageBlock] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class LlmUsageEntry(BaseModel):
    sourceType: Literal["AGENT_MODEL", "PRIVACY_MODEL"]
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


class SecurityAssessment(BaseModel):
    action: Literal["ALLOW", "BLOCK"] = "ALLOW"
    categories: list[str] = Field(default_factory=list)
    reason: str | None = None
    confidence: float | None = None


class CollaborationMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str
    senderId: str
    recipientId: str
    taskId: str | None = None
    replyToMessageId: str | None = None
    kind: Literal["MESSAGE", "DELEGATION", "RESULT", "QUESTION", "EVENT", "CONTROL"]
    body: dict[str, Any] = Field(default_factory=dict)
    createdAt: str


class ActivationStart(BaseModel):
    model_config = ConfigDict(extra="forbid")
    activationId: str


class ActivationRuntime(BaseModel):
    model_config = ConfigDict(extra="forbid")
    activationId: str
    sessionId: str
    participantId: str
    templateVersionId: str
    agent: AgentProfile
    trigger: CollaborationMessage
    context: dict[str, Any] = Field(default_factory=dict)
    workMemory: dict[str, Any] = Field(default_factory=dict)
    maxToolSteps: int = 32


class ActivationOutcome(BaseModel):
    model_config = ConfigDict(extra="forbid")
    success: bool
    replyMessage: MessageContent | None = None
    workMemory: dict[str, Any] = Field(default_factory=dict)
    failureReason: str | None = None
    llmUsage: list[LlmUsageEntry] = Field(default_factory=list)
    mappingTelemetry: PrivacyMappingTelemetry | None = None
    securityAssessment: SecurityAssessment | None = None
    completion: Literal["COMPLETED", "WAITING", "BLOCKED"] | None = None
    retryable: bool = False

    @model_validator(mode="after")
    def validate_outcome(self) -> "ActivationOutcome":
        if not self.success and not (self.failureReason or "").strip():
            raise ValueError("failed outcome requires failureReason")
        if self.success and self.retryable:
            raise ValueError("a completed activation cannot request a retry")
        if self.success and self.completion is None:
            raise ValueError("successful outcome requires an explicit completion disposition")
        return self


StreamVisibility = Literal["CUSTOMER", "OPERATOR", "DEVELOPER", "INTERNAL"]
ActivationStreamFrameKind = Literal[
    "TURN_STARTED",
    "MODEL_STARTED",
    "MODEL_COMPLETED",
    "ACTION_TOOL_STARTED",
    "ACTION_TOOL_COMPLETED",
    "REPLY_BLOCK_DELTA",
    "REPLY_BLOCK_COMPLETED",
    "FINAL_OUTCOME",
    "ERROR",
]


class ActivationStreamFrame(BaseModel):
    protocol: Literal["agentyard.activation-stream.v1"] = "agentyard.activation-stream.v1"
    frameId: str
    streamId: str
    sessionId: str
    activationId: str
    participantId: str
    seq: int
    kind: ActivationStreamFrameKind
    visibility: StreamVisibility
    occurredAt: str
    payload: dict[str, Any]


class ToolRunNodeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    toolRunId: str
    nodeKey: str
    executionId: str
    input: dict[str, Any] = Field(default_factory=dict)


class ResolvedToolNode(BaseModel):
    """Trusted API configuration used only inside the runtime."""
    sessionId: str
    participantId: str
    activationId: str
    toolRunId: str
    nodeKey: str
    nodeName: str
    executionId: str
    agent: AgentProfile
    toolId: str
    toolOperation: str
    input: dict[str, Any] = Field(default_factory=dict)
    config: dict[str, Any] = Field(default_factory=dict)


class PlaybookToolTaskResult(BaseModel):
    statePatch: dict[str, Any] = Field(default_factory=dict)
    routeKey: str | None = None
    terminalStatus: Literal["RUNNING", "WAITING", "SUCCEEDED", "FAILED", "CANCELLED"] | None = None
    failureReason: str | None = None


AgentProfile.model_rebuild()


def _has_message_content(message: MessageContent | None) -> bool:
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
