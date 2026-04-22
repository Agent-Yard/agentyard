from __future__ import annotations

from typing import Any

from ..models import AgentTurnRequest, PrivacyMappingTelemetry
from ..openai_compatible import LlmUsageTracker
from .mapper import PrivacyMapper
from .policy import PrivacyPolicy
from .store import SessionPrivacyMapStore
from .trace import MappingTrace


class PrivacyPipeline:
    def __init__(self, policy: PrivacyPolicy, usage_tracker: LlmUsageTracker | None = None) -> None:
        self._policy = policy
        self._store = SessionPrivacyMapStore(policy) if policy.enabled else None
        self._trace = MappingTrace(policy)
        self._mapper = None if self._store is None else PrivacyMapper(policy, self._store, self._trace, usage_tracker)

    @property
    def enabled(self) -> bool:
        return self._policy.enabled

    def sanitize_outbound(self, channel: str, payload: Any) -> Any:
        if self._mapper is None:
            return payload
        return self._mapper.sanitize(payload, channel)

    def restore_inbound(self, channel: str, payload: Any) -> Any:
        if self._mapper is None:
            return payload
        return self._mapper.restore(payload, channel)

    def telemetry(self) -> PrivacyMappingTelemetry | None:
        return self._trace.telemetry()

    def close(self) -> None:
        if self._store is not None:
            self._store.close()


def build_privacy_pipeline(
    request: AgentTurnRequest,
    usage_tracker: LlmUsageTracker | None = None,
) -> PrivacyPipeline:
    return PrivacyPipeline(PrivacyPolicy.from_request(request), usage_tracker)
