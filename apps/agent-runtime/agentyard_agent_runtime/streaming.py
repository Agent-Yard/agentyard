from __future__ import annotations

from .context_budget import bounded_provider_messages, bounded_tool_result, encoded
from .openai_compatible import parse_stream_tool_call

import asyncio
import contextlib
import json
import re
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any

import httpx
from starlette.concurrency import run_in_threadpool

from .data_security.detectors import PLACEHOLDER_PATTERN
from .data_security.validator import collect_placeholders
from .json_schema import validate_json_schema_value
from .models import ActivationOutcome, ActivationRuntime, ActivationStreamFrame, MessageContent, LlmUsageEntry
from .openai_compatible import (LlmUsageTracker, OpenAiCompatibleStreamAccumulator,
    OpenAiCompatibleStreamError, OpenAiCompatibleStreamEvent, OpenAiCompatibleStreamIdleTimeoutError,
    OpenAiCompatibleStreamMalformedError, OpenAiCompatibleStreamMessage, OpenAiCompatibleStreamToolCall,
    OpenAiCompatibleSettings, apply_reasoning_settings, stream_chat_completion_events)
from .openai_adapter import render_openai_tool_definitions
from .privacy_pipeline import PrivacyPipeline, build_privacy_pipeline
from .provider_settings import resolve_provider_settings
from .prompting import build_system_instruction, build_turn_input_messages, render_openai_runtime_messages
from .tooling import (APPEND_CARD_BLOCK_TOOL, APPEND_IMAGE_BLOCK_TOOL, APPEND_RICH_TEXT_BLOCK_TOOL,
    SECURITY_BLOCK_TOOL, FINISH_ACTIVATION_TOOL, OUTCOME_TOOL_KINDS, RuntimeToolKind, RuntimeToolSpec, execute_tool_call, runtime_tool_registry)
from .transcript_store import (TranscriptStore, TurnExecutionContext, transcript_entries_from_provider_messages,
    transcript_entry_from_provider_message)


def _now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class _ActivationYield(RuntimeError):
    """A compute slice ended; durable execution must continue without user input."""


def _retryable(error: Exception) -> bool:
    if isinstance(error, httpx.HTTPStatusError):
        return error.response.status_code == 429 or error.response.status_code >= 500
    return isinstance(error, (_ActivationYield, httpx.TransportError, OpenAiCompatibleStreamError,
                              ConnectionError, TimeoutError))


class _FrameWriter:
    def __init__(self, request: ActivationRuntime) -> None:
        self.request = request
        self.stream_id = "stream-" + str(uuid.uuid4())
        self.execution_attempt_id = "attempt-" + str(uuid.uuid4())
        self.seq = 0
        self.reply_message_id = request.activationId + ":reply"

    @property
    def turn_execution_id(self) -> str:
        return self.request.activationId

    def frame(self, *, kind: str, visibility: str, payload: dict[str, Any]) -> ActivationStreamFrame:
        self.seq += 1
        return ActivationStreamFrame(frameId=f"{self.stream_id}:{self.seq}", streamId=self.stream_id,
            sessionId=self.request.sessionId, activationId=self.request.activationId,
            participantId=self.request.participantId, seq=self.seq, kind=kind, visibility=visibility,
            occurredAt=_now_iso(), payload=payload)


def _turn_execution_context(request: ActivationRuntime, writer: _FrameWriter) -> TurnExecutionContext:
    return TurnExecutionContext(request.sessionId, request.participantId, request.trigger.id,
        request.activationId, writer.execution_attempt_id)


async def _heartbeat(store: TranscriptStore, context: TurnExecutionContext) -> None:
    while True:
        await asyncio.sleep(15)
        await run_in_threadpool(store.heartbeat, context)


