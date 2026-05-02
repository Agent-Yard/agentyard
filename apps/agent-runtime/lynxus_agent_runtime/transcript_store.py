from __future__ import annotations

import os
import uuid
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Literal, Protocol

from sqlalchemy import DateTime, Integer, JSON, MetaData, String, Text, UniqueConstraint, create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

from .models import AgentTurnExecutionOutcome
from .openai_compatible import OpenAiCompatibleStreamMessage

DEFAULT_DATABASE_URL = "postgresql+psycopg://lynxus:lynxus@127.0.0.1:5432/lynxus_core"
SCHEMA_NAME = "agent_runtime"


def now_utc() -> datetime:
    return datetime.now(UTC)


@dataclass(frozen=True)
class TranscriptStoreSettings:
    database_url: str = DEFAULT_DATABASE_URL

    @classmethod
    def from_env(cls) -> "TranscriptStoreSettings":
        return cls(
            database_url=(os.getenv("LYNXUS_AGENT_RUNTIME_DATABASE_URL") or DEFAULT_DATABASE_URL).strip()
            or DEFAULT_DATABASE_URL
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
    content_json: dict[str, Any]
    model_round_id: str
    seq: int


RuntimeContentBlockType = Literal[
    "text",
    "thinking",
    "tool_call",
    "tool_result",
    "runtime_reminder",
    "system_runtime_context",
]


@dataclass(frozen=True)
class RuntimeContentBlock:
    type: RuntimeContentBlockType
    text: str | None = None
    id: str | None = None
    name: str | None = None
    arguments: dict[str, Any] | None = None
    tool_call_id: str | None = None
    content: Any = None

    def to_json(self) -> dict[str, Any]:
        if self.type in {"text", "thinking", "runtime_reminder", "system_runtime_context"}:
            return {"type": self.type, "text": self.text or ""}
        if self.type == "tool_call":
            return {
                "type": "tool_call",
                "id": self.id or "",
                "name": self.name or "",
                "arguments": dict(self.arguments or {}),
            }
        if self.type == "tool_result":
            return {
                "type": "tool_result",
                "tool_call_id": self.tool_call_id or "",
                "content": self.content if self.content is not None else "",
            }
        raise ValueError(f"unsupported runtime content block type: {self.type}")


@dataclass(frozen=True)
class CommittedTranscriptEntry:
    transcript_seq: int
    role: str
    content_json: dict[str, Any]


class TranscriptStore(Protocol):
    def begin_execution(self, context: TurnExecutionContext) -> AgentTurnExecutionOutcome | None:
        ...

    def load_committed_provider_messages(self, context: TurnExecutionContext) -> list[dict[str, Any]]:
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

    def check_database(self) -> dict[str, object]:
        ...

    def close(self) -> None:
        ...


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA_NAME)


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
            name="uq_agent_runtime_transcript_context_seq",
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
    def __init__(self, settings: TranscriptStoreSettings | None = None) -> None:
        self.settings = settings or TranscriptStoreSettings.from_env()
        self.engine = create_engine(self.settings.database_url, future=True)
        self.session_factory = sessionmaker(bind=self.engine, autoflush=False, autocommit=False, expire_on_commit=False)

    def initialize(self) -> None:
        ensure_postgres_configuration(self.settings.database_url)
        with self.engine.begin() as connection:
            connection.execute(text(f"create schema if not exists {SCHEMA_NAME}"))
            Base.metadata.create_all(bind=connection)
            connection.execute(
                text(
                    "alter table agent_runtime.turn_execution "
                    "add column if not exists current_execution_attempt_id varchar(128)"
                )
            )
            connection.execute(
                text(
                    "alter table agent_runtime.transcript_entry "
                    "add column if not exists execution_attempt_id varchar(128)"
                )
            )
            connection.execute(
                text(
                    "create index if not exists idx_agent_runtime_transcript_context_committed "
                    "on agent_runtime.transcript_entry "
                    "(session_id, owner_agent_id, ownership_epoch, status, transcript_seq)"
                )
            )
            connection.execute(
                text(
                    "create unique index if not exists uq_agent_runtime_transcript_context_seq "
                    "on agent_runtime.transcript_entry "
                    "(session_id, owner_agent_id, ownership_epoch, transcript_seq)"
                )
            )

    def begin_execution(self, context: TurnExecutionContext) -> AgentTurnExecutionOutcome | None:
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
                _abort_all_pending_entries(session, context)
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
                    )
                )
            session.commit()
        return None

    def load_committed_provider_messages(self, context: TurnExecutionContext) -> list[dict[str, Any]]:
        with self.session_factory() as session:
            entries = session.scalars(
                select(TranscriptEntryRecord)
                .where(
                    TranscriptEntryRecord.session_id == context.session_id,
                    TranscriptEntryRecord.owner_agent_id == context.owner_agent_id,
                    TranscriptEntryRecord.ownership_epoch == context.ownership_epoch,
                    TranscriptEntryRecord.status == "COMMITTED",
                )
                .order_by(TranscriptEntryRecord.transcript_seq.asc())
            ).all()
        committed = [
            CommittedTranscriptEntry(
                transcript_seq=entry.transcript_seq,
                role=entry.role,
                content_json=dict(entry.content_json),
            )
            for entry in entries
        ]
        return transcript_entries_to_provider_messages(committed)

    def append_pending_entries(self, context: TurnExecutionContext, entries: list[TranscriptEntry]) -> None:
        if not entries:
            return
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id, with_for_update=True)
            if record is None or record.status != "RUNNING":
                raise RuntimeError("turn execution is not RUNNING")
            _assert_same_context(record, context)
            _assert_current_attempt(record, context)
            next_transcript_seq = _allocate_transcript_seqs(session, context, len(entries))
            for offset, entry in enumerate(entries):
                session.add(_transcript_entry_record(context, entry, next_transcript_seq + offset, "PENDING"))
            session.commit()

    def commit_success(
        self,
        context: TurnExecutionContext,
        outcome: AgentTurnExecutionOutcome,
        entries: list[TranscriptEntry],
    ) -> None:
        outcome_json = outcome.model_dump(mode="json")
        completed_at = now_utc()
        with self.session_factory() as session:
            record = session.get(TurnExecutionRecord, context.turn_execution_id)
            if record is None:
                record = TurnExecutionRecord(
                    turn_execution_id=context.turn_execution_id,
                    session_id=context.session_id,
                    owner_agent_id=context.owner_agent_id,
                    ownership_epoch=context.ownership_epoch,
                    turn_id=context.turn_id,
                    status="RUNNING",
                )
                session.add(record)
            else:
                _assert_same_context(record, context)
                _assert_current_attempt(record, context)
                if record.status != "RUNNING":
                    raise RuntimeError("turn execution is not RUNNING")
            _update_pending_entries(session, context, "COMMITTED")
            next_transcript_seq = _allocate_transcript_seqs(session, context, len(entries))
            for offset, entry in enumerate(entries):
                session.add(_transcript_entry_record(context, entry, next_transcript_seq + offset, "COMMITTED"))
            record.status = "SUCCEEDED"
            record.final_outcome_json = outcome_json
            record.failure_reason = None
            record.completed_at = completed_at
            session.commit()

    def mark_failed(self, context: TurnExecutionContext, reason: str) -> None:
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
            _update_pending_entries(session, context, "ABORTED")
            session.commit()

    def check_database(self) -> dict[str, object]:
        with self.engine.connect() as connection:
            result = connection.execute(text("select 1")).scalar_one()
            if result != 1:
                raise RuntimeError("postgres readiness probe returned unexpected result")
            connection.execute(text("select count(*) from agent_runtime.turn_execution")).scalar_one()
            connection.execute(text("select count(*) from agent_runtime.transcript_entry")).scalar_one()
            connection.execute(text("select count(*) from agent_runtime.owner_context_sequence")).scalar_one()
        return {"backend": "postgresql", "schema": SCHEMA_NAME}

    def close(self) -> None:
        self.engine.dispose()


