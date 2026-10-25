"""Durable context for a Session, independent of process, activation and role template.

PostgreSQL is authoritative. Redis only caches a bounded provider window; its TTL
never deletes messages, decisions, working memory, or side-effect receipts.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import uuid
from copy import deepcopy
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from redis.exceptions import RedisError
from sqlalchemy import DateTime, Integer, JSON, String, Text, UniqueConstraint, create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

from .models import ActivationOutcome
from .redis_support import RedisSettings, create_sync_redis_client

DEFAULT_DATABASE_URL = "postgresql+psycopg://agentyard:agentyard@127.0.0.1:5432/agentyard_agent_runtime"
DEFAULT_SCHEMA_NAME = "public"
LOGGER = logging.getLogger(__name__)


def now_utc() -> datetime:
    return datetime.now(UTC)


@dataclass(frozen=True)
class TranscriptStoreSettings:
    database_url: str = DEFAULT_DATABASE_URL
    activation_lease_seconds: int = 120
    context_window_messages: int = 160
    transcript_cache_ttl_seconds: int = 3600
    retention_sweep_limit: int = 500

    @classmethod
    def from_env(cls) -> "TranscriptStoreSettings":
        return cls(
            database_url=(os.getenv("AGENTYARD_AGENT_RUNTIME_DATABASE_URL") or DEFAULT_DATABASE_URL).strip(),
            activation_lease_seconds=_positive_int_env("AGENTYARD_AGENT_RUNTIME_ACTIVATION_LEASE_SECONDS", 120),
            context_window_messages=_positive_int_env("AGENTYARD_AGENT_RUNTIME_CONTEXT_WINDOW_MESSAGES", 160),
            transcript_cache_ttl_seconds=_positive_int_env("AGENTYARD_AGENT_RUNTIME_TRANSCRIPT_CACHE_TTL_SECONDS", 3600),
            retention_sweep_limit=_positive_int_env("AGENTYARD_AGENT_RUNTIME_RETENTION_SWEEP_LIMIT", 500),
        )


@dataclass(frozen=True)
class TurnExecutionContext:
    session_id: str
    participant_id: str
    turn_id: str
    turn_execution_id: str
    execution_attempt_id: str


@dataclass(frozen=True)
class TranscriptEntry:
    role: str
    provider_type: str
    content_json: dict[str, Any]
    model_round_id: str
    seq: int


@dataclass(frozen=True)
class CommittedTranscriptEntry:
    transcript_seq: int
    role: str
    provider_type: str
    content_json: dict[str, Any]


@dataclass(frozen=True)
class TranscriptCachePayload:
    session_id: str
    participant_id: str
    provider_type: str
    last_committed_seq: int
    messages: list[dict[str, Any]]
    memory_version: int = 0
    window_size: int = 0


class TranscriptCache(Protocol):
    def get(self, context: TurnExecutionContext, provider_type: str) -> TranscriptCachePayload | None: ...
    def put(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None: ...
    def delete(self, context: TurnExecutionContext) -> None: ...
    def close(self) -> None: ...


class RedisTranscriptCache:
    def __init__(self, redis_client: Any, ttl_seconds: int) -> None:
        self.redis_client = redis_client
        self.ttl_seconds = max(1, ttl_seconds)

    def get(self, context: TurnExecutionContext, provider_type: str) -> TranscriptCachePayload | None:
        try:
            raw = self.redis_client.get(transcript_cache_key(context))
            value = json.loads(raw) if raw else None
            if not isinstance(value, dict):
                return None
            if value.get("sessionId") != context.session_id or value.get("participantId") != context.participant_id:
                return None
            if value.get("providerType") != normalize_provider_type(provider_type):
                return None
            messages = value.get("messages")
            if not isinstance(messages, list) or not all(isinstance(m, dict) for m in messages):
                return None
            return TranscriptCachePayload(context.session_id, context.participant_id,
                normalize_provider_type(provider_type), int(value["lastCommittedSeq"]), deepcopy(messages), int(value.get("memoryVersion", 0)), int(value.get("windowSize", 0)))
        except (RedisError, ValueError, KeyError, TypeError) as error:
            LOGGER.warning("transcript cache unavailable: %s", type(error).__name__)
            return None

    def put(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None:
        try:
            self.redis_client.set(transcript_cache_key(context), json.dumps({
                "sessionId": payload.session_id, "participantId": payload.participant_id,
                "providerType": payload.provider_type, "lastCommittedSeq": payload.last_committed_seq,
                "messages": payload.messages, "memoryVersion": payload.memory_version, "windowSize": payload.window_size,
            }, ensure_ascii=False), ex=self.ttl_seconds)
        except RedisError:
            LOGGER.warning("transcript cache write failed")

    def delete(self, context: TurnExecutionContext) -> None:
        try:
            self.redis_client.delete(transcript_cache_key(context))
        except RedisError:
            LOGGER.warning("transcript cache invalidation failed")

    def close(self) -> None:
        close = getattr(self.redis_client, "close", None)
        if callable(close):
            close()


def create_transcript_cache(redis_settings: RedisSettings, store_settings: TranscriptStoreSettings) -> RedisTranscriptCache:
    return RedisTranscriptCache(create_sync_redis_client(redis_settings), store_settings.transcript_cache_ttl_seconds)


class Base(DeclarativeBase):
    pass


class SessionContextRecord(Base):
    __tablename__ = "runtime_session_context"
    session_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    participant_id: Mapped[str] = mapped_column(String(128), unique=True)
    last_transcript_seq: Mapped[int] = mapped_column(Integer, default=0)
    active_execution_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    active_attempt_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    lease_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class TurnExecutionRecord(Base):
    __tablename__ = "runtime_activation_execution"
    turn_execution_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    participant_id: Mapped[str] = mapped_column(String(128), index=True)
    turn_id: Mapped[str] = mapped_column(String(128))
    current_execution_attempt_id: Mapped[str] = mapped_column(String(128))
    status: Mapped[str] = mapped_column(String(32), index=True)
    final_outcome_json: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class TranscriptEntryRecord(Base):
    __tablename__ = "runtime_transcript_entry"
    __table_args__ = (
        UniqueConstraint("session_id", "transcript_seq", name="uq_runtime_session_transcript_seq"),
        UniqueConstraint("turn_execution_id", "model_round_id", "seq", name="uq_runtime_activation_entry"),
    )
    entry_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    participant_id: Mapped[str] = mapped_column(String(128), index=True)
    turn_execution_id: Mapped[str] = mapped_column(String(128), index=True)
    transcript_seq: Mapped[int] = mapped_column(Integer, index=True)
    model_round_id: Mapped[str] = mapped_column(String(128))
    seq: Mapped[int] = mapped_column(Integer)
    role: Mapped[str] = mapped_column(String(32))
    provider_type: Mapped[str] = mapped_column(String(64), index=True)
    content_json: Mapped[dict[str, Any]] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(32), default="RECORDED", index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class WorkingMemoryRecord(Base):
    __tablename__ = "runtime_working_memory"
    session_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    version: Mapped[int] = mapped_column(Integer)
    source_through_seq: Mapped[int] = mapped_column(Integer)
    content_json: Mapped[dict[str, Any]] = mapped_column(JSON)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class ToolReceiptRecord(Base):
    __tablename__ = "runtime_tool_receipt"
    receipt_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    activation_id: Mapped[str] = mapped_column(String(128), index=True)
    call_id: Mapped[str] = mapped_column(String(256))
    tool_name: Mapped[str] = mapped_column(String(256))
    arguments_json: Mapped[dict[str, Any]] = mapped_column(JSON)
    state: Mapped[str] = mapped_column(String(32))
    result_json: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class PostgresTranscriptStore:
    def __init__(self, settings: TranscriptStoreSettings | None = None, *, transcript_cache: TranscriptCache | None = None) -> None:
        self.settings = settings or TranscriptStoreSettings.from_env()
        self.transcript_cache = transcript_cache
        self.engine = create_engine(self.settings.database_url, future=True)
        self.session_factory = sessionmaker(bind=self.engine, autoflush=False, expire_on_commit=False)

    def initialize(self) -> None:
        ensure_postgres_configuration(self.settings.database_url)
        Base.metadata.create_all(self.engine)

    def _session_context(self, session: Session, context: TurnExecutionContext) -> SessionContextRecord:
        # INSERT ... ON CONFLICT followed by a row lock also serializes the first
        # activation, when two runtimes have never seen this Session before.
        dialect = self.engine.dialect.name
        if dialect == "postgresql":
            from sqlalchemy.dialects.postgresql import insert
        else:
            from sqlalchemy.dialects.sqlite import insert
        session.execute(insert(SessionContextRecord).values(session_id=context.session_id,
            participant_id=context.participant_id, last_transcript_seq=0).on_conflict_do_nothing())
        record = session.get(SessionContextRecord, context.session_id, with_for_update=True)
        if record is None or record.participant_id != context.participant_id:
            raise ValueError("Session participant identity mismatch")
        return record

    def begin_execution(self, context: TurnExecutionContext) -> ActivationOutcome | None:
        with self.session_factory.begin() as session:
            state = self._session_context(session, context)
            record = session.get(TurnExecutionRecord, context.turn_execution_id, with_for_update=True)
            if record is not None:
                _assert_same_context(record, context)
                if record.status == "SUCCEEDED":
                    return ActivationOutcome.model_validate(record.final_outcome_json)
            if state.active_attempt_id and state.lease_until and _utc(state.lease_until) > now_utc():
                return ActivationOutcome(success=False, retryable=True, failureReason="SESSION_BUSY: another activation attempt is running")
            if state.active_execution_id and state.active_execution_id != context.turn_execution_id:
                old = session.get(TurnExecutionRecord, state.active_execution_id)
                if old is not None and old.status == "RUNNING":
                    old.status = "INTERRUPTED"
                    old.failure_reason = "runtime lease expired; durable checkpoints and receipts retained"
            if record is None:
                record = TurnExecutionRecord(turn_execution_id=context.turn_execution_id, session_id=context.session_id,
                    participant_id=context.participant_id, turn_id=context.turn_id,
                    current_execution_attempt_id=context.execution_attempt_id, status="RUNNING")
                session.add(record)
            else:
                record.status = "RUNNING"
                record.current_execution_attempt_id = context.execution_attempt_id
                record.failure_reason = None
                record.completed_at = None
            self._renew(state, context)
        return None

    def _renew(self, state: SessionContextRecord, context: TurnExecutionContext) -> None:
        state.active_execution_id = context.turn_execution_id
        state.active_attempt_id = context.execution_attempt_id
        state.lease_until = now_utc() + timedelta(seconds=self.settings.activation_lease_seconds)

    def _running(self, session: Session, context: TurnExecutionContext) -> tuple[SessionContextRecord, TurnExecutionRecord]:
        state = self._session_context(session, context)
        record = session.get(TurnExecutionRecord, context.turn_execution_id, with_for_update=True)
        if record is None:
            raise RuntimeError("activation has not started")
        _assert_same_context(record, context)
        if record.status != "RUNNING" or state.active_attempt_id != context.execution_attempt_id:
            raise RuntimeError("activation attempt is no longer current")
        self._renew(state, context)
        return state, record

    def heartbeat(self, context: TurnExecutionContext) -> None:
        with self.session_factory.begin() as session:
            self._running(session, context)

    def execution_usage(self, context: TurnExecutionContext) -> list[dict[str, Any]]:
        with self.session_factory() as session:
            self._assert_identity(session, context)
            record = session.get(TurnExecutionRecord, context.turn_execution_id)
            return deepcopy((record.final_outcome_json or {}).get("llmUsage", [])) if record else []

    def checkpoint_usage(self, context: TurnExecutionContext, entries: list[Any]) -> None:
        with self.session_factory.begin() as session:
            _, record = self._running(session, context)
            record.final_outcome_json = {"llmUsage": [entry.model_dump(mode="json") for entry in entries]}

    def append_pending_entries(self, context: TurnExecutionContext, entries: list[TranscriptEntry]) -> None:
        # "Pending" is durable and intentionally survives failed executions.
        with self.session_factory.begin() as session:
            state, _ = self._running(session, context)
            self._append(session, state, context, entries)

    def _append(self, session: Session, state: SessionContextRecord, context: TurnExecutionContext, entries: list[TranscriptEntry]) -> None:
        for entry in entries:
            previous = session.scalar(select(TranscriptEntryRecord).where(
                TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
                TranscriptEntryRecord.model_round_id == entry.model_round_id,
                TranscriptEntryRecord.seq == entry.seq))
            if previous is not None:
                if previous.content_json != entry.content_json:
                    raise ValueError("checkpoint replay differs from recorded provider message")
                continue
            state.last_transcript_seq += 1
            session.add(TranscriptEntryRecord(entry_id=str(uuid.uuid4()), session_id=context.session_id,
                participant_id=context.participant_id, turn_execution_id=context.turn_execution_id,
                transcript_seq=state.last_transcript_seq, model_round_id=entry.model_round_id, seq=entry.seq,
                role=entry.role, provider_type=normalize_provider_type(entry.provider_type),
                content_json=deepcopy(entry.content_json), status="RECORDED"))
            session.flush()

    def load_execution_messages(self, context: TurnExecutionContext, provider_type: str = "OPENAI_COMPATIBLE") -> list[dict[str, Any]]:
        with self.session_factory() as session:
            self._assert_identity(session, context)
            records = session.scalars(select(TranscriptEntryRecord).where(
                TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
                TranscriptEntryRecord.session_id == context.session_id,
                TranscriptEntryRecord.provider_type == normalize_provider_type(provider_type),
            ).order_by(TranscriptEntryRecord.transcript_seq)).all()
            return [deepcopy(record.content_json) for record in records]

    def load_committed_provider_messages(self, context: TurnExecutionContext, provider_type: str = "OPENAI_COMPATIBLE", memory_window_size: int = 0) -> list[dict[str, Any]]:
        provider_type = normalize_provider_type(provider_type)
        window = min(self.settings.context_window_messages, max(1, memory_window_size)) if memory_window_size else self.settings.context_window_messages
        with self.session_factory() as session:
            state = self._assert_identity(session, context)
            latest = state.last_transcript_seq if state else 0
            memory = session.get(WorkingMemoryRecord, context.session_id)
            memory_version = memory.version if memory else 0
            # Validate cache version against PostgreSQL. Failed cache invalidation
            # must not resurrect stale summaries or hide committed messages.
            cached = self.transcript_cache.get(context, provider_type) if self.transcript_cache else None
            if cached is not None and cached.last_committed_seq == latest and cached.memory_version == memory_version and cached.window_size == window:
                return deepcopy(cached.messages)
            entries = list(reversed(session.scalars(select(TranscriptEntryRecord).where(
                TranscriptEntryRecord.session_id == context.session_id,
                TranscriptEntryRecord.provider_type == provider_type,
                TranscriptEntryRecord.status == "COMMITTED",
            ).order_by(TranscriptEntryRecord.transcript_seq.desc()).limit(window + 1)).all()))
            truncated = len(entries) > window
            if truncated:
                entries = entries[1:]
                # Start at a user/event boundary so an assistant's tool call is
                # never separated from its tool results by a sliding window.
                while entries and entries[0].role not in {"user", "system"}:
                    entries.pop(0)
            messages = [deepcopy(entry.content_json) for entry in entries]
            if memory:
                messages.insert(0, {"role": "user", "content": "Persistent working memory (derived context, verify against sources):\n" + json.dumps(
                    _memory_json(memory), ensure_ascii=False)})
            if truncated:
                messages.insert(0, {"role": "user", "content": "Earlier Session history remains durably stored. Use session_history to retrieve source records and history_summary to preserve decisions, open work and references. Recent history starts at transcript sequence " + str(entries[0].transcript_seq if entries else latest) + "."})
        if self.transcript_cache:
            self.transcript_cache.put(context, TranscriptCachePayload(context.session_id, context.participant_id, provider_type, latest, messages, memory_version, window))
        return messages

    def history(self, context: TurnExecutionContext, *, after: int = 0, limit: int = 50, query: str | None = None) -> dict[str, Any]:
        limit = max(1, min(limit, 200))
        with self.session_factory() as session:
            self._assert_identity(session, context)
            statement = select(TranscriptEntryRecord).where(TranscriptEntryRecord.session_id == context.session_id,
                TranscriptEntryRecord.transcript_seq > max(0, after))
            if query:
                # Literal substring search, not SQL wildcard expansion.
                statement = statement.where(TranscriptEntryRecord.content_json.cast(Text).contains(query, autoescape=True))
            entries = session.scalars(statement.order_by(TranscriptEntryRecord.transcript_seq).limit(limit + 1)).all()
            page = entries[:limit]
            return {"entries": [{"sequence": e.transcript_seq, "activationId": e.turn_execution_id,
                "status": e.status, "createdAt": e.created_at.isoformat(), "message": deepcopy(e.content_json)} for e in page],
                "nextCursor": page[-1].transcript_seq if len(entries) > limit else None}

    def memory(self, context: TurnExecutionContext) -> dict[str, Any]:
        with self.session_factory() as session:
            self._assert_identity(session, context)
            record = session.get(WorkingMemoryRecord, context.session_id)
            return _memory_json(record) if record else {"version": 0, "sourceThroughSequence": 0, "content": {}}

    def uncertain_tool_calls(self, context: TurnExecutionContext) -> list[dict[str, Any]]:
        with self.session_factory() as session:
            self._assert_identity(session, context)
            rows = session.scalars(select(ToolReceiptRecord).where(
                ToolReceiptRecord.session_id == context.session_id,
                ToolReceiptRecord.activation_id != context.turn_execution_id,
                ToolReceiptRecord.state == "STARTED").order_by(ToolReceiptRecord.created_at).limit(100)).all()
            return [{"receiptId": r.receipt_id, "activationId": r.activation_id,
                     "callId": r.call_id, "tool": r.tool_name, "state": "OUTCOME_UNKNOWN"} for r in rows]

    def tool_result_page(self, context: TurnExecutionContext, *, activation_id: str, call_id: str,
                         offset: int = 0, limit: int = 6000, result_sanitizer=None) -> dict[str, Any]:
        from .context_budget import encoded
        with self.session_factory() as session:
            self._assert_identity(session, context)
            receipt = session.scalar(select(ToolReceiptRecord).where(
                ToolReceiptRecord.session_id == context.session_id,
                ToolReceiptRecord.activation_id == activation_id, ToolReceiptRecord.call_id == call_id))
            if receipt is None or receipt.state != "COMPLETED":
                raise ValueError("completed tool receipt is not available in this Session")
            result = deepcopy(receipt.result_json)
            # Sanitize the complete object before slicing so a boundary cannot
            # split an identifier and bypass privacy detection.
            text = encoded(result_sanitizer(result) if result_sanitizer else result)
            offset, limit = max(0, offset), max(1, min(limit, 8000))
            end = min(len(text), offset + limit)
            return {"activationId": activation_id, "callId": call_id, "offset": offset,
                    "content": text[offset:end], "totalChars": len(text), "format": "JSON_TEXT_SLICE",
                    "nextOffset": end if end < len(text) else None}

    def write_memory(self, context: TurnExecutionContext, *, expected_version: int, source_through_seq: int, content: dict[str, Any]) -> dict[str, Any]:
        with self.session_factory.begin() as session:
            state, _ = self._running(session, context)
            record = session.get(WorkingMemoryRecord, context.session_id, with_for_update=True)
            if (record.version if record else 0) != expected_version:
                raise ValueError("working memory revision conflict")
            if source_through_seq < (record.source_through_seq if record else 0) or source_through_seq > state.last_transcript_seq:
                raise ValueError("working memory source range is invalid")
            if record is None:
                record = WorkingMemoryRecord(session_id=context.session_id, version=0, source_through_seq=0, content_json={})
                session.add(record)
            record.version += 1
            record.source_through_seq = source_through_seq
            record.content_json = deepcopy(content)
            record.updated_at = now_utc()
            result = _memory_json(record)
        self._invalidate(context)
        return result

    def begin_tool_call(self, context: TurnExecutionContext, call_id: str, tool_name: str, arguments: dict[str, Any], *, retry_safe: bool, require_activation: bool = True) -> dict[str, Any] | None:
        receipt_id = tool_receipt_id(context, call_id)
        with self.session_factory.begin() as session:
            self._running(session, context) if require_activation else self._session_context(session, context)
            receipt = session.get(ToolReceiptRecord, receipt_id, with_for_update=True)
            if receipt:
                if receipt.tool_name != tool_name or receipt.arguments_json != arguments:
                    raise ValueError("tool call identity reused with different arguments")
                if receipt.state == "COMPLETED":
                    return deepcopy(receipt.result_json)
                if not retry_safe:
                    return {"accepted": False, "error": {"code": "TOOL_OUTCOME_UNKNOWN",
                        "message": "A previous attempt started this tool but no completion receipt was recorded. Reconcile its external result before another write.",
                        "receiptId": receipt_id}}
                return None
            if not retry_safe:
                uncertain = session.scalar(select(ToolReceiptRecord).where(
                    ToolReceiptRecord.session_id == context.session_id,
                    ToolReceiptRecord.tool_name == tool_name,
                    ToolReceiptRecord.state == "STARTED").order_by(ToolReceiptRecord.created_at).limit(1))
                if uncertain is not None:
                    return {"accepted": False, "error": {"code": "TOOL_OUTCOME_UNKNOWN",
                        "message": "An earlier call to this external operation has no completion receipt. Changing call IDs cannot safely retry it; reconcile the external outcome first.",
                        "receiptId": uncertain.receipt_id}}
            session.add(ToolReceiptRecord(receipt_id=receipt_id, session_id=context.session_id,
                activation_id=context.turn_execution_id, call_id=call_id, tool_name=tool_name,
                arguments_json=deepcopy(arguments), state="STARTED"))
        return None

    def complete_tool_call(self, context: TurnExecutionContext, call_id: str, result: dict[str, Any], *, require_activation: bool = True) -> None:
        with self.session_factory.begin() as session:
            self._running(session, context) if require_activation else self._session_context(session, context)
            receipt = session.get(ToolReceiptRecord, tool_receipt_id(context, call_id), with_for_update=True)
            if receipt is None:
                raise RuntimeError("tool call was not reserved")
            if receipt.state == "COMPLETED" and receipt.result_json != result:
                raise ValueError("tool receipt cannot be overwritten")
            receipt.state = "COMPLETED"
            receipt.result_json = deepcopy(result)
            receipt.completed_at = now_utc()

    def commit_success(self, context: TurnExecutionContext, outcome: ActivationOutcome, entries: list[TranscriptEntry]) -> None:
        with self.session_factory.begin() as session:
            state, record = self._running(session, context)
            self._append(session, state, context, entries)
            for entry in session.scalars(select(TranscriptEntryRecord).where(TranscriptEntryRecord.turn_execution_id == context.turn_execution_id)):
                entry.status = "COMMITTED"
            record.status = "SUCCEEDED"
            record.final_outcome_json = outcome.model_dump(mode="json")
            record.failure_reason = None
            record.completed_at = now_utc()
            _release(state)
        self._invalidate(context)

    def mark_failed(self, context: TurnExecutionContext, reason: str) -> None:
        with self.session_factory.begin() as session:
            state, record = self._running(session, context)
            record.status = "FAILED"
            record.failure_reason = reason
            record.completed_at = now_utc()
            _release(state)

    def sweep_expired(self, *, now: datetime | None = None, limit: int | None = None) -> dict[str, int]:
        # Expire compute leases only. Durable context has no generic TTL.
        cutoff = now or now_utc()
        with self.session_factory.begin() as session:
            states = session.scalars(select(SessionContextRecord).where(SessionContextRecord.lease_until <= cutoff)
                .limit(limit or self.settings.retention_sweep_limit).with_for_update(skip_locked=True)).all()
            for state in states:
                record = session.get(TurnExecutionRecord, state.active_execution_id) if state.active_execution_id else None
                if record and record.status == "RUNNING":
                    record.status = "INTERRUPTED"
                    record.failure_reason = "runtime lease expired; durable checkpoints and receipts retained"
                _release(state)
        return {"activationLeasesExpired": len(states), "transcriptEntriesDeleted": 0}

    def _assert_identity(self, session: Session, context: TurnExecutionContext) -> SessionContextRecord | None:
        state = session.get(SessionContextRecord, context.session_id)
        if state and state.participant_id != context.participant_id:
            raise ValueError("Session participant identity mismatch")
        return state

    def _invalidate(self, context: TurnExecutionContext) -> None:
        if self.transcript_cache:
            self.transcript_cache.delete(context)

    def check_database(self) -> dict[str, object]:
        with self.engine.connect() as connection:
            connection.execute(text("select 1")).scalar_one()
            for table in Base.metadata.sorted_tables:
                connection.execute(select(table).limit(0))
        return {"backend": "postgresql", "schema": DEFAULT_SCHEMA_NAME}

    def close(self) -> None:
        self.engine.dispose()
        if self.transcript_cache:
            self.transcript_cache.close()


# Structural annotation shared by streaming and the SQL-backed test stores.
TranscriptStore = PostgresTranscriptStore


def create_transcript_store(settings: TranscriptStoreSettings | None = None, *, transcript_cache: TranscriptCache | None = None) -> PostgresTranscriptStore:
    return PostgresTranscriptStore(settings, transcript_cache=transcript_cache)


def ensure_postgres_configuration(database_url: str) -> None:
    if make_url(database_url).get_backend_name() != "postgresql":
        raise RuntimeError("agent-runtime transcript store requires PostgreSQL")


def transcript_cache_key(context: TurnExecutionContext) -> str:
    return f"agent-runtime:transcript:{context.session_id}"


def tool_receipt_id(context: TurnExecutionContext, call_id: str) -> str:
    return hashlib.sha256(f"{context.session_id}:{context.turn_execution_id}:{call_id}".encode()).hexdigest()


def transcript_entries_from_provider_messages(messages: list[dict[str, Any]], *, provider_type: str = "OPENAI_COMPATIBLE", model_round_id: str) -> list[TranscriptEntry]:
    return [transcript_entry_from_provider_message(message, provider_type=provider_type, model_round_id=model_round_id, seq=i)
        for i, message in enumerate(messages, 1)]


def transcript_entry_from_provider_message(message: dict[str, Any], *, provider_type: str = "OPENAI_COMPATIBLE", model_round_id: str, seq: int) -> TranscriptEntry:
    return TranscriptEntry(str(message.get("role") or ""), normalize_provider_type(provider_type), deepcopy(message), model_round_id, seq)


def normalize_provider_type(provider_type: str | None) -> str:
    return (provider_type or "OPENAI_COMPATIBLE").strip().upper() or "OPENAI_COMPATIBLE"


def _memory_json(record: WorkingMemoryRecord) -> dict[str, Any]:
    return {"version": record.version, "sourceThroughSequence": record.source_through_seq,
        "content": deepcopy(record.content_json), "updatedAt": record.updated_at.isoformat()}


def _assert_same_context(record: TurnExecutionRecord, context: TurnExecutionContext) -> None:
    if (record.session_id, record.participant_id, record.turn_id) != (context.session_id, context.participant_id, context.turn_id):
        raise ValueError("activation identity does not match its persistent Session")


def _release(state: SessionContextRecord) -> None:
    state.active_execution_id = None
    state.active_attempt_id = None
    state.lease_until = None


def _utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _positive_int_env(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
        return value if value > 0 else default
    except ValueError:
        return default
