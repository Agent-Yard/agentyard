import json
import os
import unittest
from contextlib import contextmanager
from unittest.mock import patch

from fastapi.testclient import TestClient

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.main import app
from lynxus_agent_runtime.models import (
    AgentDecision,
    AgentTurnExecutionOutcome,
    AgentTurnRequest,
    AgentTurnResult,
    SessionMessageInput,
)
from lynxus_agent_runtime.openai_compatible import OpenAiCompatibleStreamEvent, OpenAiCompatibleStreamMalformedError
from lynxus_agent_runtime.tooling import (
    RuntimeToolKind,
    semantic_tool_definitions,
    streaming_semantic_tool_definitions,
    tool_kind,
)
from lynxus_agent_runtime.transcript_store import (
    CommittedTranscriptEntry,
    TranscriptEntry,
)

from runtime_fixtures import request_payload


class FakeRedisClient:
    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


class FakeTranscriptStore:
    settings = type(
        "Settings",
        (),
        {
            "database_url": "postgresql+psycopg://test",
            "turn_execution_retention_seconds": 60,
            "transcript_entry_retention_seconds": 60,
            "retention_sweep_limit": 50,
            "transcript_cache_ttl_seconds": 60,
        },
    )()

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
        self.sweep_results: list[dict[str, int]] = []

    def initialize(self) -> None:
        return None

    def sweep_expired(self, *, now=None, limit=None) -> dict[str, int]:  # noqa: ANN001
        result = {
            "turnExecutionsAborted": 0,
            "turnExecutionsDeleted": 0,
            "transcriptEntriesAborted": 0,
            "transcriptEntriesDeleted": 0,
        }
        self.sweep_results.append(result)
        return result

    def begin_execution(self, context):  # noqa: ANN001
        self.begin_contexts.append(context)
        return self.cached_outcome

    def load_committed_provider_messages(self, context, provider_type: str = "OPENAI_COMPATIBLE"):  # noqa: ANN001
        self.load_contexts.append(context)
        entries = self.committed_entries_by_context.get(
            (context.session_id, context.owner_agent_id, context.ownership_epoch),
            [],
        )
        return [dict(entry.content_json) for entry in entries if entry.provider_type == provider_type]

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
    def test_should_build_streaming_runtime_tool_registry_with_stable_kinds(self) -> None:
        request = AgentTurnRequest.model_validate(request_payload())

        tool_names = [definition.name for definition in streaming_semantic_tool_definitions(request)]

        self.assertEqual("get_owner_capabilities", tool_names[0])
        self.assertEqual("knowledge_search", tool_names[1])
        self.assertEqual("knowledge_read", tool_names[2])
        self.assertIn("read_skill", tool_names)
        self.assertIn("append_text_block", tool_names)
        self.assertIn("update_shared_state", tool_names)
        self.assertIn("run_playbook", tool_names)
        self.assertIn("security_block", tool_names)
        self.assertIn("resource_tool__tool_ver_1__create_ticket", tool_names)
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, tool_kind(request, "knowledge_search"))
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, tool_kind(request, "read_skill"))
        self.assertEqual(RuntimeToolKind.MESSAGE_BLOCK_TOOL, tool_kind(request, "append_text_block"))
        self.assertEqual(RuntimeToolKind.STATE_TOOL, tool_kind(request, "update_shared_state"))
        self.assertEqual(RuntimeToolKind.LIFECYCLE_ACTION_TOOL, tool_kind(request, "run_playbook"))
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, tool_kind(request, "resource_tool__tool_ver_1__create_ticket"))

    def test_should_exclude_outcome_tools_from_non_streaming_semantic_definitions(self) -> None:
        request = AgentTurnRequest.model_validate(request_payload())

        tool_names = {definition.name for definition in semantic_tool_definitions(request)}

        self.assertIn("get_owner_capabilities", tool_names)
        self.assertIn("knowledge_search", tool_names)
        self.assertIn("read_skill", tool_names)
        self.assertIn("resource_tool__tool_ver_1__create_ticket", tool_names)
        self.assertNotIn("append_text_block", tool_names)
        self.assertNotIn("update_shared_state", tool_names)
        self.assertNotIn("run_playbook", tool_names)

    def test_should_exclude_knowledge_tools_without_effective_binding(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["knowledgeEnabled"] = False
        request = AgentTurnRequest.model_validate(payload)

        tool_names = {definition.name for definition in streaming_semantic_tool_definitions(request)}

        self.assertNotIn("knowledge_search", tool_names)
        self.assertNotIn("knowledge_read", tool_names)

    def test_should_fail_execute_stream_when_provider_streaming_is_unavailable(self) -> None:
        os.environ.pop("TEST_OPENAI_COMPATIBLE_API_KEY", None)
        request = request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        request["ownershipEpoch"] = 3
        transcript_store = FakeTranscriptStore()

        with agent_runtime_client(transcript_store) as client:
            response = client.post(
                "/agent-turns/execute-stream",
                json=request,
                headers={"Authorization": "Bearer test-internal-token"},
            )

        self.assertEqual(response.status_code, 200)
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
        self.assertIn("configured streaming model provider", final_frames[0]["payload"]["outcome"]["failureReason"])
        self.assertEqual("PROVIDER_STREAM_UNAVAILABLE", frames[-2]["payload"]["code"])
        self.assertEqual("PROVIDER_STREAM", frames[-2]["payload"]["stage"])
        self.assertFalse(frames[-2]["payload"]["retryable"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"])
        self.assertEqual(1, len(transcript_store.failed))

    def test_should_stream_provider_text_delta_before_final_outcome(self) -> None:
        request = request_payload()
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
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "USER_NOTICE"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_STARTED"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_SNAPSHOT"])
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
        self.assertEqual("OPENAI_COMPATIBLE", committed_entries[-1].provider_type)
        self.assertEqual({"role": "assistant", "content": "已查到订单。"}, committed_entries[-1].content_json)

    def test_should_return_cached_successful_final_outcome_without_calling_provider(self) -> None:
        request = request_payload()
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
        request = request_payload()
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
        request = request_payload()
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

    def test_should_stream_provider_when_privacy_mapping_is_enabled(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-privacy"
        request["turnExecutionId"] = "exec-privacy"
        request["effectivePrivacyMappingEnabled"] = True
        transcript_store = FakeTranscriptStore()
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="隐私映射开启时也继续流式。"),
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
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "ERROR"])
        self.assertEqual("FINAL_OUTCOME", frames[-1]["kind"])
        self.assertTrue(frames[-1]["payload"]["outcome"]["success"])
        self.assertEqual(1, len(transcript_store.committed_successes))

    def test_should_include_committed_same_owner_epoch_transcript_in_provider_payload(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-2"
        request["turnExecutionId"] = "exec-2"
        transcript_store = FakeTranscriptStore(
            committed_entries_by_context={
                ("session-1", "agent-a", 1): [
                    CommittedTranscriptEntry(
                        transcript_seq=7,
                        role="assistant",
                        provider_type="OPENAI_COMPATIBLE",
                        content_json={"role": "assistant", "content": "previous answer"},
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

    def test_should_replay_prior_tool_call_tool_result_and_thinking_without_prompt_duplication(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-2"
        request["turnExecutionId"] = "exec-2"
        replayed_entries = [
            CommittedTranscriptEntry(
                transcript_seq=1,
                role="assistant",
                provider_type="OPENAI_COMPATIBLE",
                content_json={
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "look up order",
                    "tool_calls": [
                        {
                            "id": "call-previous",
                            "type": "function",
                            "function": {
                                "name": "read_skill",
                                "arguments": '{"resourceVersionId": "skill-ver-1"}',
                            },
                        }
                    ],
                },
            ),
            CommittedTranscriptEntry(
                transcript_seq=2,
                role="tool",
                provider_type="OPENAI_COMPATIBLE",
                content_json={
                    "role": "tool",
                    "tool_call_id": "call-previous",
                    "content": '{"accepted": true, "content": "policy"}',
                },
            ),
        ]
        transcript_store = FakeTranscriptStore(
            committed_entries_by_context={
                ("session-1", "agent-a", 1): replayed_entries,
            }
        )
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="next"),
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
        messages = captured_payloads[0]["messages"]
        expected_replay_messages = [entry.content_json for entry in replayed_entries]
        self.assertEqual(expected_replay_messages, messages[:2])
        appended_messages = messages[2:]
        self.assertFalse(
            any("You are the current session owner agent." in str(message.get("content") or "") for message in appended_messages)
        )
        self.assertFalse(any("Capabilities:" in str(message.get("content") or "") for message in appended_messages))
        self.assertEqual(["system", "system", "user"], [message["role"] for message in appended_messages[:3]])
        self.assertIn("Session trigger:", appended_messages[0]["content"])
        self.assertIn("Visible sharedState slice", appended_messages[1]["content"])
        replayed_assistant = messages[0]
        self.assertEqual("", replayed_assistant["content"])
        self.assertEqual("look up order", replayed_assistant["reasoning_content"])
        self.assertEqual("call-previous", replayed_assistant["tool_calls"][0]["id"])
        replayed_tool = messages[1]
        self.assertEqual("call-previous", replayed_tool["tool_call_id"])
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual(["system", "system", "user", "assistant"], [entry.role for entry in committed_entries])
        self.assertEqual(appended_messages[:3], [entry.content_json for entry in committed_entries[:3]])

    def test_should_not_include_committed_transcript_from_different_owner_or_epoch(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-2"
        request["turnExecutionId"] = "exec-2"
        transcript_store = FakeTranscriptStore(
            committed_entries_by_context={
                ("session-1", "agent-b", 1): [
                    CommittedTranscriptEntry(
                        transcript_seq=1,
                        role="assistant",
                        provider_type="OPENAI_COMPATIBLE",
                        content_json={"role": "assistant", "content": "wrong owner"},
                    )
                ],
                ("session-1", "agent-a", 2): [
                    CommittedTranscriptEntry(
                        transcript_seq=2,
                        role="assistant",
                        provider_type="OPENAI_COMPATIBLE",
                        content_json={"role": "assistant", "content": "wrong epoch"},
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
        request = request_payload()
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
        transcript_store = FakeTranscriptStore()
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client(transcript_store) as client:
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
        request = request_payload()
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
        request = request_payload()
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

    def test_should_accumulate_text_message_block_state_and_run_playbook_action(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(event_type="content_delta", delta="我来处理。"),
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-1",
                            tool_name="append_text_block",
                            arguments_delta=json.dumps({"text": "已准备发起流程。"}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=1,
                            tool_call_id="call-2",
                            tool_name="update_shared_state",
                            arguments_delta=json.dumps({"patch": {"refundStatus": "REQUESTED"}}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                        OpenAiCompatibleStreamEvent(event_type="done"),
                    ]
                )
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="流程已确认。"),
                    OpenAiCompatibleStreamEvent(
                        event_type="tool_call_delta",
                        tool_call_index=0,
                        tool_call_id="call-3",
                        tool_name="run_playbook",
                        arguments_delta=json.dumps(
                            {"playbookId": "pb-1", "playbookInput": {"orderId": "order-1"}},
                            ensure_ascii=False,
                        ),
                    ),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        transcript_store = FakeTranscriptStore()
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(2, len(captured_payloads))
        second_round_tool_results = [message for message in captured_payloads[1]["messages"] if message.get("role") == "tool"]
        self.assertTrue(any('"blockId"' in str(message.get("content")) for message in second_round_tool_results))
        self.assertTrue(any('"accepted": true' in str(message.get("content")) for message in second_round_tool_results))
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "ACTION_TOOL_ARGUMENT_DELTA"])
        self.assertEqual([], [frame for frame in frames if "argumentsDelta" in frame["payload"]])
        self.assertTrue(all("modelRoundId" in frame["payload"] for frame in frames if frame["kind"] == "MODEL_STARTED"))
        self.assertTrue(all("modelRoundId" in frame["payload"] for frame in frames if frame["kind"] == "MODEL_COMPLETED"))
        self.assertTrue(all("status" in frame["payload"] for frame in frames if frame["kind"] == "MODEL_COMPLETED"))
        tool_started_frames = [frame for frame in frames if frame["kind"] == "ACTION_TOOL_STARTED"]
        self.assertTrue(tool_started_frames)
        self.assertTrue(all("modelRoundId" in frame["payload"] for frame in tool_started_frames))
        tool_completed_frames = [frame for frame in frames if frame["kind"] == "ACTION_TOOL_COMPLETED"]
        self.assertTrue(tool_completed_frames)
        self.assertTrue(all(frame["payload"].get("status") in {"ACCEPTED", "REJECTED", "FAILED"} for frame in tool_completed_frames))
        self.assertEqual([], [frame for frame in tool_completed_frames if "accepted" in frame["payload"]])
        outcome = frames[-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        decision = outcome["result"]["decision"]
        self.assertEqual("RUN_PLAYBOOK", decision["action"])
        self.assertEqual("pb-1", decision["playbookId"])
        self.assertEqual({"orderId": "order-1"}, decision["playbookInput"])
        self.assertEqual(
            ["我来处理。", "已准备发起流程。", "流程已确认。"],
            [block["text"] for block in decision["replyMessage"]["blocks"]],
        )
        self.assertEqual("email", outcome["result"]["sharedState"]["knownPreference"])
        self.assertEqual("REQUESTED", outcome["result"]["sharedState"]["refundStatus"])
        self.assertEqual(1, len([frame for frame in frames if frame["kind"] == "FINAL_OUTCOME"]))
        self.assertEqual(1, len(transcript_store.committed_successes))
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual(["system", "system", "system", "user"], [entry.role for entry in committed_entries[:4]])
        interaction_entries = committed_entries[4:]
        self.assertEqual(["assistant", "tool", "tool", "assistant", "tool"], [entry.role for entry in interaction_entries])
        self.assertTrue(all(entry.provider_type == "OPENAI_COMPATIBLE" for entry in committed_entries))
        self.assertEqual("call-1", interaction_entries[0].content_json["tool_calls"][0]["id"])
        self.assertEqual("call-1", interaction_entries[1].content_json["tool_call_id"])
        self.assertEqual("call-3", interaction_entries[3].content_json["tool_calls"][0]["id"])
        self.assertEqual("call-3", interaction_entries[4].content_json["tool_call_id"])

    def test_should_replay_same_turn_thinking_with_tool_call_as_reasoning_content(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-thinking-tool"
        request["turnExecutionId"] = "exec-thinking-tool"
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(event_type="thinking_delta", delta="internal lookup plan"),
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-skill-thinking",
                            tool_name="read_skill",
                            arguments_delta=json.dumps({"resourceVersionId": "skill-ver-1"}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                        OpenAiCompatibleStreamEvent(event_type="done"),
                    ]
                )
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="已读取规则。"),
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
        self.assertEqual(2, len(captured_payloads))
        replayed_assistant = next(message for message in captured_payloads[1]["messages"] if message.get("tool_calls"))
        self.assertEqual("internal lookup plan", replayed_assistant["reasoning_content"])
        self.assertNotIn("internal lookup plan", replayed_assistant.get("content") or "")

    def test_should_fail_tool_call_with_missing_id_before_executing_tool_or_committing(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-missing-tool-id"
        request["turnExecutionId"] = "exec-missing-tool-id"
        transcript_store = FakeTranscriptStore()

        events = iter(
            [
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_name="read_skill",
                    arguments_delta=json.dumps({"resourceVersionId": "skill-ver-1"}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with patch("lynxus_agent_runtime.streaming.execute_tool_call") as execute_tool:
                with agent_runtime_client(transcript_store) as client:
                    response = client.post(
                        "/agent-turns/execute-stream",
                        json=request,
                        headers={"Authorization": "Bearer test-internal-token"},
                    )

        self.assertEqual(response.status_code, 200)
        execute_tool.assert_not_called()
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertEqual("FINAL_OUTCOME", frames[-1]["kind"])
        outcome = frames[-1]["payload"]["outcome"]
        self.assertFalse(outcome["success"])
        self.assertIn("tool call id is required", outcome["failureReason"])
        self.assertEqual([], transcript_store.committed_successes)
        self.assertEqual([], transcript_store.pending_entries)
        self.assertEqual(1, len(transcript_store.failed))

    def test_should_persist_current_prompt_bundle_as_provider_transcript(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-hygiene"
        request["turnExecutionId"] = "exec-hygiene"
        transcript_store = FakeTranscriptStore()

        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="final answer"),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                OpenAiCompatibleStreamEvent(
                    event_type="usage",
                    usage={"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
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
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual([], transcript_store.pending_entries)
        self.assertEqual(["system", "system", "system", "user", "assistant"], [entry.role for entry in committed_entries])
        self.assertTrue(all(entry.provider_type == "OPENAI_COMPATIBLE" for entry in committed_entries))
        self.assertIn("You are the current session owner agent.", committed_entries[0].content_json["content"])
        self.assertIn("Session trigger:", committed_entries[1].content_json["content"])
        self.assertIn("Visible sharedState slice", committed_entries[2].content_json["content"])
        self.assertEqual({"role": "user", "content": "帮我发起退款"}, committed_entries[3].content_json)
        self.assertEqual({"role": "assistant", "content": "final answer"}, committed_entries[4].content_json)
        self.assertNotIn("usage", committed_entries[4].content_json)
        self.assertNotIn("finish_reason", committed_entries[4].content_json)
        self.assertNotIn("model", committed_entries[4].content_json)

    def test_should_accumulate_action_only_switch_owner_without_reply_message(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-switch"
        request["turnExecutionId"] = "exec-switch"
        request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER"]
        request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]

        events = iter(
            [
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-switch",
                    tool_name="switch_owner",
                    arguments_delta=json.dumps({"targetAgentId": "agent-b"}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        transcript_store = FakeTranscriptStore()
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        self.assertEqual("SWITCH_OWNER", outcome["result"]["decision"]["action"])
        self.assertEqual("agent-b", outcome["result"]["decision"]["targetAgentId"])
        self.assertIsNone(outcome["result"]["decision"]["replyMessage"])

    def test_should_fail_multiple_non_security_lifecycle_actions(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-conflict"
        request["turnExecutionId"] = "exec-conflict"
        request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK"]
        request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]

        events = iter(
            [
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-switch",
                    tool_name="switch_owner",
                    arguments_delta=json.dumps({"targetAgentId": "agent-b"}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=1,
                    tool_call_id="call-playbook",
                    tool_name="run_playbook",
                    arguments_delta=json.dumps({"playbookId": "pb-1", "playbookInput": {}}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        transcript_store = FakeTranscriptStore()
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
        self.assertFalse(outcome["success"])
        self.assertIn("multiple lifecycle actions", outcome["failureReason"])
        self.assertEqual([], transcript_store.committed_successes)
        self.assertEqual(1, len(transcript_store.failed))

    def test_should_emit_error_before_failed_final_outcome_after_customer_draft(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-conflict-draft"
        request["turnExecutionId"] = "exec-conflict-draft"
        request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK"]
        request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]

        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="我先准备回复。"),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-switch",
                    tool_name="switch_owner",
                    arguments_delta=json.dumps({"targetAgentId": "agent-b"}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=1,
                    tool_call_id="call-playbook",
                    tool_name="run_playbook",
                    arguments_delta=json.dumps({"playbookId": "pb-1", "playbookInput": {}}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                OpenAiCompatibleStreamEvent(event_type="done"),
            ]
        )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        transcript_store = FakeTranscriptStore()
        with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=events):
            with agent_runtime_client(transcript_store) as client:
                response = client.post(
                    "/agent-turns/execute-stream",
                    json=request,
                    headers={"Authorization": "Bearer test-internal-token"},
                )

        self.assertEqual(response.status_code, 200)
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        self.assertTrue(any(frame["kind"] == "REPLY_BLOCK_DELTA" for frame in frames))
        self.assertEqual("ERROR", frames[-2]["kind"])
        self.assertEqual("FINAL_OUTCOME_REJECTED", frames[-2]["payload"]["code"])
        self.assertEqual("FINAL_OUTCOME_BUILD", frames[-2]["payload"]["stage"])
        self.assertFalse(frames[-2]["payload"]["retryable"])
        self.assertEqual("FINAL_OUTCOME", frames[-1]["kind"])
        self.assertFalse(frames[-1]["payload"]["outcome"]["success"])
        self.assertIn("multiple lifecycle actions", frames[-2]["payload"]["message"])
        self.assertEqual([], transcript_store.committed_successes)
        self.assertEqual(1, len(transcript_store.failed))

    def test_should_prioritize_security_block_over_other_lifecycle_actions_with_reply(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-security"
        request["turnExecutionId"] = "exec-security"
        request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER"]
        request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]

        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="我需要阻断这个请求。"),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-switch",
                    tool_name="switch_owner",
                    arguments_delta=json.dumps({"targetAgentId": "agent-b"}, ensure_ascii=False),
                ),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=1,
                    tool_call_id="call-security",
                    tool_name="security_block",
                    arguments_delta=json.dumps(
                        {"categories": ["PROMPT_INJECTION"], "reason": "prompt_injection", "confidence": 0.95},
                        ensure_ascii=False,
                    ),
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
        outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        self.assertEqual("SECURITY_BLOCK", outcome["result"]["decision"]["action"])
        self.assertEqual("我需要阻断这个请求。", outcome["result"]["decision"]["replyMessage"]["blocks"][0]["text"])
        self.assertEqual("BLOCK", outcome["result"]["securityAssessment"]["action"])
        self.assertEqual(["PROMPT_INJECTION"], outcome["result"]["securityAssessment"]["categories"])

    def test_should_prioritize_security_block_over_invalid_lifecycle_actions(self) -> None:
        cases = [
            ("switch_owner", {"targetAgentId": "agent-x"}),
            ("run_playbook", {"playbookId": "pb-x", "playbookInput": {}}),
            ("human_handoff", {"reason": "billing"}),
        ]

        for index, (tool_name, arguments) in enumerate(cases, start=1):
            request = request_payload()
            request["turnId"] = f"turn-security-invalid-{index}"
            request["turnExecutionId"] = f"exec-security-invalid-{index}"
            request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK"]
            request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]
            events = iter(
                [
                    OpenAiCompatibleStreamEvent(
                        event_type="tool_call_delta",
                        tool_call_index=0,
                        tool_call_id=f"call-invalid-{index}",
                        tool_name=tool_name,
                        arguments_delta=json.dumps(arguments, ensure_ascii=False),
                    ),
                    OpenAiCompatibleStreamEvent(
                        event_type="tool_call_delta",
                        tool_call_index=1,
                        tool_call_id=f"call-security-{index}",
                        tool_name="security_block",
                        arguments_delta=json.dumps(
                            {"categories": ["PROMPT_INJECTION"], "reason": "prompt_injection", "confidence": 0.97},
                            ensure_ascii=False,
                        ),
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
            outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
            self.assertTrue(outcome["success"])
            self.assertEqual("SECURITY_BLOCK", outcome["result"]["decision"]["action"])
            self.assertEqual(["PROMPT_INJECTION"], outcome["result"]["securityAssessment"]["categories"])

    def test_should_fail_multiple_same_lifecycle_actions_without_security_block(self) -> None:
        cases = [
            (
                [
                    ("call-switch-1", "switch_owner", {"targetAgentId": "agent-b"}),
                    ("call-switch-2", "switch_owner", {"targetAgentId": "agent-b"}),
                ],
                "multiple lifecycle actions",
            ),
            (
                [
                    ("call-playbook-1", "run_playbook", {"playbookId": "pb-1", "playbookInput": {"step": 1}}),
                    ("call-playbook-2", "run_playbook", {"playbookId": "pb-1", "playbookInput": {"step": 2}}),
                ],
                "multiple lifecycle actions",
            ),
        ]

        for index, (tool_calls, expected_reason) in enumerate(cases, start=1):
            request = request_payload()
            request["turnId"] = f"turn-same-action-{index}"
            request["turnExecutionId"] = f"exec-same-action-{index}"
            request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK"]
            request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]
            events = [
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=tool_index,
                    tool_call_id=tool_call_id,
                    tool_name=tool_name,
                    arguments_delta=json.dumps(arguments, ensure_ascii=False),
                )
                for tool_index, (tool_call_id, tool_name, arguments) in enumerate(tool_calls)
            ]
            events.extend(
                [
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

            os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
            with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", return_value=iter(events)):
                with agent_runtime_client() as client:
                    response = client.post(
                        "/agent-turns/execute-stream",
                        json=request,
                        headers={"Authorization": "Bearer test-internal-token"},
                    )

            self.assertEqual(response.status_code, 200)
            outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
            self.assertFalse(outcome["success"])
            self.assertIn(expected_reason, outcome["failureReason"])

    def test_should_merge_update_shared_state_patch_into_final_snapshot(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-state"
        request["turnExecutionId"] = "exec-state"
        request["currentOwner"]["allowedActions"] = ["NO_OP"]
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-state",
                            tool_name="update_shared_state",
                            arguments_delta=json.dumps({"patch": {"nested": {"value": 1}}}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                        OpenAiCompatibleStreamEvent(event_type="done"),
                    ]
                )
            return iter(
                [
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
        self.assertEqual(2, len(captured_payloads))
        outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        self.assertEqual("NO_OP", outcome["result"]["decision"]["action"])
        self.assertEqual({"knownPreference": "email", "nested": {"value": 1}}, outcome["result"]["sharedState"])

    def test_should_return_read_skill_result_to_next_streaming_model_round(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-skill"
        request["turnExecutionId"] = "exec-skill"
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-skill",
                            tool_name="read_skill",
                            arguments_delta=json.dumps({"resourceVersionId": "skill-ver-1"}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                        OpenAiCompatibleStreamEvent(event_type="done"),
                    ]
                )
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="已读取退款规则。"),
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
        self.assertEqual(2, len(captured_payloads))
        tool_names = {
            tool["function"]["name"]
            for tool in captured_payloads[0]["tools"]
            if tool.get("type") == "function" and isinstance(tool.get("function"), dict)
        }
        self.assertTrue(
            {
                "read_skill",
                "append_text_block",
                "append_image_block",
                "append_rich_text_block",
                "append_card_block",
                "update_shared_state",
                "switch_owner",
                "run_playbook",
                "human_handoff",
                "security_block",
            }.issubset(tool_names)
        )
        tool_result_messages = [message for message in captured_payloads[1]["messages"] if message.get("role") == "tool"]
        self.assertTrue(any("退款时必须先确认订单状态" in str(message.get("content")) for message in tool_result_messages))
        outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        self.assertEqual("REPLY", outcome["result"]["decision"]["action"])
        self.assertEqual("已读取退款规则。", outcome["result"]["decision"]["replyMessage"]["blocks"][0]["text"])

    def test_should_persist_sanitized_tool_result_provider_message(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-sanitized-tool"
        request["turnExecutionId"] = "exec-sanitized-tool"
        captured_payloads: list[dict] = []
        transcript_store = FakeTranscriptStore()

        class FakePrivacyPipeline:
            def __init__(self) -> None:
                self.sanitized_payloads: list[tuple[str, dict]] = []

            def sanitize_prompt_bundle(self, bundle):  # noqa: ANN001
                return bundle

            def sanitize_outbound(self, channel, payload):  # noqa: ANN001
                self.sanitized_payloads.append((channel, payload))
                if channel == "TOOL_RESULT":
                    return {"accepted": payload.get("accepted"), "content": "sanitized skill content"}
                return payload

            def close(self) -> None:
                return None

        privacy_pipeline = FakePrivacyPipeline()

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-skill",
                            tool_name="read_skill",
                            arguments_delta=json.dumps({"resourceVersionId": "skill-ver-1"}, ensure_ascii=False),
                        ),
                        OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="tool_calls"),
                        OpenAiCompatibleStreamEvent(event_type="done"),
                    ]
                )
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="done"),
                    OpenAiCompatibleStreamEvent(event_type="finish_reason", finish_reason="stop"),
                    OpenAiCompatibleStreamEvent(event_type="done"),
                ]
            )

        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        with patch("lynxus_agent_runtime.streaming.build_privacy_pipeline", return_value=privacy_pipeline):
            with patch("lynxus_agent_runtime.streaming.stream_chat_completion_events", side_effect=fake_stream):
                with agent_runtime_client(transcript_store) as client:
                    response = client.post(
                        "/agent-turns/execute-stream",
                        json=request,
                        headers={"Authorization": "Bearer test-internal-token"},
                    )

        self.assertEqual(response.status_code, 200)
        self.assertEqual("TOOL_RESULT", privacy_pipeline.sanitized_payloads[0][0])
        tool_messages = [message for message in captured_payloads[1]["messages"] if message.get("role") == "tool"]
        self.assertEqual(1, len(tool_messages))
        self.assertIn("sanitized skill content", tool_messages[0]["content"])
        self.assertNotIn("退款时必须先确认订单状态", tool_messages[0]["content"])
        committed_tool_entries = [
            entry for entry in transcript_store.committed_successes[0][2] if entry.role == "tool"
        ]
        self.assertEqual(tool_messages, [entry.content_json for entry in committed_tool_entries])

    def test_should_reject_invalid_native_lifecycle_tool_arguments(self) -> None:
        cases = [
            ("switch_owner", {"targetAgentId": "agent-x"}, "switch_owner targetAgentId is not allowed"),
            ("run_playbook", {"playbookId": "pb-x", "playbookInput": {}}, "run_playbook playbookId is not allowed"),
            ("human_handoff", {"reason": "billing"}, "action SESSION_HUMAN_HANDOFF is not allowed"),
        ]

        for index, (tool_name, arguments, expected_reason) in enumerate(cases, start=1):
            request = request_payload()
            request["turnId"] = f"turn-invalid-{index}"
            request["turnExecutionId"] = f"exec-invalid-{index}"
            request["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK"]
            request["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]
            events = iter(
                [
                    OpenAiCompatibleStreamEvent(
                        event_type="tool_call_delta",
                        tool_call_index=0,
                        tool_call_id=f"call-invalid-{index}",
                        tool_name=tool_name,
                        arguments_delta=json.dumps(arguments, ensure_ascii=False),
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
            outcome = [json.loads(line) for line in response.text.splitlines() if line.strip()][-1]["payload"]["outcome"]
            self.assertFalse(outcome["success"])
            self.assertIn(expected_reason, outcome["failureReason"])

    def test_should_protect_stream_endpoint_with_internal_auth(self) -> None:
        with agent_runtime_client() as client:
            response = client.post("/agent-turns/execute-stream", json={})

        self.assertEqual(response.status_code, 401)