async def stream_activation(request: ActivationRuntime, transcript_store: TranscriptStore) -> AsyncIterator[ActivationStreamFrame]:
    if transcript_store is None:
        raise RuntimeError("every activation requires a durable transcript store")
    writer = _FrameWriter(request)
    context = _turn_execution_context(request, writer)
    cached = await run_in_threadpool(transcript_store.begin_execution, context)
    if cached is not None:
        yield writer.frame(kind="FINAL_OUTCOME", visibility="INTERNAL",
            payload={"replyMessageId": writer.reply_message_id, "outcome": cached.model_dump(mode="json")})
        return
    heartbeat = asyncio.create_task(_heartbeat(transcript_store, context))
    try:
        yield writer.frame(kind="TURN_STARTED", visibility="OPERATOR", payload={
            "replyMessageId": writer.reply_message_id, "triggerType": request.trigger.kind, "inputMessageCount": 1})
        settings = resolve_provider_settings(request)
        if settings is None:
            raise ValueError("activation requires a configured streaming model provider")
        replay = await run_in_threadpool(transcript_store.load_committed_provider_messages, context, settings.provider_type, request.agent.memoryWindowSize)
        pending = await run_in_threadpool(transcript_store.load_execution_messages, context, settings.provider_type)
        async for frame in _stream_via_openai_compatible(request, writer, settings=settings, replay_messages=replay,
            pending_messages=pending, transcript_store=transcript_store, turn_context=context):
            yield frame
    except asyncio.CancelledError:
        with contextlib.suppress(RuntimeError):
            await run_in_threadpool(transcript_store.mark_failed, context, "activation request interrupted; checkpoints and tool receipts retained")
        raise
    except Exception as error:
        reason = str(error) or type(error).__name__
        with contextlib.suppress(RuntimeError):
            await run_in_threadpool(transcript_store.mark_failed, context, reason)
        yield writer.frame(kind="ERROR", visibility="OPERATOR", payload={"code": "ACTIVATION_FAILED",
            "replyMessageId": writer.reply_message_id, "message": reason, "stage": "PROVIDER_STREAM", "retryable": _retryable(error)})
        yield writer.frame(kind="FINAL_OUTCOME", visibility="INTERNAL", payload={
            "replyMessageId": writer.reply_message_id, "outcome": ActivationOutcome(success=False, failureReason=reason,
                retryable=_retryable(error), llmUsage=getattr(error, "activation_usage", [])).model_dump(mode="json")})
    finally:
        heartbeat.cancel()
        with contextlib.suppress(asyncio.CancelledError, RuntimeError):
            await heartbeat


