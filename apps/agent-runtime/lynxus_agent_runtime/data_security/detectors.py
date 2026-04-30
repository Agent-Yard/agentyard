from __future__ import annotations

import re
from dataclasses import dataclass

PLACEHOLDER_PATTERN = re.compile(r"\[([A-Z]+)_(\d{3})\]")
EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
PHONE_PATTERN = re.compile(r"(?<!\d)(?:\+?\d[\d\-\s]{7,}\d)(?!\d)")
UUID_PATTERN = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
NAME_LABEL_PATTERN = re.compile(
    (
        r"(?P<label>(?:customer|user|contact|name|姓名|客户|用户|联系人))\s*[:：]\s*"
        r"(?P<value>[A-Za-z\u4e00-\u9fff][A-Za-z\u4e00-\u9fff .'-]{1,40})"
    ),
    re.IGNORECASE,
)
NAME_CONTEXT_PATTERN = re.compile(
    (
        r"(?P<label>(?:customer|user|contact|name|姓名|客户|用户|联系人))[ \t]+"
        r"(?P<value>(?:[A-Z][a-z]+(?: [A-Z][a-z]+){1,2}|[\u4e00-\u9fff]{2,4}))"
    )
)


@dataclass(frozen=True)
class DetectedSensitiveEntity:
    entity_type: str
    raw_value: str
    start: int
    end: int


def detect_sensitive_text(text: str) -> list[DetectedSensitiveEntity]:
    if not text:
        return []

    entities: list[DetectedSensitiveEntity] = []
    for pattern, entity_type in (
        (EMAIL_PATTERN, "ACCOUNT"),
        (PHONE_PATTERN, "PHONE"),
        (UUID_PATTERN, "ACCOUNT"),
    ):
        for match in pattern.finditer(text):
            entities.append(DetectedSensitiveEntity(entity_type, match.group(0).strip(), match.start(), match.end()))

    for pattern in (NAME_LABEL_PATTERN, NAME_CONTEXT_PATTERN):
        for match in pattern.finditer(text):
            start, end, raw_value = _stripped_group_span(match, "value")
            if raw_value:
                entities.append(DetectedSensitiveEntity("PERSON", raw_value, start, end))

    return _dedupe_detected_entities(entities)


def _stripped_group_span(match: re.Match[str], group_name: str) -> tuple[int, int, str]:
    raw_value = match.group(group_name)
    leading_trimmed = len(raw_value) - len(raw_value.lstrip())
    trailing_trimmed = len(raw_value.rstrip())
    start = match.start(group_name) + leading_trimmed
    end = match.start(group_name) + trailing_trimmed
    return start, end, raw_value.strip()


def _dedupe_detected_entities(items: list[DetectedSensitiveEntity]) -> list[DetectedSensitiveEntity]:
    deduped: list[DetectedSensitiveEntity] = []
    occupied: list[tuple[int, int]] = []
    for entity in sorted(items, key=lambda item: (item.start, -(item.end - item.start))):
        if any(
            not (entity.end <= existing_start or entity.start >= existing_end)
            for existing_start, existing_end in occupied
        ):
            continue
        deduped.append(entity)
        occupied.append((entity.start, entity.end))
    return deduped
