import unittest
from datetime import UTC, datetime, timedelta

from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from lynxus_agent_runtime.models import AgentDecision, AgentTurnExecutionOutcome, AgentTurnResult
from lynxus_agent_runtime.openai_compatible import OpenAiCompatibleStreamMessage, OpenAiCompatibleStreamToolCall
from lynxus_agent_runtime.transcript_store import (
    Base,
    CommittedTranscriptEntry,
    PostgresTranscriptStore,
    TranscriptCachePayload,
    TranscriptEntry,
    TranscriptEntryRecord,
    TranscriptStoreSettings,
    TurnExecutionContext,
    TurnExecutionRecord,
    _allocate_transcript_seqs,
    _transcript_entry_record,
    normalize_provider_message,
    transcript_cache_key,
    transcript_entries_from_provider_messages,
    transcript_entries_to_provider_messages,
    transcript_entry_from_stream_message,
)


class TranscriptStoreSerializationTest(unittest.TestCase):
    def test_should_serialize_assistant_text_thinking_and_tool_calls(self) -> None:
        entry = transcript_entry_from_stream_message(
            OpenAiCompatibleStreamMessage(
                content="visible answer",
                thinking="private chain",
                tool_calls=[
                    OpenAiCompatibleStreamToolCall(
                        index=0,
                        call_id="call-1",
                        tool_name="create_ticket",
                        arguments={"subject": "refund"},
                    )
                ],
                finish_reason="tool_calls",
                usage=None,
            ),
            model_round_id="round-1",
            seq=3,
        )

        self.assertEqual("assistant", entry.role)
        self.assertEqual("round-1", entry.model_round_id)
        self.assertEqual(3, entry.seq)
        self.assertEqual(
            [
                {"type": "text", "text": "visible answer"},
                {"type": "thinking", "text": "private chain"},
                {"type": "tool_call", "id": "call-1", "name": "create_ticket", "arguments": {"subject": "refund"}},
            ],
            entry.content_json["blocks"],
        )

    def test_should_replay_entries_by_transcript_seq_not_input_or_round_id_order(self) -> None:
        messages = transcript_entries_to_provider_messages(
            [
                CommittedTranscriptEntry(
                    transcript_seq=20,
                    role="assistant",
                    content_json={"version": 1, "blocks": [{"type": "text", "text": "second"}]},
                ),
                CommittedTranscriptEntry(
                    transcript_seq=10,
                    role="assistant",
                    content_json={"version": 1, "blocks": [{"type": "text", "text": "first"}]},
                ),
            ]
        )

        self.assertEqual(["first", "second"], [message["content"] for message in messages])

    def test_should_render_thinking_and_tool_call_fields_for_provider_replay(self) -> None:
        messages = transcript_entries_to_provider_messages(
            [
                CommittedTranscriptEntry(
                    transcript_seq=1,
                    role="assistant",
                    content_json={
                        "version": 1,
                        "blocks": [
                            {"type": "text", "text": "visible"},
                            {"type": "thinking", "text": "hidden"},
                            {
                                "type": "tool_call",
                                "id": "call-1",
                                "name": "create_ticket",
                                "arguments": {"subject": "refund"},
                            },
                        ],
                    },
                )
            ]
        )

        self.assertEqual(
            {
                "role": "assistant",
                "content": "visible",
                "reasoning_content": "hidden",
                "tool_calls": [
                    {
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "create_ticket", "arguments": '{"subject": "refund"}'},
                    }
                ],
            },
            messages[0],
        )

    def test_should_render_anthropic_like_replay_blocks_without_flattening_thinking(self) -> None:
        messages = transcript_entries_to_provider_messages(
            [
                CommittedTranscriptEntry(
                    transcript_seq=1,
                    role="assistant",
                    content_json={
                        "version": 1,
                        "blocks": [
                            {"type": "text", "text": "visible"},
                            {"type": "thinking", "text": "hidden"},
                            {
                                "type": "tool_call",
                                "id": "call-1",
                                "name": "create_ticket",
                                "arguments": {"subject": "refund"},
                            },
                        ],
                    },
                ),
                CommittedTranscriptEntry(
                    transcript_seq=2,
                    role="tool",
                    content_json={
                        "version": 1,
                        "blocks": [
                            {
                                "type": "tool_result",
                                "tool_call_id": "call-1",
                                "content": {"accepted": True},
                            }
                        ],
                    },
                ),
            ],
            provider_type="ANTHROPIC",
        )

        self.assertEqual(
            [
                {
                    "role": "assistant",
                    "content": [
                        {"type": "text", "text": "visible"},
                        {"type": "thinking", "thinking": "hidden"},
                        {
                            "type": "tool_use",
                            "id": "call-1",
                            "name": "create_ticket",
                            "input": {"subject": "refund"},
                        },
                    ],
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": "call-1",
                            "content": '{"accepted": true}',
                        }
                    ],
                },
            ],
            messages,
        )

    def test_should_normalize_anthropic_blocks_without_provider_metadata(self) -> None:
        content_json = normalize_provider_message(
            {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "visible"},
                    {"type": "thinking", "thinking": "hidden"},
                    {"type": "tool_use", "id": "call-1", "name": "create_ticket", "input": {"subject": "refund"}},
                ],
                "stop_reason": "tool_use",
                "usage": {"input_tokens": 11, "output_tokens": 7},
                "model": "claude-test",
            }
        )

        self.assertEqual(
            {
                "version": 1,
                "blocks": [
                    {"type": "text", "text": "visible"},
                    {"type": "thinking", "text": "hidden"},
                    {"type": "tool_call", "id": "call-1", "name": "create_ticket", "arguments": {"subject": "refund"}},
                ],
            },
            content_json,
        )
        self.assertNotIn("stop_reason", content_json)
        self.assertNotIn("usage", content_json)
        self.assertNotIn("model", content_json)

    def test_should_replay_anthropic_user_tool_result_after_normalization(self) -> None:
        entries = transcript_entries_from_provider_messages(
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": "call-1",
                            "content": [{"type": "text", "text": "accepted"}],
                        }
                    ],
                    "stop_reason": "tool_use",
                    "usage": {"input_tokens": 11, "output_tokens": 7},
                    "model": "claude-test",
                }
            ],
            model_round_id="round-1",
        )

        self.assertEqual("user", entries[0].role)
        self.assertEqual(
            {
                "version": 1,
                "blocks": [
                    {
                        "type": "tool_result",
                        "tool_call_id": "call-1",
                        "content": [{"type": "text", "text": "accepted"}],
                    }
                ],
            },
            entries[0].content_json,
        )
        self.assertNotIn("stop_reason", entries[0].content_json)
        self.assertNotIn("usage", entries[0].content_json)
        self.assertNotIn("model", entries[0].content_json)

        messages = transcript_entries_to_provider_messages(
            [
                CommittedTranscriptEntry(
                    transcript_seq=1,
                    role=entries[0].role,
                    content_json=entries[0].content_json,
                )
            ],
            provider_type="ANTHROPIC",
        )

        self.assertEqual(
            [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": "call-1",
                            "content": [{"type": "text", "text": "accepted"}],
                        }
                    ],
                }
            ],
            messages,
        )

    def test_should_normalize_tool_result_as_explicit_runtime_block(self) -> None:
        content_json = normalize_provider_message(
            {
                "role": "tool",
                "tool_call_id": "call-1",
                "content": '{"accepted": true}',
                "finish_reason": "stop",
                "usage": {"total_tokens": 12},
                "model": "provider-model",
            }
        )

        self.assertEqual(
            {
                "version": 1,
                "blocks": [
                    {
                        "type": "tool_result",
                        "tool_call_id": "call-1",
                        "content": '{"accepted": true}',
                    }
                ],
            },
            content_json,
        )
        self.assertNotIn("finish_reason", content_json)
        self.assertNotIn("usage", content_json)
        self.assertNotIn("model", content_json)

    def test_should_replay_tool_result_with_matching_provider_tool_call_id(self) -> None:
        messages = transcript_entries_to_provider_messages(
            [
                CommittedTranscriptEntry(
                    transcript_seq=1,
                    role="tool",
                    content_json={
                        "version": 1,
                        "blocks": [
                            {
                                "type": "tool_result",
                                "tool_call_id": "call-1",
                                "content": {"accepted": True},
                            }
                        ],
                    },
                )
            ]
        )

        self.assertEqual(
            [{"role": "tool", "tool_call_id": "call-1", "content": '{"accepted": true}'}],
            messages,
        )

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
            if getattr(constraint, "name", "") == "uq_agent_runtime_transcript_context_seq"
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
            content_json={"version": 1, "blocks": [{"type": "text", "text": "duplicate"}]},
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
            content_json={"version": 1, "blocks": [{"type": "text", "text": "first"}]},
            model_round_id="round-1",
            seq=1,
        )
        second_entry = TranscriptEntry(
            role="assistant",
            content_json={"version": 1, "blocks": [{"type": "text", "text": "second"}]},
            model_round_id="round-2",
            seq=1,
        )
        pending_entry = TranscriptEntry(
            role="assistant",
            content_json={"version": 1, "blocks": [{"type": "text", "text": "pending"}]},
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
            content_json={"version": 1, "blocks": [{"type": "text", "text": "committed"}]},
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
            content_json={"version": 1, "blocks": [{"type": "text", "text": "pending"}]},
            model_round_id="round-1",
            seq=1,
        )

        store.begin_execution(context)
        store.append_pending_entries(context, [entry])
        store.mark_failed(context, "provider failed")

        self.assertEqual([], cache.puts)
        self.assertEqual([], cache.deletes)

    def test_should_set_retention_expiry_on_turn_execution_and_transcript_entries(self) -> None:
        store = _sqlite_transcript_store()
        context = _turn_context()
        entry = TranscriptEntry(
            role="assistant",
            content_json={"version": 1, "blocks": [{"type": "text", "text": "hello"}]},
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
            content_json={"version": 1, "blocks": [{"type": "text", "text": "expired"}]},
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
            connection.exec_driver_sql("drop table agent_runtime.owner_context_sequence")
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
    engine = create_engine(
        "sqlite+pysqlite://",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as connection:
        connection.exec_driver_sql("attach database ':memory:' as agent_runtime")
    return engine
