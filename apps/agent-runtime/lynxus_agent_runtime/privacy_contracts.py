from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from pydantic import BaseModel, Field

PRIVACY_FRAGMENT_CACHE_VERSION = "agent-runtime-privacy-v2"

PRIVACY_CHANNELS = (
    "PROMPT_INSTRUCTION",
    "PROMPT_RUNTIME_MESSAGE",
    "SKILL_PAYLOAD",
    "MODEL_TOOL_ARGUMENT",
    "TOOL_RESULT",
    "MODEL_FINAL_RESPONSE",
)


class PrivacyStrategy(StrEnum):
    SKIP = "SKIP"
    RULES_ONLY = "RULES_ONLY"
    RULES_THEN_PRIVATE_LLM = "RULES_THEN_PRIVATE_LLM"


@dataclass(frozen=True)
class PrivacyModelBinding:
    resource_id: str
    resource_name: str
    resource_version_id: str
    provider_type: str
    model_id: str
    base_url: str
    api_key_env_var: str
    private_deployment: bool
    enable_thinking: bool | None = None
    reasoning_effort: str | None = None


@dataclass(frozen=True)
class PrivacyPolicy:
    session_id: str
    assistant_id: str
    agent_id: str
    enabled: bool
    model_binding: PrivacyModelBinding | None


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
