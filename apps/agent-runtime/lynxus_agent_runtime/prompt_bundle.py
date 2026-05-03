from __future__ import annotations

from dataclasses import dataclass

from .privacy_contracts import PrivacyStrategy
from .semantic import SemanticMessage


@dataclass(frozen=True)
class PromptBundle:
    instruction: str
    runtime_messages: list[SemanticMessage]
    capability_summary: str
    instruction_privacy_strategy: PrivacyStrategy = PrivacyStrategy.RULES_ONLY
    capability_summary_privacy_strategy: PrivacyStrategy = PrivacyStrategy.SKIP
