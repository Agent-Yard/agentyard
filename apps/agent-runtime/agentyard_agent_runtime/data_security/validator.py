from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .detectors import PLACEHOLDER_PATTERN, detect_sensitive_text


class PrivacyMappingBlockedError(RuntimeError):
    """Raised when privacy mapping cannot guarantee safe outbound content."""


@dataclass(frozen=True)
class SensitiveEntity:
    entity_type: str
    raw_value: str


def collect_placeholders(value: Any) -> list[str]:
    placeholders: list[str] = []
    if isinstance(value, str):
        placeholders.extend(match.group(0) for match in PLACEHOLDER_PATTERN.finditer(value))
    elif isinstance(value, dict):
        for item in value.values():
            placeholders.extend(collect_placeholders(item))
    elif isinstance(value, list):
        for item in value:
            placeholders.extend(collect_placeholders(item))
    return placeholders


def unresolved_placeholder_count(value: Any) -> int:
    return len(collect_placeholders(value))


def collect_sensitive_entities(value: Any, key_hint: str | None = None) -> list[SensitiveEntity]:
    if isinstance(value, str):
        return _collect_sensitive_text(value, key_hint)
    if isinstance(value, dict):
        entities: list[SensitiveEntity] = []
        for key, item in value.items():
            entities.extend(collect_sensitive_entities(item, str(key)))
        return _dedupe_entities(entities)
    if isinstance(value, list):
        entities: list[SensitiveEntity] = []
        for item in value:
            entities.extend(collect_sensitive_entities(item, key_hint))
        return _dedupe_entities(entities)
    return []


def validate_sanitized_output(original: Any, sanitized: Any) -> None:
    entities = collect_sensitive_entities(original)
    if not entities:
        return

    leaked_values = [
        entity.raw_value
        for entity in entities
        if _contains_raw_value(sanitized, entity.raw_value)
    ]
    if leaked_values:
        leaked_preview = ", ".join(repr(value) for value in leaked_values[:3])
        raise PrivacyMappingBlockedError(f"privacy sanitize leaked raw values: {leaked_preview}")

    if not collect_placeholders(sanitized):
        raise PrivacyMappingBlockedError("privacy sanitize did not produce placeholders for sensitive content")


def _collect_sensitive_text(text: str, key_hint: str | None) -> list[SensitiveEntity]:
    return _dedupe_entities(
        [
            SensitiveEntity(entity.entity_type, entity.raw_value)
            for entity in detect_sensitive_text(text)
        ]
    )


def _contains_raw_value(value: Any, raw_value: str) -> bool:
    if not raw_value:
        return False
    if isinstance(value, str):
        return raw_value in value
    if isinstance(value, dict):
        return any(_contains_raw_value(item, raw_value) for item in value.values())
    if isinstance(value, list):
        return any(_contains_raw_value(item, raw_value) for item in value)
    return False


def _dedupe_entities(items: list[SensitiveEntity]) -> list[SensitiveEntity]:
    deduped: list[SensitiveEntity] = []
    seen: set[tuple[str, str]] = set()
    for entity in items:
        key = (entity.entity_type, entity.raw_value)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(entity)
    return deduped
