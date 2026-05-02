from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from time import monotonic
from typing import Any, Callable, Iterable, Iterator

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


class OpenAiCompatibleStreamError(RuntimeError):
    pass


class OpenAiCompatibleStreamMalformedError(OpenAiCompatibleStreamError):
    pass


class OpenAiCompatibleStreamIdleTimeoutError(OpenAiCompatibleStreamError):
    pass


@dataclass(frozen=True)
class OpenAiCompatibleStreamEvent:
    event_type: str
    delta: str = ""
    tool_call_index: int | None = None
    tool_call_id: str | None = None
    tool_name: str | None = None
    arguments_delta: str = ""
    finish_reason: str | None = None
    usage: dict[str, Any] | None = None


@dataclass(frozen=True)
class OpenAiCompatibleStreamToolCall:
    index: int
    call_id: str
    tool_name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class OpenAiCompatibleStreamMessage:
    content: str
    thinking: str
    tool_calls: list[OpenAiCompatibleStreamToolCall]
    finish_reason: str | None
    usage: dict[str, Any] | None


@dataclass
class _ToolCallParts:
    call_id: str = ""
    tool_name: str = ""
    argument_parts: list[str] = field(default_factory=list)


class OpenAiCompatibleStreamAccumulator:
    def __init__(self) -> None:
        self._content_parts: list[str] = []
        self._thinking_parts: list[str] = []
        self._tool_calls: dict[int, _ToolCallParts] = {}
        self._finish_reason: str | None = None
        self._usage: dict[str, Any] | None = None

    def apply(self, event: OpenAiCompatibleStreamEvent) -> None:
        if event.event_type == "content_delta":
            self._content_parts.append(event.delta)
            return
        if event.event_type == "thinking_delta":
            self._thinking_parts.append(event.delta)
            return
        if event.event_type == "tool_call_delta":
            self._apply_tool_call_delta(event)
            return
        if event.event_type == "finish_reason":
            self._finish_reason = event.finish_reason
            return
        if event.event_type == "usage":
            self._usage = dict(event.usage or {})

    def build_message(self) -> OpenAiCompatibleStreamMessage:
        return OpenAiCompatibleStreamMessage(
            content="".join(self._content_parts),
            thinking="".join(self._thinking_parts),
            tool_calls=self._build_tool_calls(),
            finish_reason=self._finish_reason,
            usage=None if self._usage is None else dict(self._usage),
        )

    def _apply_tool_call_delta(self, event: OpenAiCompatibleStreamEvent) -> None:
        if event.tool_call_index is None:
            raise OpenAiCompatibleStreamMalformedError("tool call delta missing index")
        parts = self._tool_calls.setdefault(event.tool_call_index, _ToolCallParts())
        if event.tool_call_id:
            parts.call_id = event.tool_call_id
        if event.tool_name:
            parts.tool_name = event.tool_name
        if event.arguments_delta:
            parts.argument_parts.append(event.arguments_delta)

    def _build_tool_calls(self) -> list[OpenAiCompatibleStreamToolCall]:
        tool_calls: list[OpenAiCompatibleStreamToolCall] = []
        for index in sorted(self._tool_calls):
            parts = self._tool_calls[index]
            raw_arguments = "".join(parts.argument_parts).strip() or "{}"
            try:
                parsed_arguments = json.loads(raw_arguments)
            except json.JSONDecodeError as error:
                raise OpenAiCompatibleStreamMalformedError(
                    f"tool call arguments for index {index} are not valid JSON"
                ) from error
            if not isinstance(parsed_arguments, dict):
                raise OpenAiCompatibleStreamMalformedError(
                    f"tool call arguments for index {index} must decode to a JSON object"
                )
            if not parts.call_id.strip():
                raise OpenAiCompatibleStreamMalformedError(f"tool call id is required for index {index}")
            if not parts.tool_name.strip():
                raise OpenAiCompatibleStreamMalformedError(f"tool call {index} missing function name")
            tool_calls.append(
                OpenAiCompatibleStreamToolCall(
                    index=index,
                    call_id=parts.call_id,
                    tool_name=parts.tool_name,
                    arguments=parsed_arguments,
                )
            )
        return tool_calls


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


def stream_chat_completion_events(
    settings: OpenAiCompatibleSettings,
    payload: dict[str, Any],
    *,
    idle_timeout_seconds: float,
) -> Iterator[OpenAiCompatibleStreamEvent]:
    headers = {
        "Authorization": f"Bearer {settings.api_key}",
        "Content-Type": "application/json",
    }
    if settings.organization:
        headers["OpenAI-Organization"] = settings.organization
    if settings.project:
        headers["OpenAI-Project"] = settings.project
    request_payload = dict(payload)
    request_payload["stream"] = True
    request_payload["stream_options"] = {"include_usage": True}
    client = shared_http_client_for_url(settings.base_url)
    url = settings.base_url.rstrip("/") + "/chat/completions"
    LOGGER.debug(
        "openai-compatible streaming llm request",
        extra={
            "providerType": settings.provider_type,
            "modelResourceId": settings.model_resource_id,
            "modelResourceVersionId": settings.model_resource_version_id,
            "modelId": settings.model_id,
            "llmRequest": {
                "url": url,
                "headers": _redact_sensitive_headers(headers),
                "payload": request_payload,
                "idleTimeoutSeconds": idle_timeout_seconds,
            },
        },
    )
    with client.stream(
        "POST",
        url,
        headers=headers,
        json=request_payload,
        timeout=idle_timeout_seconds,
    ) as response:
        LOGGER.debug(
            "openai-compatible streaming llm response",
            extra={
                "providerType": settings.provider_type,
                "modelResourceId": settings.model_resource_id,
                "modelResourceVersionId": settings.model_resource_version_id,
                "modelId": settings.model_id,
                "llmResponse": {
                    "url": url,
                    "statusCode": response.status_code,
                },
            },
        )
        response.raise_for_status()
        yield from iter_openai_compatible_stream_events(
            response.iter_lines(),
            idle_timeout_seconds=idle_timeout_seconds,
        )


