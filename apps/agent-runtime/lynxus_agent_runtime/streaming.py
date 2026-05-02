from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
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
    OpenAiCompatibleStreamToolCall,
    apply_reasoning_settings,
    stream_chat_completion_events,
)
from .openai_adapter import render_openai_tool_definitions
from .privacy_pipeline import build_privacy_pipeline
from .prompting import build_streaming_prompt_bundle, render_openai_streaming_messages
from .tooling import execute_tool_call, streaming_semantic_tool_definitions, tool_kind
from .transcript_store import (
    TranscriptEntry,
    TranscriptStore,
    TurnExecutionContext,
    transcript_entry_from_stream_message,
    transcript_entry_from_tool_result_message,
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
    provider_settings = None if request.effectivePrivacyMappingEnabled else _resolve_provider_settings(request)
    provider_type = _request_provider_type(request, provider_settings.provider_type if provider_settings is not None else None)
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
            provider_type,
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
                "stage": "TRANSCRIPT_PERSISTENCE",
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


def _request_provider_type(request: AgentTurnRequest, resolved_provider_type: str | None) -> str:
    if resolved_provider_type:
        return resolved_provider_type
    if request.currentOwner.model is not None and request.currentOwner.model.providerType:
        return request.currentOwner.model.providerType
    return "OPENAI_COMPATIBLE"


async def _stream_via_openai_compatible(
    request: AgentTurnRequest,
    writer: _FrameWriter,
    *,
    replay_messages: list[dict[str, Any]],
    transcript_store: TranscriptStore | None,
    turn_context: TurnExecutionContext,
) -> AsyncIterator[AgentTurnStreamFrame]:
    usage_tracker = LlmUsageTracker()
    outcome_accumulator = _StreamingOutcomeAccumulator(request)
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
        current_messages = _collapse_current_system_messages(render_openai_streaming_messages(sanitized_bundle))
        provider_messages = _merge_replay_messages(current_messages, replay_messages)
        completed_transcript_entries: list[TranscriptEntry] = []
        payload: dict[str, Any] = {
            "model": settings.model_id,
            "temperature": settings.temperature,
            "messages": provider_messages,
            "tools": render_openai_tool_definitions(streaming_semantic_tool_definitions(request)),
        }
        if settings.max_tokens > 0:
            payload["max_tokens"] = settings.max_tokens
        apply_reasoning_settings(payload, settings)
        max_steps = _max_streaming_tool_steps()
        for step in range(max_steps + 1):
            model_round_id = f"{writer.turn_execution_id}:round-{step}"
            usage_tracker.set_tool_loop_step(step)
            payload["messages"] = provider_messages
            yield writer.frame(
                kind="MODEL_STARTED",
                visibility="DEVELOPER",
                payload={"modelRoundId": model_round_id},
            )
            round_accumulator = OpenAiCompatibleStreamAccumulator()
            events = stream_chat_completion_events(
                settings,
                payload,
                idle_timeout_seconds=20.0,
            )
            while True:
                event = await run_in_threadpool(_next_stream_event, events)
                if event is None:
                    break
                round_accumulator.apply(event)
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
                            "toolCallId": event.tool_call_id,
                            "delta": event.arguments_delta,
                        },
                    )
            message = round_accumulator.build_message()
            if message.usage is not None:
                usage_tracker.record("SESSION_OWNER_MODEL", settings, message.usage, tool_loop_step=step)
            yield writer.frame(
                kind="MODEL_COMPLETED",
                visibility="DEVELOPER",
                payload={
                    "modelRoundId": model_round_id,
                    "status": "SUCCEEDED",
                },
            )
            if text_guard.json_like or _is_legacy_json_contract_text(message.content):
                outcome = AgentTurnExecutionOutcome(
                    success=False,
                    failureReason="legacy JSON streaming unsupported: assistant text must not contain decision JSON",
                    llmUsage=usage_tracker.entries(),
                )
                break
            if message.content:
                outcome_accumulator.append_assistant_text(message.content)
            assistant_provider_message = _provider_message_from_stream_message(message)
            if message.tool_calls:
                provider_messages.append(assistant_provider_message)
                completed_transcript_entries.append(
                    transcript_entry_from_stream_message(
                        message,
                        model_round_id=model_round_id,
                        seq=1,
                    )
                )
                should_continue = False
                for index, tool_call in enumerate(message.tool_calls, start=1):
                    kind = tool_kind(tool_call.tool_name)
                    if kind is None:
                        raise ValueError(f"unsupported streaming tool call: {tool_call.tool_name}")
                    yield writer.frame(
                        kind="ACTION_TOOL_STARTED",
                        visibility="OPERATOR",
                        payload={
                            "modelRoundId": model_round_id,
                            "toolCallId": tool_call.call_id,
                            "toolName": tool_call.tool_name,
                            "toolKind": kind,
                        },
                    )
                    tool_result = outcome_accumulator.apply_tool_call(tool_call, kind)
                    if kind == "CONTEXT_TOOL":
                        tool_result = execute_tool_call(request, tool_call.tool_name, tool_call.arguments)
                        should_continue = True
                    elif kind in {"STATE_TOOL", "MESSAGE_BLOCK_TOOL"}:
                        should_continue = True
                    completed_payload = {
                        "toolCallId": tool_call.call_id,
                        "toolName": tool_call.tool_name,
                        "toolKind": kind,
                        "status": _tool_completion_status(tool_result),
                    }
                    produced = _tool_produced_payload(kind, tool_call, tool_result)
                    if produced is not None:
                        completed_payload["produced"] = produced
                    yield writer.frame(
                        kind="ACTION_TOOL_COMPLETED",
                        visibility="OPERATOR",
                        payload=completed_payload,
                    )
                    tool_provider_message = _provider_tool_result_message(tool_call, tool_result)
                    provider_messages.append(tool_provider_message)
                    completed_transcript_entries.append(
                        transcript_entry_from_tool_result_message(
                            tool_provider_message,
                            model_round_id=model_round_id,
                            seq=index + 1,
                        )
                    )
                if should_continue:
                    continue
            outcome = outcome_accumulator.build_outcome(usage_tracker)
            break
        else:
            outcome = AgentTurnExecutionOutcome(
                success=False,
                failureReason="model did not return a final streaming outcome within loop step budget",
                llmUsage=usage_tracker.entries(),
            )
        if transcript_store is not None:
            if outcome.success:
                if not message.tool_calls:
                    completed_transcript_entries.append(
                        transcript_entry_from_stream_message(
                            message,
                            model_round_id=f"{writer.turn_execution_id}:round-{step}",
                            seq=1,
                        )
                    )
                await run_in_threadpool(
                    transcript_store.commit_success,
                    turn_context,
                    outcome,
                    completed_transcript_entries,
                )
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
                payload={"blockId": block_id, "blockType": "TEXT", "text": text_guard.accepted_text},
            )
            if block is not None:
                yield writer.frame(
                    kind="REPLY_BLOCK_COMPLETED",
                    visibility="CUSTOMER",
                    payload={"blockId": block_id, "block": block.model_dump(mode="json")},
                )
        if not outcome.success:
            failure_reason = outcome.failureReason or "agent turn rejected"
            yield writer.frame(
                kind="ERROR",
                visibility="OPERATOR",
                payload={
                    "code": "FINAL_OUTCOME_REJECTED",
                    "message": failure_reason,
                    "stage": "FINAL_OUTCOME_BUILD",
                    "retryable": False,
                    "details": {},
                },
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


def _collapse_current_system_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not messages:
        return []
    system_contents: list[str] = []
    collapsed: list[dict[str, Any]] = []
    system_inserted = False
    for message in messages:
        if message.get("role") == "system":
            content = str(message.get("content") or "").strip()
            if content:
                system_contents.append(content)
            continue
        if not system_inserted:
            collapsed.append({"role": "system", "content": "\n\n".join(system_contents)})
            system_inserted = True
        collapsed.append(message)
    if not system_inserted:
        collapsed.insert(0, {"role": "system", "content": "\n\n".join(system_contents)})
    return collapsed


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


@dataclass(frozen=True)
class _LifecycleActionCandidate:
    action: str
    payload: dict[str, Any]
    error: str | None = None


class _StreamingOutcomeAccumulator:
    def __init__(self, request: AgentTurnRequest) -> None:
        self._request = request
        self._reply_blocks: list[dict[str, Any]] = []
        self._shared_state: dict[str, Any] = dict(request.sharedState)
        self._lifecycle_actions: list[_LifecycleActionCandidate] = []
        self._security_assessment: dict[str, Any] | None = None

    def append_assistant_text(self, text: str) -> None:
        if text.strip():
            self._reply_blocks.append({"type": "TEXT", "text": text})

    def apply_tool_call(
        self,
        tool_call: OpenAiCompatibleStreamToolCall,
        kind: str,
    ) -> dict[str, Any]:
        if kind == "STATE_TOOL":
            return self._apply_state_tool(tool_call)
        if kind == "MESSAGE_BLOCK_TOOL":
            return self._apply_message_block_tool(tool_call)
        if kind == "LIFECYCLE_ACTION_TOOL":
            return self._apply_lifecycle_action_tool(tool_call)
        return {"accepted": True}

    def build_outcome(self, usage_tracker: LlmUsageTracker) -> AgentTurnExecutionOutcome:
        try:
            decision = self._build_decision()
            self._validate_final_action(decision.action)
            return AgentTurnExecutionOutcome(
                success=True,
                result=AgentTurnResult(
                    decision=decision,
                    sharedState=dict(self._shared_state),
                    securityAssessment=self._security_assessment,
                ),
                llmUsage=usage_tracker.entries(),
            )
        except Exception as error:  # noqa: BLE001
            return AgentTurnExecutionOutcome(
                success=False,
                failureReason=str(error) or "streaming outcome validation failed",
                llmUsage=usage_tracker.entries(),
            )

    def _apply_state_tool(self, tool_call: OpenAiCompatibleStreamToolCall) -> dict[str, Any]:
        if tool_call.tool_name != "update_shared_state":
            raise ValueError(f"unsupported state tool: {tool_call.tool_name}")
        patch = tool_call.arguments.get("patch")
        if not isinstance(patch, dict):
            raise ValueError("update_shared_state.patch must be an object")
        self._shared_state = _deep_merge_dicts(self._shared_state, patch)
        return {"accepted": True}

    def _apply_message_block_tool(self, tool_call: OpenAiCompatibleStreamToolCall) -> dict[str, Any]:
        block = _message_block_from_tool_call(tool_call)
        reply = SessionMessageInput.model_validate({"blocks": [block], "metadata": {}})
        normalized = reply.blocks[0].model_dump(mode="json")
        self._reply_blocks.append(normalized)
        return {"accepted": True, "blockId": f"tool-block-{len(self._reply_blocks)}"}

    def _apply_lifecycle_action_tool(self, tool_call: OpenAiCompatibleStreamToolCall) -> dict[str, Any]:
        if tool_call.tool_name == "switch_owner":
            action = "SWITCH_OWNER"
            target_agent_id = str(tool_call.arguments.get("targetAgentId") or "").strip()
            error = None
            if action not in set(self._request.currentOwner.allowedActions):
                error = f"action {action} is not allowed"
            elif not target_agent_id:
                error = "switch_owner targetAgentId is required"
            elif target_agent_id not in set(self._request.currentOwner.switchableOwnerAgentIds):
                error = "switch_owner targetAgentId is not allowed"
            self._lifecycle_actions.append(
                _LifecycleActionCandidate(
                    action=action,
                    payload={"targetAgentId": target_agent_id},
                    error=error,
                )
            )
            return _tool_acceptance(error)
        if tool_call.tool_name == "run_playbook":
            action = "RUN_PLAYBOOK"
            playbook_id = str(tool_call.arguments.get("playbookId") or "").strip()
            playbook_input = tool_call.arguments.get("playbookInput")
            error = None
            if action not in set(self._request.currentOwner.allowedActions):
                error = f"action {action} is not allowed"
            elif not playbook_id:
                error = "run_playbook playbookId is required"
            elif playbook_id not in set(self._request.currentOwner.playbookIds):
                error = "run_playbook playbookId is not allowed"
            elif not isinstance(playbook_input, dict):
                error = "run_playbook playbookInput must be an object"
            self._lifecycle_actions.append(
                _LifecycleActionCandidate(
                    action=action,
                    payload={"playbookId": playbook_id, "playbookInput": playbook_input if isinstance(playbook_input, dict) else {}},
                    error=error,
                )
            )
            return _tool_acceptance(error)
        if tool_call.tool_name == "human_handoff":
            action = "SESSION_HUMAN_HANDOFF"
            error = None if action in set(self._request.currentOwner.allowedActions) else f"action {action} is not allowed"
            self._lifecycle_actions.append(_LifecycleActionCandidate(action=action, payload={}, error=error))
            return _tool_acceptance(error)
        if tool_call.tool_name == "security_block":
            categories = tool_call.arguments.get("categories")
            if not isinstance(categories, list) or not all(isinstance(item, str) and item.strip() for item in categories):
                raise ValueError("security_block categories must be a non-empty string array")
            reason = str(tool_call.arguments.get("reason") or "").strip()
            if not reason:
                raise ValueError("security_block reason is required")
            confidence = tool_call.arguments.get("confidence")
            if not isinstance(confidence, (int, float)) or isinstance(confidence, bool):
                raise ValueError("security_block confidence must be a number")
            self._security_assessment = {
                "action": "BLOCK",
                "categories": [item.strip() for item in categories],
                "reason": reason,
                "confidence": float(confidence),
            }
            self._lifecycle_actions.append(_LifecycleActionCandidate(action="SECURITY_BLOCK", payload={}))
            return {"accepted": True}
        raise ValueError(f"unsupported lifecycle action tool: {tool_call.tool_name}")

    def _build_decision(self) -> AgentDecision:
        reply = self._reply_message()
        lifecycle_action = self._resolve_lifecycle_action()
        if lifecycle_action is None:
            return AgentDecision(action="REPLY", replyMessage=reply) if reply is not None else AgentDecision(action="NO_OP")
        if lifecycle_action.action == "SECURITY_BLOCK":
            return AgentDecision(action="SECURITY_BLOCK", replyMessage=reply)
        if lifecycle_action.action == "SWITCH_OWNER":
            return AgentDecision(
                action="SWITCH_OWNER",
                replyMessage=reply,
                targetAgentId=str(lifecycle_action.payload.get("targetAgentId") or ""),
            )
        if lifecycle_action.action == "RUN_PLAYBOOK":
            return AgentDecision(
                action="RUN_PLAYBOOK",
                replyMessage=reply,
                playbookId=str(lifecycle_action.payload.get("playbookId") or ""),
                playbookInput=dict(lifecycle_action.payload.get("playbookInput") or {}),
            )
        if lifecycle_action.action == "SESSION_HUMAN_HANDOFF":
            return AgentDecision(action="SESSION_HUMAN_HANDOFF", replyMessage=reply)
        raise ValueError(f"unsupported lifecycle action: {lifecycle_action.action}")

    def _reply_message(self) -> SessionMessageInput | None:
        if not self._reply_blocks:
            return None
        reply = SessionMessageInput.model_validate({"blocks": self._reply_blocks, "metadata": {}})
        return reply if _has_message_content(reply) else None

    def _resolve_lifecycle_action(self) -> _LifecycleActionCandidate | None:
        security_actions = [candidate for candidate in self._lifecycle_actions if candidate.action == "SECURITY_BLOCK"]
        if security_actions:
            return security_actions[-1]
        lifecycle_actions = [candidate for candidate in self._lifecycle_actions if candidate.action != "SECURITY_BLOCK"]
        if len(lifecycle_actions) > 1:
            raise ValueError("multiple lifecycle actions are not allowed in one streaming outcome")
        for candidate in lifecycle_actions:
            if candidate.error:
                raise ValueError(candidate.error)
        return lifecycle_actions[-1] if lifecycle_actions else None

    def _validate_allowed_action(self, action: str) -> None:
        if action != "SECURITY_BLOCK" and action not in set(self._request.currentOwner.allowedActions):
            raise ValueError(f"action {action} is not allowed")

    def _validate_final_action(self, action: str) -> None:
        self._validate_allowed_action(action)


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
                    "arguments": json.dumps(tool_call.arguments, ensure_ascii=False),
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
    if tool_call.tool_name == "append_text_block":
        text = str(arguments.get("text") or "")
        if not text.strip():
            raise ValueError("append_text_block.text is required")
        return {"type": "TEXT", "text": text}
    if tool_call.tool_name == "append_image_block":
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
    if tool_call.tool_name == "append_rich_text_block":
        content = str(arguments.get("content") or "")
        if not content.strip():
            raise ValueError("append_rich_text_block.content is required")
        return {"type": "RICH_TEXT", "format": str(arguments.get("format") or "MARKDOWN"), "content": content}
    if tool_call.tool_name == "append_card_block":
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


def _tool_completion_status(result: dict[str, Any]) -> str:
    return "ACCEPTED" if bool(result.get("accepted", True)) else "REJECTED"


def _tool_produced_payload(
    kind: str,
    tool_call: OpenAiCompatibleStreamToolCall,
    result: dict[str, Any],
) -> dict[str, Any] | None:
    if kind == "STATE_TOOL":
        return {"sharedStateUpdated": bool(result.get("accepted", True))}
    if kind == "MESSAGE_BLOCK_TOOL":
        block_id = result.get("blockId")
        return {"messageBlockId": str(block_id)} if block_id else None
    if kind == "LIFECYCLE_ACTION_TOOL":
        return {"action": _lifecycle_action_name(tool_call.tool_name)}
    return None


def _lifecycle_action_name(tool_name: str) -> str:
    return {
        "switch_owner": "SWITCH_OWNER",
        "run_playbook": "RUN_PLAYBOOK",
        "human_handoff": "SESSION_HUMAN_HANDOFF",
        "security_block": "SECURITY_BLOCK",
    }.get(tool_name, tool_name)


def _has_message_content(message: SessionMessageInput | None) -> bool:
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


def _max_streaming_tool_steps() -> int:
    raw_value = ""
    try:
        import os

        raw_value = (os.getenv("LYNXUS_AGENT_RUNTIME_MAX_TOOL_STEPS") or "").strip()
    except Exception:  # pragma: no cover
        raw_value = ""
    if not raw_value:
        return 4
    try:
        return max(1, min(int(raw_value), 8))
    except ValueError:
        return 4


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
