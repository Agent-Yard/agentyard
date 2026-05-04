from __future__ import annotations

import os
import uuid
import json
import logging
from copy import deepcopy
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from redis.exceptions import RedisError
from sqlalchemy import DateTime, Integer, JSON, MetaData, String, Text, UniqueConstraint, create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

from .models import AgentTurnExecutionOutcome
from .redis_support import RedisSettings, create_sync_redis_client

DEFAULT_DATABASE_URL = "postgresql+psycopg://lynxus:lynxus@127.0.0.1:5432/lynxus_agent_runtime"
DEFAULT_SCHEMA_NAME = "public"
DEFAULT_TURN_EXECUTION_RETENTION_SECONDS = 30 * 24 * 60 * 60
DEFAULT_TRANSCRIPT_ENTRY_RETENTION_SECONDS = 30 * 24 * 60 * 60
DEFAULT_RETENTION_SWEEP_LIMIT = 500
DEFAULT_TRANSCRIPT_CACHE_TTL_SECONDS = 60 * 60
LOGGER = logging.getLogger(__name__)


def now_utc() -> datetime:
    return datetime.now(UTC)


@dataclass(frozen=True)
class TranscriptStoreSettings:
    database_url: str = DEFAULT_DATABASE_URL
    turn_execution_retention_seconds: int = DEFAULT_TURN_EXECUTION_RETENTION_SECONDS
    transcript_entry_retention_seconds: int = DEFAULT_TRANSCRIPT_ENTRY_RETENTION_SECONDS
    retention_sweep_limit: int = DEFAULT_RETENTION_SWEEP_LIMIT
    transcript_cache_ttl_seconds: int = DEFAULT_TRANSCRIPT_CACHE_TTL_SECONDS

    @classmethod
    def from_env(cls) -> "TranscriptStoreSettings":
        return cls(
            database_url=(os.getenv("LYNXUS_AGENT_RUNTIME_DATABASE_URL") or DEFAULT_DATABASE_URL).strip()
            or DEFAULT_DATABASE_URL,
            turn_execution_retention_seconds=_positive_int_env(
                "LYNXUS_AGENT_RUNTIME_TURN_EXECUTION_RETENTION_SECONDS",
                DEFAULT_TURN_EXECUTION_RETENTION_SECONDS,
            ),
            transcript_entry_retention_seconds=_positive_int_env(
                "LYNXUS_AGENT_RUNTIME_TRANSCRIPT_ENTRY_RETENTION_SECONDS",
                DEFAULT_TRANSCRIPT_ENTRY_RETENTION_SECONDS,
            ),
            retention_sweep_limit=_positive_int_env(
                "LYNXUS_AGENT_RUNTIME_RETENTION_SWEEP_LIMIT",
                DEFAULT_RETENTION_SWEEP_LIMIT,
            ),
            transcript_cache_ttl_seconds=_positive_int_env(
                "LYNXUS_AGENT_RUNTIME_TRANSCRIPT_CACHE_TTL_SECONDS",
                DEFAULT_TRANSCRIPT_CACHE_TTL_SECONDS,
            ),
        )


@dataclass(frozen=True)
class TurnExecutionContext:
    session_id: str
    owner_agent_id: str
    ownership_epoch: int
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
    owner_agent_id: str
    ownership_epoch: int
    provider_type: str
    last_committed_seq: int
    messages: list[dict[str, Any]]


