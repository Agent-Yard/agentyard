import os
import unittest
from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from lynxus_agent_runtime.models import AgentDecision, AgentTurnExecutionOutcome, AgentTurnResult
from lynxus_agent_runtime.transcript_store import (
    Base,
    DEFAULT_SCHEMA_NAME,
    OwnerContextSequenceRecord,
    PostgresTranscriptStore,
    TranscriptCachePayload,
    TranscriptBootstrapResetRecord,
    TranscriptEntry,
    TranscriptEntryRecord,
    TranscriptStoreSettings,
    TurnExecutionContext,
    TurnExecutionRecord,
    _allocate_transcript_seqs,
    _transcript_entry_record,
    transcript_cache_key,
    transcript_entries_from_provider_messages,
    transcript_entry_from_provider_message,
)


class TranscriptStoreSerializationTest(unittest.TestCase):
    def test_settings_should_default_to_agent_runtime_database(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = TranscriptStoreSettings.from_env()

        self.assertEqual(
            "postgresql+psycopg://lynxus:lynxus@127.0.0.1:5432/lynxus_agent_runtime",
            settings.database_url,
        )

    def test_tables_should_use_default_public_schema_without_explicit_qualification(self) -> None:
        self.assertEqual("public", DEFAULT_SCHEMA_NAME)
        self.assertIsNone(Base.metadata.schema)
        self.assertIsNone(TurnExecutionRecord.__table__.schema)
        self.assertIsNone(TranscriptEntryRecord.__table__.schema)
        self.assertIsNone(OwnerContextSequenceRecord.__table__.schema)

    def test_should_store_provider_message_without_normalizing_content(self) -> None:
        provider_message = {
            "role": "assistant",
            "content": "visible answer",
            "reasoning_content": "private chain",
            "tool_calls": [
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "create_ticket", "arguments": '{"subject": "refund"}'},
                }
            ],
        }

        entry = transcript_entry_from_provider_message(
            provider_message,
            provider_type="OPENAI_COMPATIBLE",
            model_round_id="round-1",
            seq=3,
        )

        self.assertEqual("assistant", entry.role)
        self.assertEqual("OPENAI_COMPATIBLE", entry.provider_type)
        self.assertEqual("round-1", entry.model_round_id)
        self.assertEqual(3, entry.seq)
        self.assertEqual(provider_message, entry.content_json)

    def test_should_store_anthropic_provider_message_without_converting_blocks(self) -> None:
        provider_message = {
            "role": "assistant",
            "content": [
                {"type": "text", "text": "visible"},
                {"type": "thinking", "thinking": "hidden"},
                {"type": "tool_use", "id": "call-1", "name": "create_ticket", "input": {"subject": "refund"}},
            ],
        }

        entries = transcript_entries_from_provider_messages(
            [provider_message],
            provider_type="ANTHROPIC",
            model_round_id="round-1",
        )

        self.assertEqual("ANTHROPIC", entries[0].provider_type)
        self.assertEqual(provider_message, entries[0].content_json)

    def test_should_preserve_provider_message_metadata_when_entry_builder_receives_it(self) -> None:
        provider_message = {
            "role": "assistant",
            "content": "visible",
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 11, "output_tokens": 7},
            "model": "provider-model",
        }

        entries = transcript_entries_from_provider_messages(
            [provider_message],
            provider_type="ANTHROPIC",
            model_round_id="round-1",
        )

        self.assertEqual(provider_message, entries[0].content_json)

    def test_should_allocate_transcript_seq_from_owner_context_sequence_row(self) -> None:
        SessionLocal = _sqlite_agent_runtime_session_factory()
        context = _turn_context()

        with SessionLocal() as session:
            self.assertEqual(1, _allocate_transcript_seqs(session, context, 2))
            self.assertEqual(3, _allocate_transcript_seqs(session, context, 1))
            self.assertEqual(4, _allocate_transcript_seqs(session, context, 3))
            session.commit()

    def test_should_define_unique_constraint_for_owner_context_transcript_seq(self) -> None:
        unique_constraints = [
            constraint
            for constraint in TranscriptEntryRecord.__table__.constraints
            if getattr(constraint, "name", "") == "uq_transcript_context_seq"
        ]

        self.assertEqual(1, len(unique_constraints))
        self.assertEqual(
            ["session_id", "owner_agent_id", "ownership_epoch", "transcript_seq"],
            [column.name for column in unique_constraints[0].columns],
        )

    def test_should_reject_duplicate_transcript_seq_for_same_owner_context(self) -> None:
        SessionLocal = _sqlite_agent_runtime_session_factory()
        context = _turn_context()
        entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "duplicate"},
            model_round_id="round-1",
            seq=1,
        )

        with SessionLocal() as session:
            transcript_seq = _allocate_transcript_seqs(session, context, 1)
            session.add(_transcript_entry_record(context, entry, transcript_seq, "COMMITTED"))
            session.add(_transcript_entry_record(context, entry, transcript_seq, "COMMITTED"))
            with self.assertRaises(IntegrityError):
                session.commit()

    def test_should_load_committed_provider_messages_from_transcript_cache_hit(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache(
            hit=TranscriptCachePayload(
                session_id=context.session_id,
                owner_agent_id=context.owner_agent_id,
                ownership_epoch=context.ownership_epoch,
                provider_type="OPENAI_COMPATIBLE",
                last_committed_seq=9,
                messages=[{"role": "assistant", "content": "cached"}],
            )
        )
        store = _sqlite_transcript_store(transcript_cache=cache)

        messages = store.load_committed_provider_messages(context)

        self.assertEqual([{"role": "assistant", "content": "cached"}], messages)
        self.assertEqual([(transcript_cache_key(context), "OPENAI_COMPATIBLE")], cache.gets)
        self.assertEqual([], cache.puts)

    def test_should_write_transcript_cache_on_miss_after_sorting_committed_entries(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache()
        store = _sqlite_transcript_store(transcript_cache=cache)
        first_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "first"},
            model_round_id="round-1",
            seq=1,
        )
        second_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "second"},
            model_round_id="round-2",
            seq=1,
        )
        pending_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "pending"},
            model_round_id="round-3",
            seq=1,
        )
        with store.session_factory() as session:
            session.add(_transcript_entry_record(context, second_entry, 20, "COMMITTED"))
            session.add(_transcript_entry_record(context, first_entry, 10, "COMMITTED"))
            session.add(_transcript_entry_record(context, pending_entry, 30, "PENDING"))
            session.commit()

        messages = store.load_committed_provider_messages(context)

        self.assertEqual(["first", "second"], [message["content"] for message in messages])
        self.assertEqual(1, len(cache.puts))
        put_key, put_payload = cache.puts[0]
        self.assertEqual("agent-runtime:transcript:session-1:agent-a:1", put_key)
        self.assertEqual(20, put_payload.last_committed_seq)
        self.assertEqual(["first", "second"], [message["content"] for message in put_payload.messages])

    def test_should_delete_transcript_cache_after_successful_commit(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache()
        store = _sqlite_transcript_store(transcript_cache=cache)
        entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "committed"},
            model_round_id="round-1",
            seq=1,
        )

        store.begin_execution(context)
        store.commit_success(
            context,
            AgentTurnExecutionOutcome(
                success=True,
                result=AgentTurnResult(decision=AgentDecision(action="NO_OP")),
            ),
            [entry],
        )

        self.assertEqual([transcript_cache_key(context)], cache.deletes)

    def test_should_not_write_transcript_cache_when_pending_entries_are_aborted(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache()
        store = _sqlite_transcript_store(transcript_cache=cache)
        entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "pending"},
            model_round_id="round-1",
            seq=1,
        )

        store.begin_execution(context)
        store.append_pending_entries(context, [entry])
        store.mark_failed(context, "provider failed")

        self.assertEqual([], cache.puts)
        self.assertEqual([], cache.deletes)

    def test_bootstrap_reset_should_delete_committed_provider_entries_once_and_record_marker(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache()
        store = _sqlite_transcript_store(transcript_cache=cache)
        openai_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "old-openai"},
            model_round_id="round-1",
            seq=1,
        )
        anthropic_entry = TranscriptEntry(
            role="assistant",
            provider_type="ANTHROPIC",
            content_json={"role": "assistant", "content": "old-anthropic"},
            model_round_id="round-1",
            seq=1,
        )

        store.begin_execution(context)
        with store.session_factory() as session:
            session.add(_transcript_entry_record(context, openai_entry, 1, "COMMITTED"))
            session.add(_transcript_entry_record(context, anthropic_entry, 2, "COMMITTED"))
            session.commit()

        store.reset_committed_provider_transcript_for_bootstrap(context, "OPENAI_COMPATIBLE")

        with store.session_factory() as session:
            entries = session.query(TranscriptEntryRecord).order_by(TranscriptEntryRecord.transcript_seq.asc()).all()
            marker = session.get(
                TranscriptBootstrapResetRecord,
                {"turn_execution_id": context.turn_execution_id, "provider_type": "OPENAI_COMPATIBLE"},
            )

        self.assertEqual(["ANTHROPIC"], [entry.provider_type for entry in entries])
        self.assertIsNotNone(marker)
        self.assertEqual("COMPLETED", marker.status)
        self.assertEqual(context.session_id, marker.session_id)
        self.assertEqual(context.owner_agent_id, marker.owner_agent_id)
        self.assertEqual(context.ownership_epoch, marker.ownership_epoch)
        self.assertEqual(context.execution_attempt_id, marker.execution_attempt_id)
        self.assertEqual(1, marker.deleted_committed_entry_count)
        self.assertIsNotNone(marker.reset_completed_at)
        self.assertEqual([transcript_cache_key(context)], cache.deletes)

    def test_bootstrap_reset_retry_should_not_delete_rebuilt_committed_entries(self) -> None:
        context = _turn_context()
        cache = _FakeTranscriptCache()
        store = _sqlite_transcript_store(transcript_cache=cache)
        old_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "old"},
            model_round_id="round-1",
            seq=1,
        )
        rebuilt_entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "rebuilt"},
            model_round_id="round-2",
            seq=1,
        )

        store.begin_execution(context)
        with store.session_factory() as session:
            session.add(_transcript_entry_record(context, old_entry, 1, "COMMITTED"))
            session.commit()
        store.reset_committed_provider_transcript_for_bootstrap(context, "OPENAI_COMPATIBLE")
        with store.session_factory() as session:
            session.add(_transcript_entry_record(context, rebuilt_entry, 2, "COMMITTED"))
            session.commit()

        store.reset_committed_provider_transcript_for_bootstrap(context, "OPENAI_COMPATIBLE")

        with store.session_factory() as session:
            entries = session.query(TranscriptEntryRecord).all()
            marker = session.get(
                TranscriptBootstrapResetRecord,
                {"turn_execution_id": context.turn_execution_id, "provider_type": "OPENAI_COMPATIBLE"},
            )

        self.assertEqual(["rebuilt"], [entry.content_json["content"] for entry in entries])
        self.assertEqual(1, marker.deleted_committed_entry_count)
        self.assertEqual([transcript_cache_key(context), transcript_cache_key(context)], cache.deletes)

    def test_bootstrap_reset_should_be_forbidden_after_successful_execution(self) -> None:
        context = _turn_context()
        store = _sqlite_transcript_store()
        store.begin_execution(context)
        store.commit_success(
            context,
            AgentTurnExecutionOutcome(
                success=True,
                result=AgentTurnResult(decision=AgentDecision(action="NO_OP")),
            ),
            [],
        )

        with self.assertRaisesRegex(RuntimeError, "successful turn execution cannot reset"):
            store.reset_committed_provider_transcript_for_bootstrap(context, "OPENAI_COMPATIBLE")

    def test_mark_failed_should_preserve_completed_bootstrap_reset_marker(self) -> None:
        context = _turn_context()
        store = _sqlite_transcript_store()
        store.begin_execution(context)
        store.reset_committed_provider_transcript_for_bootstrap(context, "OPENAI_COMPATIBLE")

        store.mark_failed(context, "provider failed")

        with store.session_factory() as session:
            marker = session.get(
                TranscriptBootstrapResetRecord,
                {"turn_execution_id": context.turn_execution_id, "provider_type": "OPENAI_COMPATIBLE"},
            )
            turn = session.get(TurnExecutionRecord, context.turn_execution_id)

        self.assertIsNotNone(marker)
        self.assertEqual("COMPLETED", marker.status)
        self.assertEqual("FAILED", turn.status)

    def test_should_set_retention_expiry_on_turn_execution_and_transcript_entries(self) -> None:
        store = _sqlite_transcript_store()
        context = _turn_context()
        entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "hello"},
            model_round_id="round-1",
            seq=1,
        )

        store.begin_execution(context)
        store.append_pending_entries(context, [entry])
        store.commit_success(
            context,
            AgentTurnExecutionOutcome(
                success=True,
                result=AgentTurnResult(decision=AgentDecision(action="NO_OP")),
            ),
            [],
        )

        with store.session_factory() as session:
            turn_execution = session.get(TurnExecutionRecord, context.turn_execution_id)
            transcript_entries = session.query(TranscriptEntryRecord).all()

        self.assertIsNotNone(turn_execution)
        self.assertIsNotNone(turn_execution.expires_at)
        self.assertTrue(transcript_entries)
        self.assertTrue(all(entry_record.expires_at is not None for entry_record in transcript_entries))
        self.assertTrue(all(entry_record.status == "COMMITTED" for entry_record in transcript_entries))

    def test_should_sweep_expired_rows_with_bounded_abort_and_delete_semantics(self) -> None:
        store = _sqlite_transcript_store()
        now = datetime(2026, 5, 3, tzinfo=UTC)
        expired_at = now - timedelta(seconds=1)
        active_at = now + timedelta(seconds=60)
        running_context = _turn_context()
        completed_context = TurnExecutionContext(
            session_id="session-1",
            owner_agent_id="agent-a",
            ownership_epoch=1,
            turn_id="turn-2",
            turn_execution_id="exec-2",
            execution_attempt_id="attempt-2",
        )
        entry = TranscriptEntry(
            role="assistant",
            provider_type="OPENAI_COMPATIBLE",
            content_json={"role": "assistant", "content": "expired"},
            model_round_id="round-1",
            seq=1,
        )

        with store.session_factory() as session:
            session.add(
                TurnExecutionRecord(
                    turn_execution_id=running_context.turn_execution_id,
                    session_id=running_context.session_id,
                    owner_agent_id=running_context.owner_agent_id,
                    ownership_epoch=running_context.ownership_epoch,
                    turn_id=running_context.turn_id,
                    current_execution_attempt_id=running_context.execution_attempt_id,
                    status="RUNNING",
                    expires_at=expired_at,
                )
            )
            session.add(
                TurnExecutionRecord(
                    turn_execution_id=completed_context.turn_execution_id,
                    session_id=completed_context.session_id,
                    owner_agent_id=completed_context.owner_agent_id,
                    ownership_epoch=completed_context.ownership_epoch,
                    turn_id=completed_context.turn_id,
                    current_execution_attempt_id=completed_context.execution_attempt_id,
                    status="SUCCEEDED",
                    completed_at=expired_at,
                    expires_at=expired_at,
                )
            )
            session.add(_transcript_entry_record(running_context, entry, 1, "PENDING", expires_at=expired_at))
            session.add(_transcript_entry_record(running_context, entry, 2, "COMMITTED", expires_at=expired_at))
            session.add(_transcript_entry_record(running_context, entry, 3, "COMMITTED", expires_at=active_at))
            session.commit()

        result = store.sweep_expired(now=now, limit=10)

        with store.session_factory() as session:
            running_record = session.get(TurnExecutionRecord, running_context.turn_execution_id)
            completed_record = session.get(TurnExecutionRecord, completed_context.turn_execution_id)
            entries = {
                entry_record.transcript_seq: entry_record
                for entry_record in session.query(TranscriptEntryRecord).order_by(TranscriptEntryRecord.transcript_seq.asc()).all()
            }

        self.assertEqual(
            {
                "turnExecutionsAborted": 1,
                "turnExecutionsDeleted": 1,
                "transcriptEntriesAborted": 1,
                "transcriptEntriesDeleted": 1,
            },
            result,
        )
        self.assertIsNotNone(running_record)
        self.assertEqual("ABORTED", running_record.status)
        self.assertIsNone(completed_record)
        self.assertEqual("ABORTED", entries[1].status)
        self.assertNotIn(2, entries)
        self.assertEqual("COMMITTED", entries[3].status)

    def test_database_check_should_fail_when_owner_context_sequence_table_is_missing(self) -> None:
        engine = _sqlite_agent_runtime_engine()
        with engine.begin() as connection:
            Base.metadata.create_all(bind=connection)
            connection.exec_driver_sql("drop table owner_context_sequence")
        store = PostgresTranscriptStore.__new__(PostgresTranscriptStore)
        store.engine = engine

        with self.assertRaises(OperationalError) as error:
            store.check_database()

        self.assertIn("owner_context_sequence", str(error.exception))


