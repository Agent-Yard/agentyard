from __future__ import annotations

from typing import Any

from ..openai_compatible import LlmUsageTracker
from .rewriter import PrivateLlmRewriter
from .policy import PrivacyPolicy
from .rules import restore_value, sanitize_value
from .store import SessionPrivacyMapStore
from .trace import MappingTrace
from .validator import (
    PrivacyMappingBlockedError,
    collect_sensitive_entities,
    unresolved_placeholder_count,
    validate_sanitized_output,
)


class PrivacyMapper:
    def __init__(
        self,
        policy: PrivacyPolicy,
        store: SessionPrivacyMapStore,
        trace: MappingTrace,
        usage_tracker: LlmUsageTracker | None = None,
    ) -> None:
        self._policy = policy
        self._store = store
        self._trace = trace
        self._rewriter = PrivateLlmRewriter(policy.model_binding, usage_tracker)

    def sanitize(self, payload: Any, channel: str) -> Any:
        if not self._policy.enabled:
            return payload
        sensitive_entities = collect_sensitive_entities(payload)
        result = sanitize_value(payload, self._store)
        if isinstance(result.value, str) and result.new_placeholder_count == 0 and sensitive_entities:
            rewritten = self._rewriter.rewrite(result.value, self._store)
            if rewritten.new_placeholder_count > 0:
                result = type(result)(
                    value=rewritten.value,
                    entity_type_breakdown=rewritten.entity_type_breakdown,
                    new_placeholder_count=rewritten.new_placeholder_count,
                )
        try:
            validate_sanitized_output(payload, result.value)
        except PrivacyMappingBlockedError:
            self._store.record_blocked()
            self._trace.record_blocked()
            raise
        self._store.record_sanitize(channel, result.new_placeholder_count, result.entity_type_breakdown)
        self._trace.record_sanitize(channel, result.entity_type_breakdown, result.new_placeholder_count)
        return result.value

    def restore(self, payload: Any, channel: str) -> Any:
        if not self._policy.enabled:
            return payload
        restored = restore_value(payload, self._store)
        unresolved = unresolved_placeholder_count(restored)
        self._store.record_restore(channel, unresolved)
        self._trace.record_restore(channel, unresolved)
        if unresolved:
            self._store.record_blocked()
            self._trace.record_blocked()
            raise ValueError("privacy restore left unresolved placeholders")
        return restored
