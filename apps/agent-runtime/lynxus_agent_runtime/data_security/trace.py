from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime

from ..models import PrivacyMappingTelemetry
from .policy import PRIVACY_CHANNELS, PrivacyPolicy


def _blank_counts() -> dict[str, int]:
    return {channel: 0 for channel in PRIVACY_CHANNELS}


@dataclass
class MappingTrace:
    policy: PrivacyPolicy
    sanitize_count_by_channel: dict[str, int] = field(default_factory=_blank_counts)
    restore_count_by_channel: dict[str, int] = field(default_factory=_blank_counts)
    entity_type_breakdown: dict[str, int] = field(default_factory=dict)
    placeholder_count: int = 0
    unresolved_placeholder_count: int = 0
    blocked_event_count: int = 0
    last_processed_at: str | None = None

    def record_sanitize(self, channel: str, entity_type_breakdown: dict[str, int], new_placeholders: int) -> None:
        self.sanitize_count_by_channel[channel] = self.sanitize_count_by_channel.get(channel, 0) + 1
        for entity_type, count in entity_type_breakdown.items():
            self.entity_type_breakdown[entity_type] = self.entity_type_breakdown.get(entity_type, 0) + count
        self.placeholder_count += max(new_placeholders, 0)
        self.last_processed_at = datetime.now(UTC).isoformat()

    def record_restore(self, channel: str, unresolved_placeholders: int) -> None:
        self.restore_count_by_channel[channel] = self.restore_count_by_channel.get(channel, 0) + 1
        self.unresolved_placeholder_count += max(unresolved_placeholders, 0)
        self.last_processed_at = datetime.now(UTC).isoformat()

    def record_blocked(self) -> None:
        self.blocked_event_count += 1
        self.last_processed_at = datetime.now(UTC).isoformat()

    def telemetry(self) -> PrivacyMappingTelemetry:
        return PrivacyMappingTelemetry(
            enabled=self.policy.enabled,
            privacyModelResourceId=None if self.policy.model_binding is None else self.policy.model_binding.resourceId,
            privacyModelResourceName=None if self.policy.model_binding is None else self.policy.model_binding.resourceName,
            sanitizeCountByChannel=dict(self.sanitize_count_by_channel),
            restoreCountByChannel=dict(self.restore_count_by_channel),
            entityTypeBreakdown=dict(self.entity_type_breakdown),
            placeholderCount=self.placeholder_count,
            unresolvedPlaceholderCount=self.unresolved_placeholder_count,
            blockedEventCount=self.blocked_event_count,
            lastProcessedAt=self.last_processed_at,
        )