def _turn_context() -> TurnExecutionContext:
    return TurnExecutionContext(
        session_id="session-1",
        owner_agent_id="agent-a",
        ownership_epoch=1,
        turn_id="turn-1",
        turn_execution_id="exec-1",
        execution_attempt_id="attempt-1",
    )


def _sqlite_agent_runtime_session_factory():  # noqa: ANN201
    engine = _sqlite_agent_runtime_engine()
    with engine.begin() as connection:
        Base.metadata.create_all(bind=connection)
    return sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


class _FakeTranscriptCache:
    def __init__(self, hit: TranscriptCachePayload | None = None) -> None:
        self.hit = hit
        self.gets: list[tuple[str, str]] = []
        self.puts: list[tuple[str, TranscriptCachePayload]] = []
        self.deletes: list[str] = []
        self.closed = False

    def get(self, context: TurnExecutionContext, provider_type: str) -> TranscriptCachePayload | None:
        self.gets.append((transcript_cache_key(context), provider_type))
        return self.hit

    def put(self, context: TurnExecutionContext, payload: TranscriptCachePayload) -> None:
        self.puts.append((transcript_cache_key(context), payload))

    def delete(self, context: TurnExecutionContext) -> None:
        self.deletes.append(transcript_cache_key(context))

    def close(self) -> None:
        self.closed = True


def _sqlite_transcript_store(
    settings: TranscriptStoreSettings | None = None,
    transcript_cache=None,  # noqa: ANN001
) -> PostgresTranscriptStore:
    engine = _sqlite_agent_runtime_engine()
    with engine.begin() as connection:
        Base.metadata.create_all(bind=connection)
    store = PostgresTranscriptStore.__new__(PostgresTranscriptStore)
    store.settings = settings or TranscriptStoreSettings(
        database_url="postgresql+psycopg://test:test@127.0.0.1:5432/test",
        turn_execution_retention_seconds=60,
        transcript_entry_retention_seconds=60,
        retention_sweep_limit=50,
        transcript_cache_ttl_seconds=60,
    )
    store.transcript_cache = transcript_cache
    store.engine = engine
    store.session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
    return store


def _sqlite_agent_runtime_engine():  # noqa: ANN201
    return create_engine(
        "sqlite+pysqlite://",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
