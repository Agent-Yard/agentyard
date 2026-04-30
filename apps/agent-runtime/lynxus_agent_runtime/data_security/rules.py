from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from .store import MappingEntry, SessionPrivacyMapStore
from .validator import PLACEHOLDER_PATTERN

EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
PHONE_PATTERN = re.compile(r"(?<!\d)(?:\+?\d[\d\-\s]{7,}\d)(?!\d)")
UUID_PATTERN = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b", re.IGNORECASE)
NAME_LABEL_PATTERN = re.compile(
    r"(?P<label>(?:customer|user|contact|name|姓名|客户|用户|联系人))\s*[:：]\s*(?P<value>[A-Za-z\u4e00-\u9fff][A-Za-z\u4e00-\u9fff .'-]{1,40})",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class SanitizationResult:
    value: Any
    entity_type_breakdown: dict[str, int]
    new_placeholder_count: int


def sanitize_value(value: Any, store: SessionPrivacyMapStore, key_hint: str | None = None) -> SanitizationResult:
    if isinstance(value, str):
        return _sanitize_text(value, store, key_hint)
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        totals: dict[str, int] = {}
        created = 0
        for key, item in value.items():
            nested = sanitize_value(item, store, str(key))
            result[str(key)] = nested.value
            created += nested.new_placeholder_count
            for entity_type, count in nested.entity_type_breakdown.items():
                totals[entity_type] = totals.get(entity_type, 0) + count
        return SanitizationResult(result, totals, created)
    if isinstance(value, list):
        items: list[Any] = []
        totals: dict[str, int] = {}
        created = 0
        for item in value:
            nested = sanitize_value(item, store, key_hint)
            items.append(nested.value)
            created += nested.new_placeholder_count
            for entity_type, count in nested.entity_type_breakdown.items():
                totals[entity_type] = totals.get(entity_type, 0) + count
        return SanitizationResult(items, totals, created)
    return SanitizationResult(value, {}, 0)


def restore_value(value: Any, store: SessionPrivacyMapStore) -> Any:
    if isinstance(value, str):
        return _restore_text(value, store)
    if isinstance(value, dict):
        return {key: restore_value(item, store) for key, item in value.items()}
    if isinstance(value, list):
        return [restore_value(item, store) for item in value]
    return value


def _sanitize_text(text: str, store: SessionPrivacyMapStore, key_hint: str | None) -> SanitizationResult:
    if not text or PLACEHOLDER_PATTERN.search(text):
        return SanitizationResult(text, {}, 0)
    replacements: list[tuple[int, int, MappingEntry]] = []

    for pattern, entity_type in (
        (EMAIL_PATTERN, "ACCOUNT"),
        (PHONE_PATTERN, "PHONE"),
        (UUID_PATTERN, "ACCOUNT"),
    ):
        for match in pattern.finditer(text):
            replacements.append((match.start(), match.end(), store.ensure_mapping(entity_type, match.group(0))))

    for match in NAME_LABEL_PATTERN.finditer(text):
        raw_value = match.group("value").strip()
        replacements.append((match.start("value"), match.end("value"), store.ensure_mapping("PERSON", raw_value)))

    replacements = _dedupe_replacements(replacements)
    if not replacements:
        return SanitizationResult(text, {}, 0)

    entity_counts: dict[str, int] = {}
    new_placeholders = 0
    rendered = text
    for start, end, entry in sorted(replacements, key=lambda item: item[0], reverse=True):
        entity_counts[entry.entity_type] = entity_counts.get(entry.entity_type, 0) + 1
        if entry.created:
            new_placeholders += 1
        rendered = rendered[:start] + entry.placeholder_id + rendered[end:]
    return SanitizationResult(rendered, entity_counts, new_placeholders)


def _restore_text(text: str, store: SessionPrivacyMapStore) -> str:
    def replace(match: re.Match[str]) -> str:
        placeholder = match.group(0)
        raw_value = store.restore_placeholder(placeholder)
        if raw_value is None:
            raise ValueError(f"unknown placeholder: {placeholder}")
        return raw_value

    return PLACEHOLDER_PATTERN.sub(replace, text)


def _dedupe_replacements(items: list[tuple[int, int, MappingEntry]]) -> list[tuple[int, int, MappingEntry]]:
    deduped: list[tuple[int, int, MappingEntry]] = []
    occupied: list[tuple[int, int]] = []
    for start, end, entry in sorted(items, key=lambda item: (item[0], -(item[1] - item[0]))):
        if any(not (end <= existing_start or start >= existing_end) for existing_start, existing_end in occupied):
            continue
        deduped.append((start, end, entry))
        occupied.append((start, end))
    return deduped
