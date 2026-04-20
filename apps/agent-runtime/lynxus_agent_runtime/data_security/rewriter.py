from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

import httpx

from .store import SessionPrivacyMapStore
from .validator import PrivacyMappingBlockedError


@dataclass(frozen=True)
class RewriteResult:
    value: str
    entity_type_breakdown: dict[str, int]
    new_placeholder_count: int


class PrivateLlmRewriter:
    def __init__(self, model_binding: Any | None) -> None:
        self._binding = model_binding

    @property
    def enabled(self) -> bool:
        return self._binding is not None and bool(getattr(self._binding, "privateDeployment", False))

    def rewrite(self, text: str, store: SessionPrivacyMapStore) -> RewriteResult:
        if not self.enabled or not text.strip():
            return RewriteResult(text, {}, 0)
        api_key = (os.getenv(self._binding.apiKeyEnvVar) or "").strip()
        if not api_key:
            raise PrivacyMappingBlockedError("private privacy model api key is not configured")
        try:
            entities = self._extract_entities(text, api_key)
        except Exception as error:  # noqa: BLE001
            raise PrivacyMappingBlockedError("private privacy model rewrite failed") from error
        rendered = text
        entity_counts: dict[str, int] = {}
        new_placeholder_count = 0
        seen_ranges: list[tuple[int, int]] = []
        replacements: list[tuple[int, int, str, str, bool]] = []
        for entity in entities:
            raw_value = str(entity.get("rawValue") or "").strip()
            entity_type = str(entity.get("entityType") or "").strip().upper()
            if not raw_value or not entity_type:
                continue
            start = rendered.find(raw_value)
            if start < 0:
                continue
            end = start + len(raw_value)
            if any(not (end <= left or start >= right) for left, right in seen_ranges):
                continue
            entry = store.ensure_mapping(entity_type, raw_value)
            replacements.append((start, end, entry.placeholder_id, entry.entity_type, entry.created))
            seen_ranges.append((start, end))
        for start, end, placeholder, entity_type, created in sorted(replacements, key=lambda item: item[0], reverse=True):
            rendered = rendered[:start] + placeholder + rendered[end:]
            entity_counts[entity_type] = entity_counts.get(entity_type, 0) + 1
            if created:
                new_placeholder_count += 1
        return RewriteResult(rendered, entity_counts, new_placeholder_count)

    def _extract_entities(self, text: str, api_key: str) -> list[dict[str, Any]]:
        response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": "privacy_mapping_entities",
                "schema": {
                    "type": "object",
                    "properties": {
                        "entities": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "rawValue": {"type": "string"},
                                    "entityType": {"type": "string"},
                                },
                                "required": ["rawValue", "entityType"],
                                "additionalProperties": False,
                            },
                        }
                    },
                    "required": ["entities"],
                    "additionalProperties": False,
                },
            },
        }
        payload = {
            "model": self._binding.modelId,
            "temperature": 0,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Extract sensitive entities from the provided text. "
                        "Return JSON only. Allowed entityType values: PERSON, ACCOUNT, ORDER, PHONE."
                    ),
                },
                {
                    "role": "user",
                    "content": text,
                },
            ],
            "response_format": response_format,
        }
        with httpx.Client(timeout=10.0) as client:
            response = client.post(
                self._binding.baseUrl.rstrip("/") + "/chat/completions",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        entities = parsed.get("entities", [])
        return entities if isinstance(entities, list) else []