async def _stream_via_openai_compatible(request: ActivationRuntime, writer: _FrameWriter, *,
        settings: OpenAiCompatibleSettings, replay_messages: list[dict[str, Any]], pending_messages: list[dict[str, Any]],
        transcript_store: TranscriptStore, turn_context: TurnExecutionContext) -> AsyncIterator[ActivationStreamFrame]:
    usage = LlmUsageTracker()
    usage.restore([LlmUsageEntry.model_validate(entry) for entry in
        await run_in_threadpool(transcript_store.execution_usage, turn_context)])
    accumulator = _StreamingOutcomeAccumulator(request)
    pipeline = build_privacy_pipeline(request, usage)
    registry = runtime_tool_registry(request)
    try:
        role_instruction = pipeline.sanitize_prompt_instruction(request.agent.systemPrompt.strip())
        system = build_system_instruction(request) + ("\n\nRole instructions:\n" + role_instruction if role_instruction else "")
        # The system envelope is rebuilt from the current authorized template.
        # Persistent records are evidence, never stale system instructions.
        provider_messages = [{"role": "system", "content": system}]
        provider_messages.extend(m for m in replay_messages if m.get("role") != "system")
        uncertain = await run_in_threadpool(transcript_store.uncertain_tool_calls, turn_context)
        if uncertain:
            provider_messages.append({"role": "user", "content": "Earlier tool calls have unresolved durable receipts. They may already have changed external state; inspect their history and reconcile before repeating an operation:\n" + json.dumps(uncertain, ensure_ascii=False)})
        if pending_messages:
            provider_messages.extend(m for m in pending_messages if m.get("role") != "system")
            _restore_accumulator(accumulator, pending_messages)
        else:
            current = render_openai_runtime_messages(pipeline.sanitize_semantic_messages(build_turn_input_messages(request)))
            await run_in_threadpool(transcript_store.append_pending_entries, turn_context,
                transcript_entries_from_provider_messages(current, provider_type=settings.provider_type,
                    model_round_id=f"{request.activationId}:input"))
            provider_messages.extend(current)
        # Recover the exact pending calls using their durable call IDs. A cached
        # receipt returns the original result; uncertain writes are never replayed.
        recovered = _pending_tool_calls(pending_messages)
        for index, call in enumerate(recovered, 1):
            spec = registry.get(call.tool_name)
            if call.tool_name == FINISH_ACTIVATION_TOOL and any(
                    len(m.get("tool_calls", [])) > 1 and any(c["id"] == call.call_id for c in m["tool_calls"])
                    for m in pending_messages if m.get("role") == "assistant"):
                result = {"success": False, "code": "FINISH_MUST_BE_ALONE",
                    "message": "Call activation_finish alone after inspecting all other tool results."}
            elif spec is None:
                result = {"success": False, "code": "CAPABILITY_UNAVAILABLE", "message": "This pending capability is no longer available."}
            else:
                result = await _execute_recorded_tool(request, call, spec, accumulator, transcript_store, turn_context, pipeline)
            provider_result = pipeline.sanitize_outbound("TOOL_RESULT", _llm_visible_tool_result(spec.kind if spec else RuntimeToolKind.CONTEXT_TOOL, result))
            message = _provider_tool_result_message(call, bounded_tool_result(provider_result,
                activation_id=request.activationId, call_id=call.call_id))
            await run_in_threadpool(transcript_store.append_pending_entries, turn_context,
                [transcript_entry_from_provider_message(message, provider_type=settings.provider_type,
                    model_round_id=f"{request.activationId}:recover:{call.call_id}", seq=1)])
            provider_messages.append(message)
        if accumulator.finished:
            outcome = _restore_successful_outcome(pipeline, accumulator.build_outcome(usage))
            await run_in_threadpool(transcript_store.commit_success, turn_context, outcome, [])
            yield writer.frame(kind="FINAL_OUTCOME", visibility="INTERNAL",
                payload={"replyMessageId": writer.reply_message_id, "outcome": outcome.model_dump(mode="json")})
            return
        payload: dict[str, Any] = {"model": settings.model_id, "temperature": settings.temperature,
            "messages": provider_messages, "tools": render_openai_tool_definitions([s.definition for s in registry.values()])}
        if settings.max_tokens > 0:
            payload["max_tokens"] = settings.max_tokens
        apply_reasoning_settings(payload, settings)
        first_round = sum(1 for m in pending_messages if m.get("role") == "assistant")
        missing_finish = 0
        for step in range(first_round, first_round + _max_streaming_tool_steps(request) + 1):
            round_id = f"{request.activationId}:round-{step}"
            usage.set_tool_loop_step(step)
            yield writer.frame(kind="MODEL_STARTED", visibility="DEVELOPER", payload={"modelRoundId": round_id})
            streamed = OpenAiCompatibleStreamAccumulator()
            payload["messages"] = bounded_provider_messages(provider_messages,
                tool_schema_chars=len(encoded(payload["tools"])))
            events = stream_chat_completion_events(settings, payload, idle_timeout_seconds=20.0)
            while True:
                event = await run_in_threadpool(_next_stream_event, events)
                if event is None:
                    break
                streamed.apply(event)
            message = streamed.build_message()
            usage.record("AGENT_MODEL", settings, message.usage, tool_loop_step=step)
            await run_in_threadpool(transcript_store.checkpoint_usage, turn_context, usage.entries())
            if _is_legacy_json_contract_text(message.content):
                raise ValueError("model returned a decision object instead of a reply or tool call")
            # All intermediate model text belongs to the private execution transcript.
            # Only the explicit final envelope is published to a conversation/channel.
            assistant_message = _provider_message_from_stream_message(message)
            await run_in_threadpool(transcript_store.append_pending_entries, turn_context,
                [transcript_entry_from_provider_message(assistant_message, provider_type=settings.provider_type, model_round_id=round_id, seq=1)])
            provider_messages.append(assistant_message)
            yield writer.frame(kind="MODEL_COMPLETED", visibility="DEVELOPER",
                payload={"modelRoundId": round_id, "finishReason": message.finish_reason})
            for index, call in enumerate(message.tool_calls, 2):
                spec = registry.get(call.tool_name)
                if call.tool_name == FINISH_ACTIVATION_TOOL and len(message.tool_calls) != 1:
                    result = {"success": False, "code": "FINISH_MUST_BE_ALONE",
                        "message": "Complete all other tools, inspect their results, then call activation_finish alone."}
                    kind = RuntimeToolKind.COMPLETION_TOOL
                elif spec is None:
                    result = {"success": False, "code": "UNKNOWN_TOOL", "message": "Use a declared tool name."}
                    kind = RuntimeToolKind.CONTEXT_TOOL
                else:
                    kind = spec.kind
                    yield writer.frame(kind="ACTION_TOOL_STARTED", visibility="OPERATOR",
                        payload={"modelRoundId": round_id, "toolCallId": call.call_id, "toolName": call.tool_name, "toolKind": kind.value})
                    result = await _execute_recorded_tool(request, call, spec, accumulator, transcript_store, turn_context, pipeline)
                yield writer.frame(kind="ACTION_TOOL_COMPLETED", visibility="OPERATOR",
                    payload={"toolCallId": call.call_id, "toolName": call.tool_name, "toolKind": kind.value,
                        "status": _tool_completion_status(result)})
                llm_result = pipeline.sanitize_outbound("TOOL_RESULT", _llm_visible_tool_result(kind, result))
                tool_message = _provider_tool_result_message(call, bounded_tool_result(llm_result,
                    activation_id=request.activationId, call_id=call.call_id))
                await run_in_threadpool(transcript_store.append_pending_entries, turn_context,
                    [transcript_entry_from_provider_message(tool_message, provider_type=settings.provider_type, model_round_id=round_id, seq=index)])
                provider_messages.append(tool_message)
            if accumulator.continuing:
                raise _ActivationYield("continuing unfinished work from durable checkpoints: " + accumulator.summary)
            if accumulator.finished:
                outcome = _restore_successful_outcome(pipeline, accumulator.build_outcome(usage))
                await run_in_threadpool(transcript_store.commit_success, turn_context, outcome, [])
                if outcome.replyMessage:
                    for index, block in enumerate(outcome.replyMessage.blocks):
                        yield writer.frame(kind="REPLY_BLOCK_COMPLETED", visibility="CUSTOMER", payload={
                            "replyMessageId": writer.reply_message_id, "blockId": f"final-{index}",
                            "block": block.model_dump(mode="json")})
                yield writer.frame(kind="FINAL_OUTCOME", visibility="INTERNAL",
                    payload={"replyMessageId": writer.reply_message_id, "outcome": outcome.model_dump(mode="json")})
                return
            if not message.tool_calls:
                missing_finish += 1
                if missing_finish >= 3:
                    raise ValueError("model stopped repeatedly without an explicit activation disposition; work is not marked complete")
                feedback = {"role": "user", "content": "Execution is not complete merely because you stopped speaking. Carry out the remaining authorized work now. When your contribution is actually done call activation_finish alone with COMPLETED and the final reply; for a real external dependency use WAITING with waitingFor, for a concrete obstacle use BLOCKED. If more compute is needed use CONTINUE. Do not repeat a result already sent with message_send; omit reply for a courtesy acknowledgement."}
                await run_in_threadpool(transcript_store.append_pending_entries, turn_context,
                    [transcript_entry_from_provider_message(feedback, provider_type=settings.provider_type, model_round_id=round_id, seq=2)])
                provider_messages.append(feedback)
            else:
                missing_finish = 0
        raise _ActivationYield("activation reached its tool step budget; continuing from durable context and receipts")
    except Exception as error:
        error.activation_usage = usage.entries()
        with contextlib.suppress(RuntimeError):
            await run_in_threadpool(transcript_store.checkpoint_usage, turn_context, usage.entries())
        raise
    finally:
        pipeline.close()


