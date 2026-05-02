from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any

from starlette.concurrency import run_in_threadpool

from .decisioning import _resolve_provider_settings, execute_agent_turn
from .models import (
    AgentDecision,
    AgentTurnExecutionOutcome,
    AgentTurnRequest,
    AgentTurnResult,
    AgentTurnStreamFrame,
    SessionMessageInput,
)
from .openai_compatible import (
    LlmUsageTracker,
    OpenAiCompatibleStreamAccumulator,
    OpenAiCompatibleStreamError,
    OpenAiCompatibleStreamEvent,
    OpenAiCompatibleStreamIdleTimeoutError,
    OpenAiCompatibleStreamMalformedError,
    OpenAiCompatibleStreamMessage,
    apply_reasoning_settings,
    stream_chat_completion_events,
)
from .openai_adapter import render_openai_tool_definitions
from .privacy_pipeline import build_privacy_pipeline
from .prompting import build_streaming_prompt_bundle, render_openai_streaming_messages
from .tooling import semantic_tool_definitions
from .transcript_store import (
    TranscriptEntry,
    TranscriptStore,
    TurnExecutionContext,
    transcript_entries_from_provider_messages,
    transcript_entry_from_stream_message,
)


def _now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class _FrameWriter:
    def __init__(self, request: AgentTurnRequest) -> None:
        self._request = request
        self._stream_id = "stream-" + str(uuid.uuid4())
        self._seq = 0
        self._turn_id = request.turnId or request.trigger.eventId or "turn-" + str(uuid.uuid4())
        self._turn_execution_id = request.turnExecutionId or self._turn_id + ":exec-1"
        self._execution_attempt_id = "attempt-" + str(uuid.uuid4())

    @property
    def turn_id(self) -> str:
        return self._turn_id

    @property
    def turn_execution_id(self) -> str:
        return self._turn_execution_id

    @property
    def execution_attempt_id(self) -> str:
        return self._execution_attempt_id

    def frame(
        self,
        *,
        kind: str,
        visibility: str,
        payload: dict[str, Any],
    ) -> AgentTurnStreamFrame:
        self._seq += 1
        return AgentTurnStreamFrame(
            frameId=f"{self._turn_execution_id}:{self._seq}",
            streamId=self._stream_id,
            sessionId=self._request.sessionId,
            turnId=self._turn_id,
            turnExecutionId=self._turn_execution_id,
            ownerAgentId=self._request.currentOwner.agentId,
            ownershipEpoch=self._request.ownershipEpoch,
            seq=self._seq,
            kind=kind,  # type: ignore[arg-type]
            visibility=visibility,  # type: ignore[arg-type]
            occurredAt=_now_iso(),
            payload=payload,
        )