def create_transcript_store() -> PostgresTranscriptStore:
    return PostgresTranscriptStore()


def ensure_postgres_configuration(database_url: str) -> None:
    try:
        url = make_url(database_url)
    except Exception as exc:  # pragma: no cover
        raise RuntimeError(f"invalid agent-runtime database url: {exc}") from exc
    if url.get_backend_name() != "postgresql":
        raise RuntimeError("agent-runtime transcript store requires PostgreSQL")


def transcript_entries_from_provider_messages(
    messages: list[dict[str, Any]],
    *,
    model_round_id: str,
) -> list[TranscriptEntry]:
    return [
        TranscriptEntry(
            role=str(message.get("role") or ""),
            content_json=normalize_provider_message(message),
            model_round_id=model_round_id,
            seq=index,
        )
        for index, message in enumerate(messages, start=1)
    ]


def transcript_entry_from_stream_message(
    message: OpenAiCompatibleStreamMessage,
    *,
    model_round_id: str,
    seq: int,
) -> TranscriptEntry:
    blocks: list[RuntimeContentBlock] = []
    if message.content:
        blocks.append(RuntimeContentBlock(type="text", text=message.content))
    if message.thinking:
        blocks.append(RuntimeContentBlock(type="thinking", text=message.thinking))
    for tool_call in message.tool_calls:
        blocks.append(
            RuntimeContentBlock(
                type="tool_call",
                id=tool_call.call_id,
                name=tool_call.tool_name,
                arguments=dict(tool_call.arguments),
            )
        )
    return TranscriptEntry(
        role="assistant",
        content_json=runtime_content_json(blocks),
        model_round_id=model_round_id,
        seq=seq,
    )