async def _execute_recorded_tool(request: ActivationRuntime, call: OpenAiCompatibleStreamToolCall, spec: RuntimeToolSpec,
        accumulator: "_StreamingOutcomeAccumulator", store: TranscriptStore, context: TurnExecutionContext,
        pipeline: PrivacyPipeline) -> dict[str, Any]:
    if call.argument_error:
        return {"success": False, "code": "INVALID_TOOL_ARGUMENTS", "message": call.argument_error}
    try:
        validate_json_schema_value(call.arguments, spec.definition.input_schema)
    except ValueError as error:
        return {"success": False, "code": "INVALID_ARGUMENTS", "message": str(error)}
    cached = await run_in_threadpool(store.begin_tool_call, context, call.call_id, call.tool_name, call.arguments,
        retry_safe=spec.retry_safe)
    if cached is not None:
        if spec.kind != RuntimeToolKind.CONTEXT_TOOL and cached.get("accepted") is True:
            accumulator.apply_tool_call(call, spec.kind)
        accumulator.observe_tool_result(call, cached)
        return cached
    try:
        if spec.kind == RuntimeToolKind.CONTEXT_TOOL:
            arguments = call.arguments if spec.resource_type == "SESSION_HISTORY" else _restore_privacy_payload(pipeline, "MODEL_TOOL_ARGUMENT", call.arguments)
            result = await run_in_threadpool(execute_tool_call, request, spec, arguments,
                call_id=call.call_id, transcript_store=store, context=context,
                result_sanitizer=lambda value: pipeline.sanitize_outbound("TOOL_RESULT", value))
        else:
            result = accumulator.apply_tool_call(call, spec.kind)
    except httpx.HTTPStatusError as error:
        if error.response.status_code >= 500:
            raise
        result = {"success": False, "code": "TOOL_REJECTED", "message": f"Tool execution was rejected (HTTP {error.response.status_code})."}
    except (ValueError, KeyError) as error:
        result = {"success": False, "code": "TOOL_REJECTED", "message": str(error)}
    await run_in_threadpool(store.complete_tool_call, context, call.call_id, result)
    accumulator.observe_tool_result(call, result)
    return result