async def stream_agent_turn(
    request: AgentTurnRequest,
    transcript_store: TranscriptStore | None = None,
) -> AsyncIterator[str]:
    writer = _FrameWriter(request)
    turn_context = _turn_execution_context(request, writer)
    replay_messages: list[dict[str, Any]] = []
    if transcript_store is not None:
        cached_outcome = await run_in_threadpool(transcript_store.begin_execution, turn_context)
        if cached_outcome is not None:
            yield _serialize(writer.frame(
                kind="FINAL_OUTCOME",
                visibility="INTERNAL",
                payload={"outcome": cached_outcome.model_dump(mode="json")},
            ))
            return
        replay_messages = await run_in_threadpool(
            transcript_store.load_committed_provider_messages,
            turn_context,
        )

    yield _serialize(writer.frame(
        kind="TURN_STARTED",
        visibility="OPERATOR",
        payload={"triggerType": request.trigger.triggerType},
    ))
    yield _serialize(writer.frame(
        kind="USER_NOTICE",
        visibility="CUSTOMER",
        payload={"label": "PROCESSING", "text": "已收到，我正在处理。"},
    ))

    if _can_use_provider_stream(request):
        async for frame in _stream_via_openai_compatible(
            request,
            writer,
            replay_messages=replay_messages,
            transcript_store=transcript_store,
            turn_context=turn_context,
        ):
            yield _serialize(frame)
        return

    if transcript_store is not None:
        message = "execute-stream requires provider streaming because non-streaming fallback cannot replay owner transcript"
        outcome = AgentTurnExecutionOutcome(success=False, failureReason=message)
        await run_in_threadpool(transcript_store.mark_failed, turn_context, message)
        yield _serialize(writer.frame(
            kind="ERROR",
            visibility="OPERATOR",
            payload={
                "code": "TRANSCRIPT_REPLAY_UNSUPPORTED_ON_FALLBACK",
                "message": message,
                "stage": "TRANSCRIPT_REPLAY",
                "retryable": False,
                "details": {},
            },
        ))
        yield _serialize(writer.frame(
            kind="FINAL_OUTCOME",
            visibility="INTERNAL",
            payload={"outcome": outcome.model_dump(mode="json")},
        ))
        return

    try:
        outcome, _ = await run_in_threadpool(execute_agent_turn, request)
    except Exception as error:  # noqa: BLE001
        message = str(error) or "agent turn execution failed"
        yield _serialize(writer.frame(
            kind="ERROR",
            visibility="OPERATOR",
            payload={
                "code": "AGENT_TURN_EXECUTION_FAILED",
                "message": message,
                "stage": "FINAL_OUTCOME_BUILD",
                "retryable": True,
                "details": {},
            },
        ))
        outcome = AgentTurnExecutionOutcome(success=False, failureReason=message)

    if transcript_store is not None:
        if outcome.success:
            entries = _transcript_entries_from_successful_outcome(outcome, model_round_id=writer.turn_execution_id)
            await run_in_threadpool(transcript_store.commit_success, turn_context, outcome, entries)
        else:
            await run_in_threadpool(
                transcript_store.mark_failed,
                turn_context,
                outcome.failureReason or "agent turn execution failed",
            )

    yield _serialize(writer.frame(
        kind="FINAL_OUTCOME",
        visibility="INTERNAL",
        payload={"outcome": outcome.model_dump(mode="json")},
    ))


def _can_use_provider_stream(request: AgentTurnRequest) -> bool:
    if request.effectivePrivacyMappingEnabled:
        return False
    return _resolve_provider_settings(request) is not None