def iter_openai_compatible_stream_events(
    lines: Iterable[str | bytes],
    *,
    idle_timeout_seconds: float = 30.0,
    clock: Callable[[], float] = monotonic,
) -> Iterator[OpenAiCompatibleStreamEvent]:
    last_event_at = clock()
    saw_done = False
    for raw_line in lines:
        now = clock()
        if now - last_event_at > idle_timeout_seconds:
            raise OpenAiCompatibleStreamIdleTimeoutError(
                f"openai-compatible stream idle timeout after {idle_timeout_seconds:.1f}s"
            )
        line = _decode_stream_line(raw_line).strip()
        if not line or line.startswith(":"):
            continue
        if not line.startswith("data:"):
            raise OpenAiCompatibleStreamMalformedError("malformed openai-compatible stream line")
        data = line.removeprefix("data:").strip()
        last_event_at = now
        if data == "[DONE]":
            saw_done = True
            yield OpenAiCompatibleStreamEvent(event_type="done")
            break
        payload = _parse_stream_json(data)
        yield from _events_from_stream_payload(payload)
    if not saw_done:
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream ended before [DONE]")


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


def _decode_stream_line(raw_line: str | bytes) -> str:
    if isinstance(raw_line, bytes):
        return raw_line.decode("utf-8")
    return raw_line


def _parse_stream_json(data: str) -> dict[str, Any]:
    try:
        payload = json.loads(data)
    except json.JSONDecodeError as error:
        raise OpenAiCompatibleStreamMalformedError("malformed openai-compatible stream JSON") from error
    if not isinstance(payload, dict):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream event must be a JSON object")
    return payload


def _events_from_stream_payload(payload: dict[str, Any]) -> Iterator[OpenAiCompatibleStreamEvent]:
    usage = payload.get("usage")
    if usage is not None:
        if not isinstance(usage, dict):
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream usage must be a JSON object")
        yield OpenAiCompatibleStreamEvent(event_type="usage", usage=dict(usage))
    choices = payload.get("choices")
    if choices is None:
        if usage is None:
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream event missing choices")
        return
    if not isinstance(choices, list):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream choices must be an array")
    for choice in choices:
        if not isinstance(choice, dict):
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream choice must be an object")
        delta = choice.get("delta") or {}
        if not isinstance(delta, dict):
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream choice delta must be an object")
        yield from _events_from_choice_delta(delta)
        finish_reason = choice.get("finish_reason")
        if finish_reason is not None:
            yield OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason=str(finish_reason))


def _events_from_choice_delta(delta: dict[str, Any]) -> Iterator[OpenAiCompatibleStreamEvent]:
    content = delta.get("content")
    if content is not None:
        if not isinstance(content, str):
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream content delta must be a string")
        if content:
            yield OpenAiCompatibleStreamEvent(event_type="content_delta", delta=content)
    for field_name in ("reasoning_content", "thinking", "reasoning"):
        thinking = delta.get(field_name)
        if thinking is not None:
            if not isinstance(thinking, str):
                raise OpenAiCompatibleStreamMalformedError("openai-compatible stream thinking delta must be a string")
            if thinking:
                yield OpenAiCompatibleStreamEvent(event_type="thinking_delta", delta=thinking)
    tool_calls = delta.get("tool_calls")
    if tool_calls is None:
        return
    if not isinstance(tool_calls, list):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool_calls delta must be an array")
    for position, tool_call in enumerate(tool_calls):
        if not isinstance(tool_call, dict):
            raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool call delta must be an object")
        yield _tool_call_event_from_delta(position, tool_call)


def _tool_call_event_from_delta(position: int, tool_call: dict[str, Any]) -> OpenAiCompatibleStreamEvent:
    raw_index = tool_call.get("index", position)
    try:
        index = int(raw_index)
    except (TypeError, ValueError) as error:
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool call index must be an integer") from error
    function_payload = tool_call.get("function") or {}
    if not isinstance(function_payload, dict):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool call function must be an object")
    arguments_delta = function_payload.get("arguments") or ""
    if not isinstance(arguments_delta, str):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool arguments delta must be a string")
    tool_name = function_payload.get("name")
    if tool_name is not None and not isinstance(tool_name, str):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool name must be a string")
    tool_call_id = tool_call.get("id")
    if tool_call_id is not None and not isinstance(tool_call_id, str):
        raise OpenAiCompatibleStreamMalformedError("openai-compatible stream tool call id must be a string")
    return OpenAiCompatibleStreamEvent(
        event_type="tool_call_delta",
        tool_call_index=index,
        tool_call_id=tool_call_id,
        tool_name=tool_name,
        arguments_delta=arguments_delta,
    )
