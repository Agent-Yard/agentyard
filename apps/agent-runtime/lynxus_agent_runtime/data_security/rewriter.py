from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

from ..openai_compatible import OpenAiCompatibleSettings, apply_reasoning_settings, chat_completion
from ..privacy_contracts import PrivacyModelBinding
from .store import SessionPrivacyMapStore
from .validator import PrivacyMappingBlockedError


@dataclass(frozen=True)
class RewriteResult:
    value: str
    entity_type_breakdown: dict[str, int]
    new_placeholder_count: int
    total_replacement_count: int = 0


class PrivateLlmRewriter:
    def __init__(self, model_binding: PrivacyModelBinding | None, usage_tracker: object | None = None) -> None:
        self._binding = model_binding
        self._usage_tracker = usage_tracker

    @property
    def enabled(self) -> bool:
        return self._binding is not None and self._binding.private_deployment

    @property
    def configured(self) -> bool:
        return self.enabled and self._binding is not None and bool((os.getenv(self._binding.api_key_env_var) or "").strip())

    def rewrite(self, text: str, store: SessionPrivacyMapStore) -> RewriteResult:
        if not self.enabled or not text.strip():
            return RewriteResult(text, {}, 0, 0)
        if self._binding is None:
            return RewriteResult(text, {}, 0, 0)
        api_key = (os.getenv(self._binding.api_key_env_var) or "").strip()
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
        if self._binding is None:
            return []
        response_format = {
            "type": "json_object"
        }
        payload = {
            "model": self._binding.model_id,
            "temperature": 0,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "请从所给的文本中提取可能涉及隐私信息的实体。"
                        "只返回一个如下所示的JSON对象: \n"
                        '{"entities":[{"rawValue":"...","entityType":"PERSON"}]}\n\n'
                        '如果没有隐私信息实体则返回 {"entities":[]}\n'
                        "可能的隐私实体类型: PERSON, ACCOUNT, ORDER, PHONE"
                    ),
                },
                {
                    "role": "user",
                    "content": text,
                },
            ],
            "response_format": response_format,
        }
        settings = OpenAiCompatibleSettings(
            base_url=self._binding.base_url.rstrip("/"),
            model_id=self._binding.model_id,
            api_key=api_key,
            provider_type=self._binding.provider_type.upper(),
            model_resource_id=self._binding.resource_id,
            model_resource_version_id=self._binding.resource_version_id,
            enable_thinking=self._binding.enable_thinking,
            reasoning_effort=(self._binding.reasoning_effort or "").strip(),
        )
        apply_reasoning_settings(payload, settings)
        response_json = chat_completion(
            settings,
            payload,
            timeout_seconds=10.0,
            usage_tracker=self._usage_tracker,
            source_type="SESSION_PRIVACY_MODEL",
            tool_loop_step=_current_tool_loop_step(self._usage_tracker),
        )
        content = response_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if isinstance(parsed, list):
            return [item for item in parsed if isinstance(item, dict)]
        if not isinstance(parsed, dict):
            return []
        entities = parsed.get("entities", [])
        return [item for item in entities if isinstance(item, dict)] if isinstance(entities, list) else []


def _current_tool_loop_step(usage_tracker: object | None) -> int | None:
    if usage_tracker is None:
        return None
    current_step = getattr(usage_tracker, "current_tool_loop_step", None)
    if current_step is None:
        return None
    return current_step()
