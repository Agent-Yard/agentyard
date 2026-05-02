import json
import os
import unittest
from contextlib import contextmanager
from unittest.mock import patch

from fastapi.testclient import TestClient

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.main import app
from lynxus_agent_runtime.models import AgentDecision, AgentTurnExecutionOutcome, AgentTurnResult, SessionMessageInput
from lynxus_agent_runtime.openai_compatible import OpenAiCompatibleStreamEvent, OpenAiCompatibleStreamMalformedError
from lynxus_agent_runtime.transcript_store import (
    CommittedTranscriptEntry,
    TranscriptEntry,
    provider_message_from_transcript_entry,
)

from test_decisioning_loop import _request_payload


class FakeRedisClient:
    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


class FakeTranscriptStore:
    settings = type("Settings", (), {"database_url": "postgresql+psycopg://test"})()

    def __init__(
        self,
        *,
        cached_outcome: AgentTurnExecutionOutcome | None = None,
        committed_entries_by_context: dict[tuple[str, str, int], list[CommittedTranscriptEntry]] | None = None,
    ) -> None:
        self.cached_outcome = cached_outcome
        self.committed_entries_by_context = committed_entries_by_context or {}
        self.begin_contexts: list[object] = []
        self.load_contexts: list[object] = []
        self.pending_entries: list[tuple[object, list[TranscriptEntry]]] = []
        self.committed_successes: list[tuple[object, AgentTurnExecutionOutcome, list[TranscriptEntry]]] = []
        self.failed: list[tuple[object, str]] = []

    def initialize(self) -> None:
        return None

    def begin_execution(self, context):  # noqa: ANN001
        self.begin_contexts.append(context)
        return self.cached_outcome

    def load_committed_provider_messages(self, context):  # noqa: ANN001
        self.load_contexts.append(context)
        entries = self.committed_entries_by_context.get(
            (context.session_id, context.owner_agent_id, context.ownership_epoch),
            [],
        )
        return [provider_message_from_transcript_entry(entry.role, entry.content_json) for entry in entries]

    def append_pending_entries(self, context, entries):  # noqa: ANN001
        self.pending_entries.append((context, list(entries)))

    def commit_success(self, context, outcome, entries):  # noqa: ANN001
        self.committed_successes.append((context, outcome, list(entries)))

    def mark_failed(self, context, reason):  # noqa: ANN001
        self.failed.append((context, reason))

    def check_database(self) -> dict[str, object]:
        return {"backend": "postgresql", "schema": "agent_runtime"}

    def close(self) -> None:
        return None


@contextmanager
def agent_runtime_client(transcript_store: FakeTranscriptStore | None = None):
    store = transcript_store or FakeTranscriptStore()
    with patch("lynxus_agent_runtime.main.create_redis_client", return_value=FakeRedisClient()):
        with patch("lynxus_agent_runtime.main.create_transcript_store", return_value=store):
            with TestClient(app) as client:
                yield client


