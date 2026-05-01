from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from .http_clients import shared_http_client_for_url
from .models import LlmUsageEntry

LOGGER = logging.getLogger("lynxus-agent-runtime")


@dataclass(frozen=True)
class OpenAiCompatibleSettings:
    base_url: str
    model_id: str
    api_key: str
    provider_type: str
    model_resource_id: str | None = None
    model_resource_version_id: str | None = None
    temperature: float = 0
    max_tokens: int = 0
    enable_thinking: bool | None = None
    reasoning_effort: str | None = None
    organization: str = ""
    project: str = ""


@dataclass
class LlmUsageTracker:
    _entries: list[LlmUsageEntry] = field(default_factory=list)
    _next_sequence: int = 1
    _current_tool_loop_step: int = 0

    def set_tool_loop_step(self, step: int) -> None:
        self._current_tool_loop_step = max(0, step)

    def current_tool_loop_step(self) -> int:
        return self._current_tool_loop_step

    def entries(self) -> list[LlmUsageEntry]:
        return list(self._entries)

    def record(
        self,
        source_type: str,
        settings: OpenAiCompatibleSettings,
        usage_payload: Any,
        *,
        tool_loop_step: int | None = None,
    ) -> None:
        raw_usage = usage_payload if isinstance(usage_payload, dict) else {}
        usage_available = bool(raw_usage)
        effective_step = self._current_tool_loop_step if tool_loop_step is None else max(0, tool_loop_step)
        self._entries.append(
            LlmUsageEntry(
                sourceType=source_type,
                callSequence=self._next_sequence,
                toolLoopStep=effective_step,
                providerType=settings.provider_type,
                modelResourceId=settings.model_resource_id,
                modelResourceVersionId=settings.model_resource_version_id,
                modelId=settings.model_id,
                usageAvailable=usage_available,
                promptTokens=_optional_int(raw_usage.get("prompt_tokens")),
                completionTokens=_optional_int(raw_usage.get("completion_tokens")),
                totalTokens=_optional_int(raw_usage.get("total_tokens")),
                rawUsage=dict(raw_usage),
                occurredAt=datetime.now(UTC).isoformat(),
            )
        )
        self._next_sequence += 1


def chat_completion(
    settings: OpenAiCompatibleSettings,
    payload: dict[str, Any],
    *,
    timeout_seconds: float,
    usage_tracker: LlmUsageTracker | None = None,
    source_type: str | None = None,
    tool_loop_step: int | None = None,
) -> dict[str, Any]:
    headers = {
        "Authorization": f"Bearer {settings.api_key}",
        "Content-Type": "application/json",
    }
    if settings.organization:
        headers["OpenAI-Organization"] = settings.organization
    if settings.project:
        headers["OpenAI-Project"] = settings.project
    client = shared_http_client_for_url(settings.base_url)
    url = settings.base_url.rstrip("/") + "/chat/completions"
    LOGGER.debug(
        "openai-compatible llm request",
        extra={
            "providerType": settings.provider_type,
            "modelResourceId": settings.model_resource_id,
            "modelResourceVersionId": settings.model_resource_version_id,
            "modelId": settings.model_id,
            "sourceType": source_type,
            "toolLoopStep": tool_loop_step,
            "llmRequest": {
                "url": url,
                "headers": _redact_sensitive_headers(headers),
                "payload": payload,
                "timeoutSeconds": timeout_seconds,
            },
        },
    )
    response = client.post(
        url,
        headers=headers,
        json=payload,
        timeout=timeout_seconds,
    )
    LOGGER.debug(
        "openai-compatible llm response",
        extra={
            "providerType": settings.provider_type,
            "modelResourceId": settings.model_resource_id,
            "modelResourceVersionId": settings.model_resource_version_id,
            "modelId": settings.model_id,
            "sourceType": source_type,
            "toolLoopStep": tool_loop_step,
            "llmResponse": {
                "url": url,
                "statusCode": response.status_code,
                "body": getattr(response, "text", ""),
            },
        },
    )
    response.raise_for_status()
    parsed = response.json()
    if usage_tracker is not None and source_type is not None:
        usage_tracker.record(
            source_type,
            settings,
            parsed.get("usage"),
            tool_loop_step=tool_loop_step,
        )
    return parsed


def apply_reasoning_settings(payload: dict[str, Any], settings: OpenAiCompatibleSettings) -> None:
    model = payload.get("model", "") or ""
    if model.startswith("glm") or model.startswith("kimi") or model.startswith("qwen"):
        if settings.enable_thinking is not None:
            payload["enable_thinking"] = settings.enable_thinking
    elif model.startswith("deepseek"):
        if settings.enable_thinking is not None:
            payload["thinking"] = {"type": "enabled" if settings.enable_thinking else "disabled"}
        if settings.reasoning_effort:
            payload["reasoning_effort"] = settings.reasoning_effort
    elif model.startswith("gpt"):
        if settings.reasoning_effort:
            payload["reasoning_effort"] = settings.reasoning_effort


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _redact_sensitive_headers(headers: dict[str, str]) -> dict[str, str]:
    redacted = dict(headers)
    if "Authorization" in redacted:
        redacted["Authorization"] = "Bearer [REDACTED]"
    return redacted
