from __future__ import annotations

from typing import Any

from ..openai_compatible import LlmUsageTracker
from .policy import PrivacyPolicy, PrivacyStrategy
from .rewriter import PrivateLlmRewriter
from .rules import SanitizationResult, restore_value, sanitize_value
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

    def sanitize(
        self,
        payload: Any,
        channel: str,
        strategy: PrivacyStrategy = PrivacyStrategy.RULES_THEN_PRIVATE_LLM,
    ) -> Any:
        if not self._policy.enabled or strategy == PrivacyStrategy.SKIP:
            return payload
        result = sanitize_value(payload, self._store)
        if strategy == PrivacyStrategy.RULES_THEN_PRIVATE_LLM:
            rewritten = self._rewrite_private_leaves(result.value)
            if rewritten.total_replacement_count > 0:
                result = SanitizationResult(
                    value=rewritten.value,
                    entity_type_breakdown=_merge_breakdowns(
                        result.entity_type_breakdown,
                        rewritten.entity_type_breakdown,
                    ),
                    new_placeholder_count=result.new_placeholder_count + rewritten.new_placeholder_count,
                    total_replacement_count=result.total_replacement_count + rewritten.total_replacement_count,
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

    def _rewrite_private_leaves(self, value: Any) -> SanitizationResult:
        if isinstance(value, str):
            return self._rewrite_private_text(value)
        if isinstance(value, dict):
            rewritten_items: dict[str, Any] = {}
            totals: dict[str, int] = {}
            created = 0
            replacements = 0
            for key, item in value.items():
                nested = self._rewrite_private_leaves(item)
                rewritten_items[str(key)] = nested.value
                created += nested.new_placeholder_count
                replacements += nested.total_replacement_count
                totals = _merge_breakdowns(totals, nested.entity_type_breakdown)
            return SanitizationResult(rewritten_items, totals, created, replacements)
        if isinstance(value, list):
            rewritten_items: list[Any] = []
            totals: dict[str, int] = {}
            created = 0
            replacements = 0
            for item in value:
                nested = self._rewrite_private_leaves(item)
                rewritten_items.append(nested.value)
                created += nested.new_placeholder_count
                replacements += nested.total_replacement_count
                totals = _merge_breakdowns(totals, nested.entity_type_breakdown)
            return SanitizationResult(rewritten_items, totals, created, replacements)
        return SanitizationResult(value, {}, 0, 0)

    def _rewrite_private_text(self, text: str) -> SanitizationResult:
        if not text.strip():
            return SanitizationResult(text, {}, 0, 0)
        if not (collect_sensitive_entities(text) or self._rewriter.configured):
            return SanitizationResult(text, {}, 0, 0)
        rewritten = self._rewriter.rewrite(text, self._store)
        return SanitizationResult(
            rewritten.value,
            rewritten.entity_type_breakdown,
            rewritten.new_placeholder_count,
            rewritten.total_replacement_count,
        )

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


def _merge_breakdowns(first: dict[str, int], second: dict[str, int]) -> dict[str, int]:
    merged = dict(first)
    for entity_type, count in second.items():
        merged[entity_type] = merged.get(entity_type, 0) + count
    return merged