class AgentTurnStreamingTest(unittest.TestCase):
    def test_should_fail_execute_stream_non_streaming_fallback_when_transcript_store_is_enabled(self) -> None:
        os.environ.pop("TEST_OPENAI_COMPATIBLE_API_KEY", None)
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        request["ownershipEpoch"] = 3
        transcript_store = FakeTranscriptStore()

        with patch("lynxus_agent_runtime.streaming.execute_agent_turn") as fallback_execute:
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        fallback_execute.assert_not_called()
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/x-ndjson")
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertGreaterEqual(len(frames), 3)
        for index, frame in enumerate(frames, start=1):
            self.assertEqual(frame["seq"], index)
            self.assertEqual(frame["frameId"], f"exec-1:{index}")
            self.assertEqual(frame["turnId"], "turn-1")
            self.assertEqual(frame["turnExecutionId"], "exec-1")
            self.assertEqual(frame["ownershipEpoch"], 3)

        final_frames = [frame for frame in frames if frame["kind"] == "FINAL_OUTCOME"]
        self.assertEqual(1, len(final_frames))
        self.assertFalse(final_frames[0]["payload"]["outcome"]["success"])
        self.assertIn("non-streaming fallback cannot replay", final_frames[0]["payload"]["outcome"]["failureReason"])
        self.assertEqual("TRANSCRIPT_REPLAY_UNSUPPORTED_ON_FALLBACK", frames[-2]["payload"]["code"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"])
        self.assertEqual(1, len(transcript_store.failed))

    def test_should_stream_provider_text_delta_before_final_outcome(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        transcript_store = FakeTranscriptStore()

        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="已查到"),
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="订单。"),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                OpenAiCompatibleStreamEvent(
                    event_type="usage",
                    usage={"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10},
                ),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        delta_frames = [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"]
        self.assertEqual(["已查到", "订单。"], [frame["payload"]["delta"] for frame in delta_frames])
        final_frame = frames[-1]
        self.assertEqual("FINAL_OUTCOME", final_frame["kind"])
        outcome = final_frame["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        final_text = outcome["result"]["decision"]["replyMessage"]["blocks"][0]["text"]
        self.assertEqual("".join(frame["payload"]["delta"] for frame in delta_frames), final_text)
        self.assertEqual(1, len([frame for frame in frames if frame["kind"] == "FINAL_OUTCOME"]))
        self.assertEqual(1, len(transcript_store.committed_successes))
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual("assistant", committed_entries[-1].role)
        self.assertEqual("已查到订单。", committed_entries[-1].content_json["blocks"][0]["text"])

    def test_should_return_cached_successful_final_outcome_without_calling_provider(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        reply = SessionMessageInput.model_validate({"blocks": [{"type": "TEXT", "text": "cached"}], "metadata": {}})
        cached_outcome = AgentTurnExecutionOutcome(
            success=True,
            result=AgentTurnResult(
                decision=AgentDecision(action="REPLY", replyMessage=reply),
                sharedState={"from": "cache"},
            ),
        )
        transcript_store = FakeTranscriptStore(cached_outcome=cached_outcome)

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events") as provider:
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        provider.assert_not_called()
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual(["FINAL_OUTCOME"], [frame["kind"] for frame in frames])
        outcome = frames[0]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        self.assertEqual("cached", outcome["result"]["decision"]["replyMessage"]["blocks"][0]["text"])
        self.assertEqual([], transcript_store.committed_successes)

    def test_should_not_call_provider_when_turn_execution_is_already_running(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        running_conflict = AgentTurnExecutionOutcome(
            success=False,
            failureReason="turn execution is already RUNNING for this turnExecutionId; retry later",
        )
        transcript_store = FakeTranscriptStore(cached_outcome=running_conflict)

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events") as provider:
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        provider.assert_not_called()
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual(["FINAL_OUTCOME"], [frame["kind"] for frame in frames])
        self.assertFalse(frames[0]["payload"]["outcome"]["success"])
        self.assertIn("already RUNNING", frames[0]["payload"]["outcome"]["failureReason"])
        self.assertEqual([], transcript_store.pending_entries)

    def test_should_not_reuse_failed_execution_as_success_cache(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        transcript_store = FakeTranscriptStore()
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="fresh"),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(1, len(captured_payloads))
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual("fresh", frames[-1]["payload"]["outcome"]["result"]["decision"]["replyMessage"]["blocks"][0]["text"])

    def test_should_fail_privacy_mapping_execute_stream_fallback_without_calling_decisioning(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-privacy"
        request["turnExecutionId"] = "exec-privacy"
        request["effectivePrivacyMappingEnabled"] = True
        transcript_store = FakeTranscriptStore()

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.execute_agent_turn") as fallback_execute:
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        fallback_execute.assert_not_called()
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual("ERROR", frames[-2]["kind"])
        self.assertEqual("TRANSCRIPT_REPLAY_UNSUPPORTED_ON_FALLBACK", frames[-2]["payload"]["code"])
        self.assertFalse(frames[-1]["payload"]["outcome"]["success"])

    def test_should_include_committed_same_owner_epoch_transcript_in_provider_payload(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-2"
        request["turnExecutionId"] = "exec-2"
        transcript_store = FakeTranscriptStore(
            committed_entries_by_context={
                ("session-1", "agent-a", 1): [
                    CommittedTranscriptEntry(
                        transcript_seq=7,
                        role="assistant",
                        content_json={"version": 1, "blocks": [{"type": "text", "text": "previous answer"}]},
                    )
                ]
            }
        )
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="next answer"),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        self.assertIn(
            {"role": "assistant", "content": "previous answer"},
            captured_payloads[0]["messages"],
        )

    def test_should_not_include_committed_transcript_from_different_owner_or_epoch(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-2"
        request["turnExecutionId"] = "exec-2"
        transcript_store = FakeTranscriptStore(
            committed_entries_by_context={
                ("session-1", "agent-b", 1): [
                    CommittedTranscriptEntry(
                        transcript_seq=1,
                        role="assistant",
                        content_json={"version": 1, "blocks": [{"type": "text", "text": "wrong owner"}]},
                    )
                ],
                ("session-1", "agent-a", 2): [
                    CommittedTranscriptEntry(
                        transcript_seq=2,
                        role="assistant",
                        content_json={"version": 1, "blocks": [{"type": "text", "text": "wrong epoch"}]},
                    )
                ],
            }
        )
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="current"),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        rendered = "\n".join(str(message.get("content") or "") for message in captured_payloads[0]["messages"])
        self.assertNotIn("wrong owner", rendered)
        self.assertNotIn("wrong epoch", rendered)

    def test_should_not_stream_or_persist_legacy_json_decision_as_reply(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"

        legacy_json = json.dumps(
            {
                "decision": {
                    "action": "REPLY",
                    "replyMessage": {"blocks": [{"type": "TEXT", "text": "已查到订单。"}], "metadata": {}},
                },
                "sharedState": {},
                "securityAssessment": {"action": "ALLOW", "categories": []},
            },
            ensure_ascii=False,
        )
        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta=legacy_json[:20]),
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta=legacy_json[20:]),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client() as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_COMPLETED"])
        self.assertEqual("FINAL_OUTCOME", frames[-1]["kind"])
        outcome = frames[-1]["payload"]["outcome"]
        self.assertFalse(outcome["success"])
        self.assertIsNone(outcome["result"])
        self.assertIn("legacy JSON", outcome["failureReason"])

    def test_should_build_streaming_provider_prompt_without_legacy_response_contract(self) -> None:
        request = _request_payload()
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="已查到订单。"),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
            with agent_runtime_client() as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        rendered_prompt = "\n\n".join(str(message.get("content") or "") for message in captured_payloads[0]["messages"])
        self.assertNotIn("Response contract", rendered_prompt)
        self.assertNotIn("Final output must be JSON only", rendered_prompt)
        self.assertNotIn("decision.replyMessage", rendered_prompt)
        self.assertIn("write user-visible assistant text as normal assistant content", rendered_prompt)

    def test_should_not_create_successful_final_message_for_malformed_provider_stream(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch(
            "lynxus_agent_runtime.streaming.stream_chat_completion_events",
            side_effect=OpenAiCompatibleStreamMalformedError("malformed openai-compatible stream JSON"),
        ):
            with agent_runtime_client() as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"])
        self.assertEqual("ERROR", frames[-2]["kind"])
        self.assertEqual("PROVIDER_STREAM_MALFORMED", frames[-2]["payload"]["code"])
        self.assertEqual("FINAL_OUTCOME", frames[-1]["kind"])
        outcome = frames[-1]["payload"]["outcome"]
        self.assertFalse(outcome["success"])
        self.assertIsNone(outcome["result"])

    def test_should_emit_internal_tool_argument_delta_without_successful_durable_message(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"

        events = iter(
            [
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-1",
                    tool_name="create_ticket",
                    arguments_delta='{"subject":"refund"}',
                ),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client() as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        tool_delta_frames = [frame for frame in frames if frame["kind"] == "ACTION_TOOL_ARGUMENT_DELTA"]
        self.assertEqual(1, len(tool_delta_frames))
        self.assertEqual("INTERNAL", tool_delta_frames[0]["visibility"])
        self.assertEqual('{"subject":"refund"}', tool_delta_frames[0]["payload"]["argumentsDelta"])
        outcome = frames[-1]["payload"]["outcome"]
        self.assertFalse(outcome["success"])
        self.assertIsNone(outcome["result"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_COMPLETED"])

    def test_should_protect_stream_endpoint_with_internal_auth(self) -> None:
        with agent_runtime_client() as client:
            response = client.post("/agent-turns/execute-stream", json={})

        self.assertEqual(response.status_code, 401)