def transcript_entry_from_tool_result_message(
    message: dict[str, Any],
    *,
    model_round_id: str,
    seq: int,
) -> TranscriptEntry:
    return TranscriptEntry(
        role="tool",
        content_json=normalize_provider_message(message),
        model_round_id=model_round_id,
        seq=seq,
    )


def normalize_provider_message(message: dict[str, Any]) -> dict[str, Any]:
    role = str(message.get("role") or "")
    blocks: list[RuntimeContentBlock] = []
    content = message.get("content")
    if role == "tool":
        blocks.append(
            RuntimeContentBlock(
                type="tool_result",
                tool_call_id=str(message.get("tool_call_id") or ""),
                content=content if content is not None else "",
            )
        )
        return runtime_content_json(blocks)
    if content is not None and str(content):
        blocks.append(RuntimeContentBlock(type="text", text=str(content)))
    thinking = message.get("reasoning_content") or message.get("thinking")
    if thinking is not None and str(thinking):
        blocks.append(RuntimeContentBlock(type="thinking", text=str(thinking)))
    for tool_call in message.get("tool_calls") or []:
        function_call = tool_call.get("function") if isinstance(tool_call, dict) else None
        if not isinstance(function_call, dict):
            continue
        blocks.append(
            RuntimeContentBlock(
                type="tool_call",
                id=str(tool_call.get("id") or ""),
                name=str(function_call.get("name") or ""),
                arguments=_parse_provider_tool_arguments(function_call.get("arguments")),
            )
        )
    return runtime_content_json(blocks)


def runtime_content_json(blocks: list[RuntimeContentBlock]) -> dict[str, Any]:
    return {"version": 1, "blocks": [block.to_json() for block in blocks]}


def transcript_entries_to_provider_messages(entries: list[CommittedTranscriptEntry]) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for entry in sorted(entries, key=lambda item: item.transcript_seq):
        message = provider_message_from_transcript_entry(entry.role, entry.content_json)
        if message is not None:
            messages.append(message)
    return messages


def provider_message_from_transcript_entry(role: str, content_json: dict[str, Any]) -> dict[str, Any] | None:
    blocks = content_json.get("blocks")
    if not isinstance(blocks, list):
        return None
    if role == "tool":
        for block in blocks:
            if not isinstance(block, dict) or block.get("type") != "tool_result":
                continue
            return {
                "role": "tool",
                "tool_call_id": str(block.get("tool_call_id") or ""),
                "content": _provider_tool_result_content(block.get("content")),
            }
        return None
    text = "".join(str(block.get("text") or "") for block in blocks if isinstance(block, dict) and block.get("type") == "text")
    message: dict[str, Any] = {"role": role, "content": text}
    thinking = "".join(
        str(block.get("text") or "") for block in blocks if isinstance(block, dict) and block.get("type") == "thinking"
    )
    if thinking:
        message["reasoning_content"] = thinking
    tool_calls = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_call":
            continue
        tool_calls.append(
            {
                "id": str(block.get("id") or ""),
                "type": "function",
                "function": {
                    "name": str(block.get("name") or ""),
                    "arguments": block.get("arguments")
                    if isinstance(block.get("arguments"), str)
                    else json.dumps(block.get("arguments") or {}, ensure_ascii=False),
                },
            }
        )
    if tool_calls:
        message["tool_calls"] = tool_calls
    return message


def _parse_provider_tool_arguments(raw_arguments: Any) -> dict[str, Any]:
    if isinstance(raw_arguments, dict):
        return dict(raw_arguments)
    if not isinstance(raw_arguments, str):
        return {}
    text_value = raw_arguments.strip()
    if not text_value:
        return {}
    parsed = json.loads(text_value)
    if not isinstance(parsed, dict):
        raise ValueError("provider tool call arguments must decode to an object")
    return parsed


def _provider_tool_result_content(content: Any) -> str:
    if isinstance(content, str):
        return content
    return json.dumps(content if content is not None else "", ensure_ascii=False)


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
        content_json=entry.content_json,
        status=status,
    )


def _update_pending_entries(session: Session, context: TurnExecutionContext, status: str) -> None:
    entries = session.scalars(
        select(TranscriptEntryRecord).where(
            TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
            TranscriptEntryRecord.execution_attempt_id == context.execution_attempt_id,
            TranscriptEntryRecord.status == "PENDING",
        )
    ).all()
    for entry in entries:
        entry.status = status


def _abort_all_pending_entries(session: Session, context: TurnExecutionContext) -> None:
    entries = session.scalars(
        select(TranscriptEntryRecord).where(
            TranscriptEntryRecord.turn_execution_id == context.turn_execution_id,
            TranscriptEntryRecord.status == "PENDING",
        )
    ).all()
    for entry in entries:
        entry.status = "ABORTED"


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