def _pending_tool_calls(messages: list[dict[str, Any]]) -> list[OpenAiCompatibleStreamToolCall]:
    completed = {m.get("tool_call_id") for m in messages if m.get("role") == "tool"}
    return [parse_stream_tool_call(0, c["id"], c["function"]["name"], c["function"]["arguments"])
        for m in messages if m.get("role") == "assistant" for c in m.get("tool_calls", []) if c["id"] not in completed]


def _restore_accumulator(accumulator: "_StreamingOutcomeAccumulator", messages: list[dict[str, Any]]) -> None:
    completed = {m.get("tool_call_id") for m in messages if m.get("role") == "tool"
                 and json.loads(m.get("content") or "{}").get("accepted") is True}
    for m in messages:
        if m.get("role") != "assistant":
            continue
        for c in m.get("tool_calls", []):
            name = c["function"]["name"]
            if c["id"] in completed and name in OUTCOME_TOOL_KINDS:
                call = parse_stream_tool_call(0, c["id"], name, c["function"]["arguments"])
                if not call.argument_error:
                    if name != FINISH_ACTIVATION_TOOL or call.arguments.get("status") != "CONTINUE":
                        accumulator.apply_tool_call(call, OUTCOME_TOOL_KINDS[name])
    calls = {c["id"]: c for m in messages for c in m.get("tool_calls", [])}
    for m in messages:
        if m.get("role") == "tool" and m.get("tool_call_id") in calls:
            c = calls[m["tool_call_id"]]
            call = parse_stream_tool_call(0, c["id"], c["function"]["name"], c["function"]["arguments"])
            accumulator.observe_tool_result(call, json.loads(m.get("content") or "{}"))


def _next_stream_event(events: Any) -> OpenAiCompatibleStreamEvent | None:
    try:
        return next(events)
    except StopIteration:
        return None


