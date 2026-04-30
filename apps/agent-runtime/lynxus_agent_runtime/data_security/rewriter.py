from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

from ..openai_compatible import LlmUsageTracker, OpenAiCompatibleSettings, chat_completion
from .store import SessionPrivacyMapStore
from .validator import PrivacyMappingBlockedError


@dataclass(frozen=True)
class RewriteResult:
    value: str
    entity_type_breakdown: dict[str, int]
    new_placeholder_count: int
    total_replacement_count: int = 0


class PrivateLlmRewriter:
    def __init__(self, model_binding: Any | None, usage_tracker: LlmUsageTracker | None = None) -> None:
        self._binding = model_binding
        self._usage_tracker = usage_tracker

    @property
    def enabled(self) -> bool:
        return self._binding is not None and bool(getattr(self._binding, "privateDeployment", False))

    @property
    def configured(self) -> bool:
        return self.enabled and bool((os.getenv(self._binding.apiKeyEnvVar) or "").strip())

    def rewrite(self, text: str, store: SessionPrivacyMapStore) -> RewriteResult:
        if not self.enabled or not text.strip():
            return RewriteResult(text, {}, 0, 0)
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
        replacements: list[tuple[int, int, str, str]] = []
        mapping_cache: dict[tuple[str, str], tuple[str, str]] = {}
        for entity in entities:
            raw_value = str(entity.get("rawValue") or "").strip()
            entity_type = str(entity.get("entityType") or "").strip().upper()
            if not raw_value or not entity_type:
                continue
            ranges: list[tuple[int, int]] = []
            search_from = 0
            while True:
                start = rendered.find(raw_value, search_from)
                if start < 0:
                    break
                end = start + len(raw_value)
                search_from = end
                if any(not (end <= left or start >= right) for left, right in seen_ranges):
                    continue
                ranges.append((start, end))
            if not ranges:
                continue
            cache_key = (entity_type, raw_value)
            cached = mapping_cache.get(cache_key)
            if cached is None:
                entry = store.ensure_mapping(entity_type, raw_value)
                cached = (entry.placeholder_id, entry.entity_type)
                mapping_cache[cache_key] = cached
                if entry.created:
                    new_placeholder_count += 1
            placeholder, mapped_entity_type = cached
            for start, end in ranges:
                replacements.append((start, end, placeholder, mapped_entity_type))
                seen_ranges.append((start, end))
        for start, end, placeholder, entity_type in sorted(replacements, key=lambda item: item[0], reverse=True):
            rendered = rendered[:start] + placeholder + rendered[end:]
            entity_counts[entity_type] = entity_counts.get(entity_type, 0) + 1
        return RewriteResult(rendered, entity_counts, new_placeholder_count, len(replacements))

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
                        "Return one JSON object only, exactly matching this shape: "
                        '{"entities":[{"rawValue":"...","entityType":"PERSON"}]}. '
                        'If no sensitive entities are present, return {"entities":[]}. '
                        "Never return a bare array or an empty object. "
                        "Allowed entityType values: PERSON, ACCOUNT, ORDER, PHONE."
                    ),
                },
                {
                    "role": "user",
                    "content": text,
                },
            ],
            "response_format": response_format,
        }
        response_json = chat_completion(
            OpenAiCompatibleSettings(
                base_url=self._binding.baseUrl.rstrip("/"),
                model_id=self._binding.modelId,
                api_key=api_key,
                provider_type=str(getattr(self._binding, "providerType", "OPENAI_COMPATIBLE")).upper(),
                model_resource_id=getattr(self._binding, "resourceId", None),
                model_resource_version_id=getattr(self._binding, "resourceVersionId", None),
            ),
            payload,
            timeout_seconds=10.0,
            usage_tracker=self._usage_tracker,
            source_type="SESSION_PRIVACY_MODEL",
            tool_loop_step=None if self._usage_tracker is None else self._usage_tracker.current_tool_loop_step(),
        )
        content = response_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if isinstance(parsed, list):
            return [item for item in parsed if isinstance(item, dict)]
        if not isinstance(parsed, dict):
            return []
        entities = parsed.get("entities", [])
        return [item for item in entities if isinstance(item, dict)] if isinstance(entities, list) else []
