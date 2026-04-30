from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .privacy_contracts import PrivacyStrategy
from .semantic import SemanticMessage


@dataclass(frozen=True)
class PromptBundle:
    instruction: str
    runtime_messages: list[SemanticMessage]
    capabilities: dict[str, Any]
    response_contract: dict[str, Any]
    instruction_privacy_strategy: PrivacyStrategy = PrivacyStrategy.RULES_ONLY
    capabilities_privacy_strategy: PrivacyStrategy = PrivacyStrategy.SKIP
    response_contract_privacy_strategy: PrivacyStrategy = PrivacyStrategy.SKIP
