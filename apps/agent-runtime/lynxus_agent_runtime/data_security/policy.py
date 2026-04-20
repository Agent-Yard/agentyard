from __future__ import annotations

from dataclasses import dataclass

from ..models import AgentTurnRequest, LlmModelDescriptor

PRIVACY_CHANNELS = (
    "PROMPT_INSTRUCTION",
    "PROMPT_RUNTIME_MESSAGE",
    "SKILL_PAYLOAD",
    "MODEL_TOOL_ARGUMENT",
    "TOOL_RESULT",
    "MODEL_FINAL_RESPONSE",
)


@dataclass(frozen=True)
class PrivacyPolicy:
    session_id: str
    assistant_id: str
    agent_id: str
    enabled: bool
    model_binding: LlmModelDescriptor | None

    @classmethod
    def from_request(cls, request: AgentTurnRequest) -> "PrivacyPolicy":
        binding = request.effectivePrivacyModelBinding or request.currentOwner.effectivePrivacyModelBinding
        enabled = bool(request.effectivePrivacyMappingEnabled or request.currentOwner.effectivePrivacyMappingEnabled)
        return cls(
            session_id=request.sessionId,
            assistant_id=request.assistantId,
            agent_id=request.currentOwner.agentId,
            enabled=enabled and binding is not None,
            model_binding=binding,
        )