class _StreamingOutcomeAccumulator:
    def __init__(self, request: ActivationRuntime) -> None:
        self._request = request
        self._reply_blocks: list[dict[str, Any]] = []
        self._security_assessment: dict[str, Any] | None = None
        self.completion: str | None = None
        self.summary = ""
        self.waiting_for: str | None = None
        self._reply_sent = request.context.get("replyAlreadyDelivered") is True

    def observe_tool_result(self, call: OpenAiCompatibleStreamToolCall, result: dict[str, Any]) -> None:
        if call.tool_name == "message_send" and result.get("success") is True:
            message = result.get("result") or {}
            if message.get("kind") in {"RESULT", "QUESTION"} and message.get("recipientId") == self._request.context.get("replyRecipientId"):
                self._reply_sent = True

    @property
    def finished(self) -> bool:
        return self.security_blocked or self.completion in {"COMPLETED", "WAITING", "BLOCKED"}

    @property
    def continuing(self) -> bool:
        return self.completion == "CONTINUE"

    @property
    def security_blocked(self) -> bool:
        return self._security_assessment is not None

    def handled_outcome_tool_kinds(self) -> dict[str, RuntimeToolKind]:
        return dict(OUTCOME_TOOL_KINDS)

    def apply_tool_call(self, call: OpenAiCompatibleStreamToolCall, kind: RuntimeToolKind) -> dict[str, Any]:
        if OUTCOME_TOOL_KINDS.get(call.tool_name) != kind:
            raise ValueError("outcome tool kind mismatch")
        if call.tool_name == FINISH_ACTIVATION_TOOL:
            status = call.arguments["status"]
            waiting_for = call.arguments.get("waitingFor")
            reply = str(call.arguments.get("reply") or "").strip()
            if status == "WAITING" and not waiting_for:
                raise ValueError("WAITING requires waitingFor identifying the actual external dependency")
            if status == "CONTINUE" and (reply or self._reply_blocks):
                raise ValueError("CONTINUE checkpoints unfinished work without publishing a final reply")
            if reply and self._reply_blocks:
                raise ValueError("A reply was already composed with message blocks; omit reply to avoid publishing two answers")
            if status == "BLOCKED" and not (reply or self._reply_blocks or self._reply_sent):
                raise ValueError("BLOCKED must communicate the actual obstacle through reply or an already delivered message")
            self.completion, self.summary, self.waiting_for = status, call.arguments["summary"], waiting_for
            if reply:
                self._reply_blocks.append({"type": "TEXT", "text": reply})
            return {"accepted": True, "status": status}
        if call.tool_name == SECURITY_BLOCK_TOOL:
            self._security_assessment = {"action": "BLOCK", "categories": _required_string_array(call.arguments.get("categories"), "categories required"),
                "reason": _required_trimmed_string(call.arguments.get("reason"), "reason required"),
                "confidence": float(_required_number(call.arguments.get("confidence"), "confidence required"))}
            self._reply_blocks.clear()
            self.completion = "BLOCKED"
            return {"accepted": True}
        block = _message_block_from_tool_call(call)
        reply = MessageContent.model_validate({"blocks": [block], "metadata": {}})
        self._reply_blocks.append(reply.blocks[0].model_dump(mode="json"))
        return {"accepted": True, "blockId": f"tool-block-{len(self._reply_blocks)}"}

    def build_outcome(self, usage: LlmUsageTracker) -> ActivationOutcome:
        if not self.finished:
            raise ValueError("activation has no explicit completion disposition")
        reply = MessageContent.model_validate({"blocks": self._reply_blocks, "metadata": {}}) if self._reply_blocks and not self._reply_sent else None
        return ActivationOutcome(success=True, replyMessage=reply, workMemory={},
            completion=self.completion, securityAssessment=self._security_assessment, llmUsage=usage.entries())


class _CustomerTextStreamGuard:
    def __init__(self) -> None:
        self._pending_parts: list[str] = []
        self._accepted_parts: list[str] = []
        self._json_like = False
        self._decided_text = False

    @property
    def json_like(self) -> bool:
        return self._json_like

    @property
    def accepted_text(self) -> str:
        if self._json_like:
            return ""
        return "".join(self._accepted_parts)

    def accept(self, delta: str) -> str:
        if self._json_like:
            self._pending_parts.append(delta)
            return ""
        if self._decided_text:
            self._accepted_parts.append(delta)
            return delta
        self._pending_parts.append(delta)
        pending = "".join(self._pending_parts)
        stripped = pending.lstrip()
        if not stripped:
            return ""
        if stripped[0] in {"{", "["}:
            self._json_like = True
            return ""
        self._decided_text = True
        self._pending_parts.clear()
        self._accepted_parts.append(pending)
        return pending


_PRIVACY_PLACEHOLDER_PREFIX_PATTERN = re.compile(r"^[A-Z][A-Z0-9]{0,31}(?:_(?:\d{0,6})?)?$")
_RESERVED_PRIVACY_MARKER_PATTERN = re.compile(r"<<[A-Z][A-Z0-9_]*")
_PRIVACY_PLACEHOLDER_MAX_LENGTH = len("<<") + 32 + len("_") + 6 + len(">>")