class TranscriptCache(Protocol):
    def get(self, context: TurnExecutionContext, provider_type: str) -> TranscriptCachePayload | None:
        ...

    def put(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None:
        ...

    def delete(self, context: TurnExecutionContext) -> None:
        ...

    def close(self) -> None:
        ...


class RedisTranscriptCache:
    def __init__(self, redis_client: Any, ttl_seconds: int) -> None:
        self.redis_client = redis_client
        self.ttl_seconds = max(1, ttl_seconds)

    def get(self, context: TurnExecutionContext, provider_type: str) -> TranscriptCachePayload | None:
        key = transcript_cache_key(context)
        try:
            raw_payload = self.redis_client.get(key)
        except RedisError as error:
            LOGGER.warning("transcript cache read failed key=%s reason=%s", key, error)
            return None
        if raw_payload is None:
            return None
        try:
            payload = json.loads(raw_payload)
        except (TypeError, json.JSONDecodeError):
            LOGGER.warning("transcript cache payload is malformed key=%s", key)
            return None
        return _hydrate_transcript_cache_payload(payload, context, provider_type)

    def put(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None:
        key = transcript_cache_key(context)
        try:
            self.redis_client.set(
                key,
                json.dumps(_transcript_cache_payload_json(payload), ensure_ascii=False),
                ex=self.ttl_seconds,
            )
        except RedisError as error:
            LOGGER.warning("transcript cache write failed key=%s reason=%s", key, error)

    def delete(self, context: TurnExecutionContext) -> None:
        key = transcript_cache_key(context)
        try:
            self.redis_client.delete(key)
        except RedisError as error:
            LOGGER.warning("transcript cache delete failed key=%s reason=%s", key, error)

    def close(self) -> None:
        close = getattr(self.redis_client, "close", None)
        if callable(close):
            close()


def create_transcript_cache(
    redis_settings: RedisSettings,
    store_settings: TranscriptStoreSettings,
) -> RedisTranscriptCache:
    return RedisTranscriptCache(
        create_sync_redis_client(redis_settings),
        ttl_seconds=store_settings.transcript_cache_ttl_seconds,
    )


class TranscriptStore(Protocol):
    def begin_execution(self, context: TurnExecutionContext) -> AgentTurnExecutionOutcome | None:
        ...

    def load_committed_provider_messages(
        self,
        context: TurnExecutionContext,
        provider_type: str = "OPENAI_COMPATIBLE",
    ) -> list[dict[str, Any]]:
        ...

    def append_pending_entries(self, context: TurnExecutionContext, entries: list[TranscriptEntry]) -> None:
        ...

    def commit_success(
        self,
        context: TurnExecutionContext,
        outcome: AgentTurnExecutionOutcome,
        entries: list[TranscriptEntry],
    ) -> None:
        ...

    def mark_failed(self, context: TurnExecutionContext, reason: str) -> None:
        ...

    def sweep_expired(self, *, now: datetime | None = None, limit: int | None = None) -> dict[str, int]:
        ...

    def check_database(self) -> dict[str, object]:
        ...

    def close(self) -> None:
        ...


class Base(DeclarativeBase):
    metadata = MetaData()


class TurnExecutionRecord(Base):
    __tablename__ = "turn_execution"

    turn_execution_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    owner_agent_id: Mapped[str] = mapped_column(String(128), index=True)
    ownership_epoch: Mapped[int] = mapped_column(Integer, index=True)
    turn_id: Mapped[str] = mapped_column(String(128), index=True)
    current_execution_attempt_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    status: Mapped[str] = mapped_column(String(32), index=True)
    final_outcome_json: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class TranscriptEntryRecord(Base):
    __tablename__ = "transcript_entry"
    __table_args__ = (
        UniqueConstraint(
            "session_id",
            "owner_agent_id",
            "ownership_epoch",
            "transcript_seq",
            name="uq_transcript_context_seq",
        ),
    )

    entry_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    owner_agent_id: Mapped[str] = mapped_column(String(128), index=True)
    ownership_epoch: Mapped[int] = mapped_column(Integer, index=True)
    turn_execution_id: Mapped[str] = mapped_column(String(128), index=True)
    execution_attempt_id: Mapped[str | None] = mapped_column(String(128), index=True, nullable=True)
    transcript_seq: Mapped[int] = mapped_column(Integer, index=True)
    model_round_id: Mapped[str] = mapped_column(String(128), index=True)
    seq: Mapped[int] = mapped_column(Integer)
    role: Mapped[str] = mapped_column(String(32))
    provider_type: Mapped[str] = mapped_column(String(64), index=True)
    content_json: Mapped[dict[str, Any]] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(32), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class OwnerContextSequenceRecord(Base):
    __tablename__ = "owner_context_sequence"

    sequence_id: Mapped[str] = mapped_column(String(512), primary_key=True)
    session_id: Mapped[str] = mapped_column(String(128), index=True)
    owner_agent_id: Mapped[str] = mapped_column(String(128), index=True)
    ownership_epoch: Mapped[int] = mapped_column(Integer, index=True)
    last_transcript_seq: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class PostgresTranscriptStore:
    def __init__(
        self,
        settings: TranscriptStoreSettings | None = None,
        *,
        transcript_cache: TranscriptCache | None = None,
    ) -> None:
        self.settings = settings or TranscriptStoreSettings.from_env()
        self.transcript_cache = transcript_cache
        self.engine = create_engine(self.settings.database_url, future=True)
        self.session_factory = sessionmaker(bind=self.engine, autoflush=False, autocommit=False, expire_on_commit=False)

    def initialize(self) -> None:
        ensure_postgres_configuration(self.settings.database_url)
        with self.engine.begin() as connection:
            Base.metadata.create_all(bind=connection)
            connection.execute(
                text(
                    "alter table turn_execution "
                    "add column if not exists current_execution_attempt_id varchar(128)"
                )
            )
            connection.execute(
                text(
                    "alter table transcript_entry "
                    "add column if not exists execution_attempt_id varchar(128)"
                )
            )
            connection.execute(
                text(
                    "alter table transcript_entry "
                    "add column if not exists provider_type varchar(64)"
                )
            )
            connection.execute(
                text(
                    "update transcript_entry "
                    "set provider_type = 'OPENAI_COMPATIBLE' "
                    "where provider_type is null"
                )
            )
            connection.execute(
                text(
                    "create index if not exists idx_transcript_context_committed "
                    "on transcript_entry "
                    "(session_id, owner_agent_id, ownership_epoch, status, transcript_seq)"
                )
            )
            connection.execute(
                text(
                    "create unique index if not exists uq_transcript_context_seq "
                    "on transcript_entry "
                    "(session_id, owner_agent_id, ownership_epoch, transcript_seq)"
                )
            )

    def begin_execution(self, context: TurnExecutionContext) -> AgentTurnExecutionOutcome | None:
        expires_at = self._turn_execution_expires_at()
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id)
            if record is not None:
                _assert_same_context(record, context)
                if record.status == "SUCCEEDED" and record.final_outcome_json is not None:
                    return AgentTurnExecutionOutcome.model_validate(record.final_outcome_json)
                if record.status == "RUNNING":
                    return AgentTurnExecutionOutcome(
                        success=False,
                        failureReason="turn execution is already RUNNING for this turnExecutionId; retry later",
                    )
                record.status = "RUNNING"
                record.current_execution_attempt_id = context.execution_attempt_id
                record.final_outcome_json = None
                record.failure_reason = None
                record.completed_at = None
                record.expires_at = expires_at
                _abort_all_pending_entries(session, context, expires_at=self._transcript_entry_expires_at())
            else:
                session.add(
                    TurnExecutionRecord(
                        turn_execution_id=context.turn_execution_id,
                        session_id=context.session_id,
                        owner_agent_id=context.owner_agent_id,
                        ownership_epoch=context.ownership_epoch,
                        turn_id=context.turn_id,
                        current_execution_attempt_id=context.execution_attempt_id,
                        status="RUNNING",
                        expires_at=expires_at,
                    )
                )
            session.commit()
        return None

    def load_committed_provider_messages(
        self,
        context: TurnExecutionContext,
        provider_type: str = "OPENAI_COMPATIBLE",
    ) -> list[dict[str, Any]]:
        normalized_provider_type = normalize_provider_type(provider_type)
        cached_payload = self._get_transcript_cache(context, normalized_provider_type)
        if cached_payload is not None:
            return [_clone_provider_message(message) for message in cached_payload.messages]
        with self.session_factory() as session:
            entries = session.scalars(
                select(TranscriptEntryRecord)
                .where(
                    TranscriptEntryRecord.session_id == context.session_id,
                    TranscriptEntryRecord.owner_agent_id == context.owner_agent_id,
                    TranscriptEntryRecord.ownership_epoch == context.ownership_epoch,
                    TranscriptEntryRecord.provider_type == normalized_provider_type,
                    TranscriptEntryRecord.status == "COMMITTED",
                )
                .order_by(TranscriptEntryRecord.transcript_seq.asc())
            ).all()
        committed = [
            CommittedTranscriptEntry(
                transcript_seq=entry.transcript_seq,
                role=entry.role,
                provider_type=entry.provider_type,
                content_json=_clone_provider_message(entry.content_json),
            )
            for entry in entries
        ]
        messages = [_clone_provider_message(entry.content_json) for entry in committed]
        self._put_transcript_cache(
            context,
            TranscriptCachePayload(
                session_id=context.session_id,
                owner_agent_id=context.owner_agent_id,
                ownership_epoch=context.ownership_epoch,
                provider_type=normalized_provider_type,
                last_committed_seq=max((entry.transcript_seq for entry in committed), default=0),
                messages=messages,
            ),
        )
        return messages

    def append_pending_entries(self, context: TurnExecutionContext, entries: list[TranscriptEntry]) -> None:
        if not entries:
            return
        expires_at = self._transcript_entry_expires_at()
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id, with_for_update=True)
            if record is None or record.status != "RUNNING":
                raise RuntimeError("turn execution is not RUNNING")
            _assert_same_context(record, context)
            _assert_current_attempt(record, context)
            next_transcript_seq = _allocate_transcript_seqs(session, context, len(entries))
            for offset, entry in enumerate(entries):
                session.add(
                    _transcript_entry_record(
                        context,
                        entry,
                        next_transcript_seq + offset,
                        "PENDING",
                        expires_at=expires_at,
                    )
                )
            session.commit()

    def commit_success(
        self,
        context: TurnExecutionContext,
        outcome: AgentTurnExecutionOutcome,
        entries: list[TranscriptEntry],
    ) -> None:
        outcome_json = outcome.model_dump(mode="json")
        completed_at = now_utc()
        turn_expires_at = self._turn_execution_expires_at()
        transcript_expires_at = self._transcript_entry_expires_at()
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id)
            if record is None:
                record = TurnExecutionRecord(
                    turn_execution_id=context.turn_execution_id,
                    session_id=context.session_id,
                    owner_agent_id=context.owner_agent_id,
                    ownership_epoch=context.ownership_epoch,
                    turn_id=context.turn_id,
                    current_execution_attempt_id=context.execution_attempt_id,
                    status="RUNNING",
                    expires_at=turn_expires_at,
                )
                session.add(record)
            else:
                _assert_same_context(record, context)
                _assert_current_attempt(record, context)
                if record.status != "RUNNING":
                    raise RuntimeError("turn execution is not RUNNING")
            _update_pending_entries(session, context, "COMMITTED", expires_at=transcript_expires_at)
            next_transcript_seq = _allocate_transcript_seqs(session, context, len(entries))
            for offset, entry in enumerate(entries):
                session.add(
                    _transcript_entry_record(
                        context,
                        entry,
                        next_transcript_seq + offset,
                        "COMMITTED",
                        expires_at=transcript_expires_at,
                    )
                )
            record.status = "SUCCEEDED"
            record.final_outcome_json = outcome_json
            record.failure_reason = None
            record.completed_at = completed_at
            record.expires_at = turn_expires_at
            session.commit()
        self._delete_transcript_cache(context)

    def mark_failed(self, context: TurnExecutionContext, reason: str) -> None:
        turn_expires_at = self._turn_execution_expires_at()
        transcript_expires_at = self._transcript_entry_expires_at()
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id)
            if record is None:
                record = TurnExecutionRecord(
                    turn_execution_id=context.turn_execution_id,
                    session_id=context.session_id,
                    owner_agent_id=context.owner_agent_id,
                    ownership_epoch=context.ownership_epoch,
                    turn_id=context.turn_id,
                    current_execution_attempt_id=context.execution_attempt_id,
                    status="FAILED",
                    expires_at=turn_expires_at,
                )
                session.add(record)
            else:
                _assert_same_context(record, context)
                if record.status == "SUCCEEDED":
                    session.commit()
                    return
                _assert_current_attempt(record, context)
                record.status = "FAILED"
            record.final_outcome_json = None
            record.failure_reason = reason
            record.completed_at = now_utc()
            record.expires_at = turn_expires_at
            _update_pending_entries(session, context, "ABORTED", expires_at=transcript_expires_at)
            session.commit()

    def sweep_expired(self, *, now: datetime | None = None, limit: int | None = None) -> dict[str, int]:
        cutoff = now or now_utc()
        bounded_limit = self._bounded_sweep_limit(limit)
        with self.session_factory() as session:
            committed_entries = session.scalars(
                select(TranscriptEntryRecord)
                .where(
                    TranscriptEntryRecord.status != "PENDING",
                    TranscriptEntryRecord.expires_at.is_not(None),
                    TranscriptEntryRecord.expires_at <= cutoff,
                )
                .order_by(TranscriptEntryRecord.expires_at.asc())
                .limit(bounded_limit)
            ).all()
            committed_entry_count = len(committed_entries)
            for entry in committed_entries:
                session.delete(entry)

            completed_records = session.scalars(
                select(TurnExecutionRecord)
                .where(
                    TurnExecutionRecord.status != "RUNNING",
                    TurnExecutionRecord.expires_at.is_not(None),
                    TurnExecutionRecord.expires_at <= cutoff,
                )
                .order_by(TurnExecutionRecord.expires_at.asc())
                .limit(bounded_limit)
            ).all()
            completed_record_count = len(completed_records)
            for record in completed_records:
                session.delete(record)

            running_records = session.scalars(
                select(TurnExecutionRecord)
                .where(
                    TurnExecutionRecord.status == "RUNNING",
                    TurnExecutionRecord.expires_at.is_not(None),
                    TurnExecutionRecord.expires_at <= cutoff,
                )
                .order_by(TurnExecutionRecord.expires_at.asc())
                .limit(bounded_limit)
            ).all()
            for record in running_records:
                record.status = "ABORTED"
                record.failure_reason = record.failure_reason or "turn execution expired before completion"
                record.completed_at = cutoff

            pending_entries = session.scalars(
                select(TranscriptEntryRecord)
                .where(
                    TranscriptEntryRecord.status == "PENDING",
                    TranscriptEntryRecord.expires_at.is_not(None),
                    TranscriptEntryRecord.expires_at <= cutoff,
                )
                .order_by(TranscriptEntryRecord.expires_at.asc())
                .limit(bounded_limit)
            ).all()
            for entry in pending_entries:
                entry.status = "ABORTED"

            session.commit()
        return {
            "turnExecutionsAborted": len(running_records),
            "turnExecutionsDeleted": completed_record_count,
            "transcriptEntriesAborted": len(pending_entries),
            "transcriptEntriesDeleted": committed_entry_count,
        }

    def check_database(self) -> dict[str, object]:
        with self.engine.connect() as connection:
            result = connection.execute(text("select 1")).scalar_one()
            if result != 1:
                raise RuntimeError("postgres readiness probe returned unexpected result")
            connection.execute(text("select count(*) from turn_execution")).scalar_one()
            connection.execute(text("select count(*) from transcript_entry")).scalar_one()
            connection.execute(text("select count(*) from owner_context_sequence")).scalar_one()
        return {"backend": "postgresql", "schema": DEFAULT_SCHEMA_NAME}

    def close(self) -> None:
        self.engine.dispose()
        if self.transcript_cache is not None:
            self.transcript_cache.close()

    def _turn_execution_expires_at(self) -> datetime:
        return now_utc() + timedelta(seconds=self.settings.turn_execution_retention_seconds)

    def _transcript_entry_expires_at(self) -> datetime:
        return now_utc() + timedelta(seconds=self.settings.transcript_entry_retention_seconds)

    def _bounded_sweep_limit(self, limit: int | None) -> int:
        configured_limit = max(1, self.settings.retention_sweep_limit)
        if limit is None:
            return configured_limit
        return max(1, min(limit, configured_limit))

    def _get_transcript_cache(
        self,
        context: TurnExecutionContext,
        provider_type: str,
    ) -> TranscriptCachePayload | None:
        if self.transcript_cache is None:
            return None
        return self.transcript_cache.get(context, provider_type)

    def _put_transcript_cache(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None:
        if self.transcript_cache is not None:
            self.transcript_cache.put(context, payload)

    def _delete_transcript_cache(self, context: TurnExecutionContext) -> None:
        if self.transcript_cache is not None:
            self.transcript_cache.delete(context)


def create_transcript_store(
    settings: TranscriptStoreSettings | None = None,
    *,
    transcript_cache: TranscriptCache | None = None,
) -> PostgresTranscriptStore:
    return PostgresTranscriptStore(settings, transcript_cache=transcript_cache)


def ensure_postgres_configuration(database_url: str) -> None:
    try:
        url = make_url(database_url)
    except Exception as exc:  # pragma: no cover
        raise RuntimeError(f"invalid agent-runtime database url: {exc}") from exc
    if url.get_backend_name() != "postgresql":
        raise RuntimeError("agent-runtime transcript store requires PostgreSQL")


def _positive_int_env(name: str, default: int) -> int:
    raw_value = (os.getenv(name) or "").strip()
    if not raw_value:
        return default
    try:
        value = int(raw_value)
    except ValueError:
        return default
    return value if value > 0 else default


def transcript_cache_key(context: TurnExecutionContext) -> str:
    return f"agent-runtime:transcript:{context.session_id}:{context.owner_agent_id}:{context.ownership_epoch}"


def _hydrate_transcript_cache_payload(
    payload: Any,
    context: TurnExecutionContext,
    provider_type: str,
) -> TranscriptCachePayload | None:
    if not isinstance(payload, dict):
        return None
    try:
        ownership_epoch = int(payload.get("ownershipEpoch") or -1)
        last_committed_seq = int(payload.get("lastCommittedSeq") or 0)
    except (TypeError, ValueError):
        return None
    if (
        payload.get("sessionId") != context.session_id
        or payload.get("ownerAgentId") != context.owner_agent_id
        or ownership_epoch != context.ownership_epoch
        or normalize_provider_type(str(payload.get("providerType") or "")) != normalize_provider_type(provider_type)
    ):
        return None
    messages = payload.get("messages")
    if not isinstance(messages, list) or not all(isinstance(message, dict) for message in messages):
        return None
    return TranscriptCachePayload(
        session_id=context.session_id,
        owner_agent_id=context.owner_agent_id,
        ownership_epoch=context.ownership_epoch,
        provider_type=normalize_provider_type(provider_type),
        last_committed_seq=last_committed_seq,
        messages=[_clone_provider_message(message) for message in messages],
    )


def _transcript_cache_payload_json(payload: TranscriptCachePayload) -> dict[str, Any]:
    return {
        "sessionId": payload.session_id,
        "ownerAgentId": payload.owner_agent_id,
        "ownershipEpoch": payload.ownership_epoch,
        "providerType": normalize_provider_type(payload.provider_type),
        "lastCommittedSeq": payload.last_committed_seq,
        "messages": [_clone_provider_message(message) for message in payload.messages],
    }


def _clone_provider_message(message: dict[str, Any]) -> dict[str, Any]:
    return deepcopy(message)


def transcript_entries_from_provider_messages(
    messages: list[dict[str, Any]],
    *,
    provider_type: str = "OPENAI_COMPATIBLE",
    model_round_id: str,
) -> list[TranscriptEntry]:
    return [
        transcript_entry_from_provider_message(
            message,
            provider_type=provider_type,
            model_round_id=model_round_id,
            seq=index,
        )
        for index, message in enumerate(messages, start=1)
    ]


def transcript_entry_from_provider_message(
    message: dict[str, Any],
    *,
    provider_type: str = "OPENAI_COMPATIBLE",
    model_round_id: str,
    seq: int,
) -> TranscriptEntry:
    return TranscriptEntry(
        role=str(message.get("role") or ""),
        provider_type=normalize_provider_type(provider_type),
        content_json=_clone_provider_message(message),
        model_round_id=model_round_id,
        seq=seq,
    )


def normalize_provider_type(provider_type: str | None) -> str:
    return (provider_type or "OPENAI_COMPATIBLE").strip().upper() or "OPENAI_COMPATIBLE"


def _allocate_transcript_seqs(session: Session, context: TurnExecutionContext, count: int) -> int:
    if count <= 0:
        return 0
    sequence_id = _owner_context_sequence_id(context)
    record = session.get(OwnerContextSequenceRecord, sequence_id, with_for_update=True)
    if record is None:
        record = OwnerContextSequenceRecord(
            sequence_id=sequence_id,
            session_id=context.session_id,
            owner_agent_id=context.owner_agent_id,
            ownership_epoch=context.ownership_epoch,
            last_transcript_seq=0,
        )
        session.add(record)
        session.flush()
    first_seq = record.last_transcript_seq + 1
    record.last_transcript_seq += count
    record.updated_at = now_utc()
    return first_seq


def _transcript_entry_record(
    context: TurnExecutionContext,
    entry: TranscriptEntry,
    transcript_seq: int,
    status: str,
    *,
    expires_at: datetime | None = None,
) -> TranscriptEntryRecord:
    return TranscriptEntryRecord(
        entry_id="entry-" + str(uuid.uuid4()),
        session_id=context.session_id,
        owner_agent_id=context.owner_agent_id,
        ownership_epoch=context.ownership_epoch,
        turn_execution_id=context.turn_execution_id,
        execution_attempt_id=context.execution_attempt_id,
        transcript_seq=transcript_seq,
        model_round_id=entry.model_round_id,
        seq=entry.seq,
        role=entry.role,
        provider_type=normalize_provider_type(entry.provider_type),
        content_json=entry.content_json,
        status=status,
        expires_at=expires_at,
    )


def _update_pending_entries(
    session: Session,
    context: TurnExecutionContext,
    status: str,
    *,
    expires_at: datetime | None = None,
) -> None:
    entries = session.scalars(
        select(TranscriptEntryRecord).where(
            TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
            TranscriptEntryRecord.execution_attempt_id == context.execution_attempt_id,
            TranscriptEntryRecord.status == "PENDING",
        )
    ).all()
    for entry in entries:
        entry.status = status
        if expires_at is not None:
            entry.expires_at = expires_at


def _abort_all_pending_entries(
    session: Session,
    context: TurnExecutionContext,
    *,
    expires_at: datetime | None = None,
) -> None:
    entries = session.scalars(
        select(TranscriptEntryRecord).where(
            TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
            TranscriptEntryRecord.status == "PENDING",
        )
    ).all()
    for entry in entries:
        entry.status = "ABORTED"
        if expires_at is not None:
            entry.expires_at = expires_at


def _assert_same_context(record: TurnExecutionRecord, context: TurnExecutionContext) -> None:
    if (
        record.session_id != context.session_id
        or record.owner_agent_id != context.owner_agent_id
        or record.ownership_epoch != context.ownership_epoch
        or record.turn_id != context.turn_id
    ):
        raise RuntimeError("turnExecutionId already exists for a different owner-context")


def _assert_current_attempt(record: TurnExecutionRecord, context: TurnExecutionContext) -> None:
    if record.current_execution_attempt_id != context.execution_attempt_id:
        raise RuntimeError("turn execution attempt is no longer current")


def _owner_context_sequence_id(context: TurnExecutionContext) -> str:
    return f"{context.session_id}\x1f{context.owner_agent_id}\x1f{context.ownership_epoch}"