async def _stream_via_openai_compatible(
    request: AgentTurnRequest,
    writer: _FrameWriter,
    *,
    replay_messages: list[dict[str, Any]],
    transcript_store: TranscriptStore | None,
    turn_context: TurnExecutionContext,
) -> AsyncIterator[AgentTurnStreamFrame]:
    usage_tracker = LlmUsageTracker()
    accumulator = OpenAiCompatibleStreamAccumulator()
    text_guard = _CustomerTextStreamGuard()
    reply_block_started = False
    block_id = "reply-block-1"
    privacy_pipeline = build_privacy_pipeline(request, usage_tracker)
    try:
        settings = _resolve_provider_settings(request)
        if settings is None:
            raise RuntimeError("no supported model provider configured for current owner")
        prompt_bundle = build_streaming_prompt_bundle(request)
        sanitized_bundle = privacy_pipeline.sanitize_prompt_bundle(prompt_bundle)
        current_messages = render_openai_streaming_messages(sanitized_bundle)
        provider_messages = _merge_replay_messages(current_messages, replay_messages)
        current_entries = transcript_entries_from_provider_messages(
            current_messages,
            model_round_id=writer.turn_execution_id,
        )
        if transcript_store is not None:
            await run_in_threadpool(transcript_store.append_pending_entries, turn_context, current_entries)
        payload: dict[str, Any] = {
            "model": settings.model_id,
            "temperature": settings.temperature,
            "messages": provider_messages,
            "tools": render_openai_tool_definitions(semantic_tool_definitions(request)),
        }
        if settings.max_tokens > 0:
            payload["max_tokens"] = settings.max_tokens
        apply_reasoning_settings(payload, settings)
        yield writer.frame(
            kind="MODEL_STARTED",
            visibility="DEVELOPER",
            payload={"providerType": settings.provider_type, "modelId": settings.model_id},
        )
        events = stream_chat_completion_events(
            settings,
            payload,
            idle_timeout_seconds=20.0,
        )
        while True:
            event = await run_in_threadpool(_next_stream_event, events)
            if event is None:
                break
            accumulator.apply(event)
            if event.event_type == "content_delta" and event.delta:
                customer_delta = text_guard.accept(event.delta)
                if customer_delta and not reply_block_started:
                    reply_block_started = True
                    yield writer.frame(
                        kind="REPLY_BLOCK_STARTED",
                        visibility="CUSTOMER",
                        payload={"blockId": block_id, "blockType": "TEXT"},
                    )
                if customer_delta:
                    yield writer.frame(
                        kind="REPLY_BLOCK_DELTA",
                        visibility="CUSTOMER",
                        payload={"blockId": block_id, "blockType": "TEXT", "delta": customer_delta},
                    )
            elif event.event_type == "tool_call_delta":
                yield writer.frame(
                    kind="ACTION_TOOL_ARGUMENT_DELTA",
                    visibility="INTERNAL",
                    payload={
                        "index": event.tool_call_index,
                        "toolCallId": event.tool_call_id,
                        "toolName": event.tool_name,
                        "argumentsDelta": event.arguments_delta,
                    },
                )
        message = accumulator.build_message()
        if message.usage is not None:
            usage_tracker.record("SESSION_OWNER_MODEL", settings, message.usage)
        yield writer.frame(
            kind="MODEL_COMPLETED",
            visibility="DEVELOPER",
            payload={"finishReason": message.finish_reason, "usageAvailable": message.usage is not None},
        )
        outcome = _outcome_from_stream_message(request, message, usage_tracker, text_guard)
        if transcript_store is not None:
            if outcome.success:
                completed_entries = [
                    transcript_entry_from_stream_message(
                        message,
                        model_round_id=writer.turn_execution_id,
                        seq=len(current_entries) + 1,
                    ),
                ]
                await run_in_threadpool(transcript_store.commit_success, turn_context, outcome, completed_entries)
            else:
                await run_in_threadpool(
                    transcript_store.mark_failed,
                    turn_context,
                    outcome.failureReason or "openai-compatible provider stream failed",
                )
        if reply_block_started and outcome.success and outcome.result is not None:
            block = outcome.result.decision.replyMessage.blocks[0] if outcome.result.decision.replyMessage else None
            yield writer.frame(
                kind="REPLY_BLOCK_SNAPSHOT",
                visibility="CUSTOMER",
                payload={"blockId": block_id, "blockType": "TEXT", "text": message.content},
            )
            if block is not None:
                yield writer.frame(
                    kind="REPLY_BLOCK_COMPLETED",
                    visibility="CUSTOMER",
                    payload={"blockId": block_id, "block": block.model_dump(mode="json")},
                )
        yield writer.frame(
            kind="FINAL_OUTCOME",
            visibility="INTERNAL",
            payload={"outcome": outcome.model_dump(mode="json")},
        )
    except OpenAiCompatibleStreamError as error:
        outcome = AgentTurnExecutionOutcome(
            success=False,
            failureReason=str(error) or "openai-compatible provider stream failed",
            llmUsage=usage_tracker.entries(),
        )
        if transcript_store is not None:
            await run_in_threadpool(transcript_store.mark_failed, turn_context, outcome.failureReason)
        yield writer.frame(
            kind="ERROR",
            visibility="OPERATOR",
            payload={
                "code": _provider_stream_error_code(error),
                "message": outcome.failureReason,
                "stage": "PROVIDER_STREAM",
                "retryable": isinstance(error, OpenAiCompatibleStreamIdleTimeoutError),
                "details": {},
            },
        )
        yield writer.frame(
            kind="FINAL_OUTCOME",
            visibility="INTERNAL",
            payload={"outcome": outcome.model_dump(mode="json")},
        )
    except Exception as error:  # noqa: BLE001
        outcome = AgentTurnExecutionOutcome(
            success=False,
            failureReason=str(error) or "openai-compatible provider stream failed",
            llmUsage=usage_tracker.entries(),
        )
        if transcript_store is not None:
            await run_in_threadpool(transcript_store.mark_failed, turn_context, outcome.failureReason)
        yield writer.frame(
            kind="ERROR",
            visibility="OPERATOR",
            payload={
                "code": "PROVIDER_STREAM_FAILED",
                "message": outcome.failureReason,
                "stage": "PROVIDER_STREAM",
                "retryable": True,
                "details": {},
            },
        )
        yield writer.frame(
            kind="FINAL_OUTCOME",
            visibility="INTERNAL",
            payload={"outcome": outcome.model_dump(mode="json")},
        )
    finally:
        privacy_pipeline.close()