class _ChunkSafePrivacyRestorer:
    def __init__(self, privacy_pipeline: PrivacyPipeline) -> None:
        self._privacy_pipeline = privacy_pipeline
        self._enabled = bool(privacy_pipeline.enabled)
        self._buffer = ""

    def accept(self, delta: str) -> str:
        if not delta:
            return ""
        if not self._enabled:
            return delta
        self._buffer += delta
        release_end = _safe_privacy_release_index(self._buffer)
        if release_end <= 0:
            return ""
        segment = self._buffer[:release_end]
        self._buffer = self._buffer[release_end:]
        return _restore_privacy_payload(self._privacy_pipeline, "MODEL_FINAL_RESPONSE", segment)

    def flush(self) -> str:
        if not self._enabled:
            return ""
        if not self._buffer:
            return ""
        segment = self._buffer
        self._buffer = ""
        return _restore_privacy_payload(self._privacy_pipeline, "MODEL_FINAL_RESPONSE", segment)


def _safe_privacy_release_index(text: str) -> int:
    start = text.rfind("<<")
    if start < 0:
        return len(text)
    suffix = text[start:]
    if _is_incomplete_privacy_placeholder_prefix(suffix):
        return start
    return len(text)


def _is_incomplete_privacy_placeholder_prefix(text: str) -> bool:
    if not text.startswith("<<") or PLACEHOLDER_PATTERN.fullmatch(text):
        return False
    if len(text) > _PRIVACY_PLACEHOLDER_MAX_LENGTH:
        return False
    body = text[2:]
    if not body:
        return True
    if ">" in body[:-1]:
        return False
    if body.endswith(">"):
        body = body[:-1]
    if not body:
        return True
    return _PRIVACY_PLACEHOLDER_PREFIX_PATTERN.fullmatch(body) is not None


def _restore_successful_outcome(privacy_pipeline: PrivacyPipeline, outcome: ActivationOutcome) -> ActivationOutcome:
    if not privacy_pipeline.enabled:
        return outcome
    value = _restore_privacy_payload(privacy_pipeline, "MODEL_FINAL_RESPONSE", outcome.model_dump(mode="json"))
    result = ActivationOutcome.model_validate(value)
    result.mappingTelemetry = privacy_pipeline.telemetry()
    return result


def _restore_privacy_payload(privacy_pipeline: PrivacyPipeline, channel: str, payload: Any) -> Any:
    _assert_no_malformed_privacy_markers(payload)
    if not collect_placeholders(payload):
        return payload
    return privacy_pipeline.restore_inbound(channel, payload)


def _assert_no_malformed_privacy_markers(payload: Any) -> None:
    if isinstance(payload, str):
        sanitized = PLACEHOLDER_PATTERN.sub("", payload)
        if _RESERVED_PRIVACY_MARKER_PATTERN.search(sanitized):
            raise ValueError("unresolved privacy placeholder marker")
        return
    if isinstance(payload, dict):
        for item in payload.values():
            _assert_no_malformed_privacy_markers(item)
        return
    if isinstance(payload, list):
        for item in payload:
            _assert_no_malformed_privacy_markers(item)


def _provider_message_from_stream_message(message: OpenAiCompatibleStreamMessage) -> dict[str, Any]:
    rendered: dict[str, Any] = {"role": "assistant", "content": message.content}
    if message.thinking:
        rendered["reasoning_content"] = message.thinking
    if message.tool_calls:
        rendered["tool_calls"] = [
            {
                "id": tool_call.call_id,
                "type": "function",
                "function": {
                    "name": tool_call.tool_name,
                    "arguments": tool_call.raw_arguments if tool_call.raw_arguments is not None else json.dumps(tool_call.arguments, ensure_ascii=False),
                },
            }
            for tool_call in message.tool_calls
        ]
    return rendered


def _provider_tool_result_message(
    tool_call: OpenAiCompatibleStreamToolCall,
    result: dict[str, Any],
) -> dict[str, Any]:
    return {
        "role": "tool",
        "tool_call_id": tool_call.call_id,
        "content": json.dumps(result, ensure_ascii=False),
    }


