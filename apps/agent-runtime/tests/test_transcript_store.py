import unittest

from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from lynxus_agent_runtime.openai_compatible import OpenAiCompatibleStreamMessage, OpenAiCompatibleStreamToolCall
from lynxus_agent_runtime.transcript_store import (
    Base,
    CommittedTranscriptEntry,
    PostgresTranscriptStore,
    TranscriptEntry,
    TranscriptEntryRecord,
    TurnExecutionContext,
    _allocate_transcript_seqs,
    _transcript_entry_record,
    normalize_provider_message,
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