def _next_stream_event(events: Any) -> OpenAiCompatibleStreamEvent | None:
    try:
        return next(events)
    except StopIteration:
        return None


def _turn_execution_context(request: AgentTurnRequest, writer: _FrameWriter) -> TurnExecutionContext:
    return TurnExecutionContext(
        session_id=request.sessionId,
        owner_agent_id=request.currentOwner.agentId,
        ownership_epoch=request.ownershipEpoch,
        turn_id=writer.turn_id,
        turn_execution_id=writer.turn_execution_id,
        execution_attempt_id=writer.execution_attempt_id,
    )


def _merge_replay_messages(
    current_messages: list[dict[str, Any]],
    replay_messages: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not replay_messages or not current_messages:
        return [*current_messages]
    return [current_messages[0], *replay_messages, *current_messages[1:]]


def _transcript_entries_from_successful_outcome(
    outcome: AgentTurnExecutionOutcome,
    *,
    model_round_id: str,
) -> list[TranscriptEntry]:
    if not outcome.success or outcome.result is None or outcome.result.decision.replyMessage is None:
        return []
    text = "".join(
        block.text
        for block in outcome.result.decision.replyMessage.blocks
        if getattr(block, "type", None) == "TEXT" and getattr(block, "text", "")
    )
    if not text:
        return []
    message = OpenAiCompatibleStreamMessage(
        content=text,
        thinking="",
        tool_calls=[],
        finish_reason="stop",
        usage=None,
    )
    return [transcript_entry_from_stream_message(message, model_round_id=model_round_id, seq=1)]


def _outcome_from_stream_message(
    request: AgentTurnRequest,
    message: OpenAiCompatibleStreamMessage,
    usage_tracker: LlmUsageTracker,
    text_guard: "_CustomerTextStreamGuard",
) -> AgentTurnExecutionOutcome:
    if text_guard.json_like or _is_legacy_json_contract_text(message.content):
        return AgentTurnExecutionOutcome(
            success=False,
            failureReason="legacy JSON streaming unsupported: assistant text must not contain decision JSON",
            llmUsage=usage_tracker.entries(),
        )
    if message.tool_calls:
        return AgentTurnExecutionOutcome(
            success=False,
            failureReason="openai-compatible stream returned tool calls before streaming tool loop is available",
            llmUsage=usage_tracker.entries(),
        )
    text = message.content
    if text:
        reply = SessionMessageInput.model_validate({"blocks": [{"type": "TEXT", "text": text}], "metadata": {}})
        decision = AgentDecision(action="REPLY", replyMessage=reply)
    else:
        decision = AgentDecision(action="NO_OP")
    return AgentTurnExecutionOutcome(
        success=True,
        result=AgentTurnResult(
            decision=decision,
            sharedState=dict(request.sharedState),
        ),
        llmUsage=usage_tracker.entries(),
    )


class _CustomerTextStreamGuard:
    def __init__(self) -> None:
        self._pending_parts: list[str] = []
        self._json_like = False
        self._decided_text = False

    @property
    def json_like(self) -> bool:
        return self._json_like

    def accept(self, delta: str) -> str:
        if self._json_like:
            self._pending_parts.append(delta)
            return ""
        if self._decided_text:
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
        return pending


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
        return bool(legacy_keys.intersection(parsed))
    return True


def _provider_stream_error_code(error: OpenAiCompatibleStreamError) -> str:
    if isinstance(error, OpenAiCompatibleStreamMalformedError):
        return "PROVIDER_STREAM_MALFORMED"
    if isinstance(error, OpenAiCompatibleStreamIdleTimeoutError):
        return "PROVIDER_STREAM_IDLE_TIMEOUT"
    return "PROVIDER_STREAM_FAILED"


def _serialize(frame: AgentTurnStreamFrame) -> str:
    return frame.model_dump_json() + "\n"