def _message_block_from_tool_call(tool_call: OpenAiCompatibleStreamToolCall) -> dict[str, Any]:
    arguments = tool_call.arguments
    if tool_call.tool_name == APPEND_IMAGE_BLOCK_TOOL:
        url = str(arguments.get("url") or "").strip()
        if not url:
            raise ValueError("append_image_block.url is required")
        return {
            key: value
            for key, value in {
                "type": "IMAGE",
                "url": url,
                "mimeType": _optional_string(arguments.get("mimeType")),
                "width": _optional_int(arguments.get("width")),
                "height": _optional_int(arguments.get("height")),
                "alt": _optional_string(arguments.get("alt")),
            }.items()
            if value is not None
        }
    if tool_call.tool_name == APPEND_RICH_TEXT_BLOCK_TOOL:
        content = str(arguments.get("content") or "")
        if not content.strip():
            raise ValueError("append_rich_text_block.content is required")
        return {"type": "RICH_TEXT", "format": str(arguments.get("format") or "MARKDOWN"), "content": content}
    if tool_call.tool_name == APPEND_CARD_BLOCK_TOOL:
        card_type = str(arguments.get("cardType") or "").strip()
        version = str(arguments.get("version") or "").strip()
        if not card_type or not version:
            raise ValueError("append_card_block cardType and version are required")
        data = arguments.get("data")
        actions = arguments.get("actions")
        return {
            "type": "CARD",
            "cardType": card_type,
            "version": version,
            "data": data if isinstance(data, dict) else {},
            "actions": actions if isinstance(actions, list) else [],
        }
    raise ValueError(f"unsupported message block tool: {tool_call.tool_name}")


def _deep_merge_dicts(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge_dicts(merged[key], value)
        else:
            merged[key] = value
    return merged


def _tool_acceptance(error: str | None) -> dict[str, Any]:
    if error:
        return {"accepted": False, "error": error}
    return {"accepted": True}


def _first_error(*errors: str | None) -> str | None:
    return next((error for error in errors if error), None)


def _missing_value_error(value: str, error: str) -> str | None:
    return None if value else error


def _value_not_allowed_error(value: str, allowed_values: set[str], error: str) -> str | None:
    return None if value in allowed_values else error


def _required_string_array(value: Any, error: str) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
        raise ValueError(error)
    return [item.strip() for item in value]


def _required_trimmed_string(value: Any, error: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(error)
    return text


def _required_number(value: Any, error: str) -> int | float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(error)
    return value


def _tool_completion_status(result: dict[str, Any]) -> str:
    return "REJECTED" if result.get("accepted") is False or result.get("success") is False else "ACCEPTED"


def _llm_visible_tool_result(kind: RuntimeToolKind, result: dict[str, Any]) -> dict[str, Any]:
    if kind != RuntimeToolKind.MESSAGE_BLOCK_TOOL:
        return result
    visible_result = dict(result)
    visible_result.pop("blockId", None)
    return visible_result


def _has_message_content(message: MessageContent | None) -> bool:
    if message is None:
        return False
    return any(block.model_dump(mode="json") for block in message.blocks)


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _max_streaming_tool_steps(request: ActivationRuntime) -> int:
    return max(1, min(request.maxToolSteps, 100))


def _is_legacy_json_contract_text(text: str) -> bool:
    stripped = text.strip()
    if not stripped or stripped[0] not in {"{", "["}:
        return False
    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError:
        return stripped.startswith("{")
    if isinstance(parsed, dict):
        legacy_keys = {"decision", "sharedState", "securityAssessment", "skillReads"}
        return bool(legacy_keys.intersection(parsed)) or parsed.get("action") in {"REPLY", "NO_OP", "SWITCH_OWNER", "RUN_PLAYBOOK", "SESSION_HUMAN_HANDOFF", "SECURITY_BLOCK"}
    return False


def _provider_stream_error_code(error: OpenAiCompatibleStreamError) -> str:
    if isinstance(error, OpenAiCompatibleStreamMalformedError):
        return "PROVIDER_STREAM_MALFORMED"
    if isinstance(error, OpenAiCompatibleStreamIdleTimeoutError):
        return "PROVIDER_STREAM_IDLE_TIMEOUT"
    return "PROVIDER_STREAM_FAILED"
