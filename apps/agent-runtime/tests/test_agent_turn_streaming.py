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
from lynxus_agent_runtime.privacy_contracts import PrivacyMappingTelemetry
from lynxus_agent_runtime.streaming import _StreamingOutcomeAccumulator
from lynxus_agent_runtime.tooling import (
    OUTCOME_TOOL_KINDS,
    RuntimeToolKind,
    execute_tool_call,
    runtime_tool_specs,
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
        return {"backend": "postgresql", "schema": "public"}

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

        definitions = [spec.definition for spec in runtime_tool_specs(request)]
        tool_names = [definition.name for definition in definitions]

        self.assertEqual("get_owner_capabilities", tool_names[0])
        self.assertEqual("knowledge_search", tool_names[1])
        self.assertEqual("knowledge_read", tool_names[2])
        self.assertIn("read_skill", tool_names)
        self.assertNotIn("append_text_block", tool_names)
        self.assertIn("update_shared_state", tool_names)
        self.assertIn("run_playbook", tool_names)
        self.assertNotIn("switch_owner", tool_names)
        self.assertNotIn("human_handoff", tool_names)
        self.assertIn("security_block", tool_names)
        self.assertIn("create_ticket", tool_names)
        definitions_by_name = {definition.name: definition for definition in definitions}
        read_skill_schema = definitions_by_name["read_skill"].input_schema
        self.assertEqual(["skill-ver-1"], read_skill_schema["properties"]["skillId"]["enum"])
        self.assertIn("skill-ver-1 = Refund Policy", read_skill_schema["properties"]["skillId"]["description"])
        run_playbook_schema = definitions_by_name["run_playbook"].input_schema
        self.assertEqual(["pb-1"], run_playbook_schema["properties"]["playbookId"]["enum"])
        self.assertIn("pb-1 = Playbook 1 - refund flow", run_playbook_schema["properties"]["playbookId"]["description"])
        self.assertNotIn("blockId", definitions_by_name["append_image_block"].output_schema["properties"])
        self.assertNotIn("blockId", definitions_by_name["append_rich_text_block"].output_schema["properties"])
        self.assertNotIn("blockId", definitions_by_name["append_card_block"].output_schema["properties"])
        specs_by_name = {spec.name: spec for spec in runtime_tool_specs(request)}
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, specs_by_name["knowledge_search"].kind)
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, specs_by_name["read_skill"].kind)
        self.assertEqual(RuntimeToolKind.STATE_TOOL, specs_by_name["update_shared_state"].kind)
        self.assertEqual(RuntimeToolKind.LIFECYCLE_ACTION_TOOL, specs_by_name["run_playbook"].kind)
        self.assertNotIn("switch_owner", specs_by_name)
        self.assertNotIn("human_handoff", specs_by_name)
        self.assertEqual(RuntimeToolKind.CONTEXT_TOOL, specs_by_name["create_ticket"].kind)

    def test_should_wire_every_runtime_tool_spec_to_its_executor(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["allowedActions"] = ["SWITCH_OWNER", "RUN_PLAYBOOK", "SESSION_HUMAN_HANDOFF"]
        payload["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]
        request = AgentTurnRequest.model_validate(payload)

        specs = runtime_tool_specs(request)

        context_tools_without_handler = [
            spec.name
            for spec in specs
            if spec.kind == RuntimeToolKind.CONTEXT_TOOL and spec.handler is None
        ]
        outcome_tool_kinds = {
            spec.name: spec.kind
            for spec in specs
            if spec.kind != RuntimeToolKind.CONTEXT_TOOL
        }
        accumulator = _StreamingOutcomeAccumulator(request)

        self.assertEqual([], context_tools_without_handler)
        self.assertEqual(OUTCOME_TOOL_KINDS, outcome_tool_kinds)
        self.assertEqual(outcome_tool_kinds, accumulator.handled_outcome_tool_kinds())

    def test_should_describe_switch_owner_targets_in_tool_schema(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["allowedActions"] = ["SWITCH_OWNER"]
        payload["currentOwner"]["switchableOwnerAgentIds"] = ["agent-b"]
        request = AgentTurnRequest.model_validate(payload)

        definitions_by_name = {
            spec.definition.name: spec.definition
            for spec in runtime_tool_specs(request)
        }

        self.assertIn("switch_owner", definitions_by_name)
        self.assertNotIn("run_playbook", definitions_by_name)
        target_schema = definitions_by_name["switch_owner"].input_schema["properties"]["targetAgentId"]
        self.assertEqual(["agent-b"], target_schema["enum"])
        self.assertIn("agent-b = Agent B, ops - handle escalations", target_schema["description"])

    def test_should_describe_human_handoff_operator_reason_and_lifecycle_contract(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["allowedActions"] = ["SESSION_HUMAN_HANDOFF"]
        request = AgentTurnRequest.model_validate(payload)

        definitions_by_name = {
            spec.definition.name: spec.definition
            for spec in runtime_tool_specs(request)
        }

        handoff_tool = definitions_by_name["human_handoff"]
        self.assertIn("terminal lifecycle action", handoff_tool.description)
        self.assertIn("agent turn stops", handoff_tool.description)
        self.assertIn("customer-visible assistant reply before calling", handoff_tool.description)
        self.assertIn("operatorReason is not customer-visible", handoff_tool.description)
        self.assertIn("operatorReason", handoff_tool.input_schema["properties"])
        self.assertNotIn("reason", handoff_tool.input_schema["properties"])
        self.assertFalse(handoff_tool.input_schema["additionalProperties"])

    def test_should_hide_connector_type_from_resource_tool_description_fallback(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["tools"][0]["operations"][0]["description"] = ""
        request = AgentTurnRequest.model_validate(payload)

        specs_by_name = {spec.name: spec for spec in runtime_tool_specs(request)}

        description = specs_by_name["create_ticket"].definition.description
        self.assertEqual("Invoke Ticket Tool.create_ticket.", description)
        self.assertNotIn("simple-http", description)
        self.assertNotIn("connector", description)

    def test_should_read_shared_owner_capability_directory(self) -> None:
        request = AgentTurnRequest.model_validate(request_payload())

        specs_by_name = {spec.name: spec for spec in runtime_tool_specs(request)}
        capabilities = execute_tool_call(request, specs_by_name["get_owner_capabilities"], {})

        self.assertEqual("agent-a", capabilities["ownerAgentId"])
        self.assertEqual(["REPLY", "RUN_PLAYBOOK", "SECURITY_BLOCK"], capabilities["allowedActions"])
        self.assertEqual(["pb-1"], capabilities["playbookIds"])
        self.assertEqual(
            [
                {
                    "skillId": "skill-ver-1",
                    "skillName": "Refund Policy",
                    "skillDesc": "Read refund constraints before answering.",
                }
            ],
            capabilities["skills"],
        )
        self.assertEqual("snapshot-1", capabilities["knowledgeBinding"]["snapshotId"])
        self.assertEqual(
            {
                "name": "create_ticket",
                "description": "Create a service ticket.",
            },
            capabilities["functions"][0],
        )
        self.assertNotIn("connectorType", capabilities["functions"][0])
        self.assertNotIn("resourceVersionId", capabilities["functions"][0])
        self.assertNotIn("resourceName", capabilities["functions"][0])
        self.assertNotIn("inputSchema", capabilities["functions"][0])

    def test_should_exclude_outcome_tools_from_context_only_runtime_specs(self) -> None:
        request = AgentTurnRequest.model_validate(request_payload())

        tool_names = {
            spec.definition.name
            for spec in runtime_tool_specs(request, include_outcome_tools=False)
        }

        self.assertIn("get_owner_capabilities", tool_names)
        self.assertIn("knowledge_search", tool_names)
        self.assertIn("read_skill", tool_names)
        self.assertIn("create_ticket", tool_names)
        self.assertNotIn("append_text_block", tool_names)
        self.assertNotIn("update_shared_state", tool_names)
        self.assertNotIn("run_playbook", tool_names)

    def test_should_exclude_knowledge_tools_without_effective_binding(self) -> None:
        payload = request_payload()
        payload["currentOwner"]["knowledgeEnabled"] = False
        request = AgentTurnRequest.model_validate(payload)

        tool_names = {spec.definition.name for spec in runtime_tool_specs(request)}

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
        self.assertEqual("session-message-reply-1", frames[-2]["payload"]["messageId"])
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
        self.assertTrue(all(frame["payload"]["messageId"] == "session-message-reply-1" for frame in delta_frames))
        completed_frames = [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_COMPLETED"]
        self.assertEqual(["session-message-reply-1"], [frame["payload"]["messageId"] for frame in completed_frames])
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

    def test_should_restore_split_privacy_placeholders_before_customer_stream_and_final_outcome(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-privacy-restore"
        request["turnExecutionId"] = "exec-privacy-restore"
        request["effectivePrivacyMappingEnabled"] = True
        transcript_store = FakeTranscriptStore()

        class FakePrivacyPipeline:
            enabled = True

            def __init__(self) -> None:
                self.restore_count = 0

            def sanitize_prompt_instruction(self, instruction):  # noqa: ANN001
                return instruction

            def sanitize_semantic_messages(self, messages):  # noqa: ANN001
                return messages

            def restore_inbound(self, channel, payload):  # noqa: ANN001
                self.restore_count += 1

                def restore(value):  # noqa: ANN001
                    if isinstance(value, str):
                        return value.replace(
                            "<<PERSON_001>>",
                            "Alice Johnson",
                        ).replace("<<PHONE_001>>", "13812345678")
                    if isinstance(value, dict):
                        return {key: restore(item) for key, item in value.items()}
                    if isinstance(value, list):
                        return [restore(item) for item in value]
                    return value

                return restore(payload)

            def telemetry(self) -> PrivacyMappingTelemetry:
                return PrivacyMappingTelemetry(
                    enabled=True,
                    restoreCountByChannel={"MODEL_FINAL_RESPONSE": self.restore_count},
                )

            def close(self) -> None:
                return None

        privacy_pipeline = FakePrivacyPipeline()

        def fake_stream(_settings, _payload, *, idle_timeout_seconds):  # noqa: ANN001
            return iter(
                [
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="Hello <<PER"),
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta="SON_001"),
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta=">>, your phone is <<PHONE_001"),
                    OpenAiCompatibleStreamEvent(event_type="content_delta", delta=">>."),
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
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        delta_text = "".join(
            frame["payload"]["delta"]
            for frame in frames
            if frame["kind"] == "REPLY_BLOCK_DELTA"
        )
        self.assertEqual("Hello Alice Johnson, your phone is 13812345678.", delta_text)
        self.assertNotIn("<<PERSON_001>>", delta_text)
        self.assertNotIn("<<PHONE_001>>", delta_text)
        final_outcome = frames[-1]["payload"]["outcome"]
        final_text = final_outcome["result"]["decision"]["replyMessage"]["blocks"][0]["text"]
        self.assertEqual("Hello Alice Johnson, your phone is 13812345678.", final_text)
        self.assertEqual(
            {"MODEL_FINAL_RESPONSE": privacy_pipeline.restore_count},
            final_outcome["result"]["mappingTelemetry"]["restoreCountByChannel"],
        )
        committed_text = transcript_store.committed_successes[0][2][-1].content_json["content"]
        self.assertEqual("Hello <<PERSON_001>>, your phone is <<PHONE_001>>.", committed_text)

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
                                "arguments": '{"skillId": "skill-ver-1"}',
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
        self.assertEqual(["user"], [message["role"] for message in appended_messages])
        self.assertEqual("帮我发起退款", appended_messages[0]["content"])
        self.assertNotIn("Visible sharedState slice", "\n".join(str(message.get("content") or "") for message in appended_messages))
        self.assertNotIn("Session trigger:", "\n".join(str(message.get("content") or "") for message in appended_messages))
        replayed_assistant = messages[0]
        self.assertEqual("", replayed_assistant["content"])
        self.assertEqual("look up order", replayed_assistant["reasoning_content"])
        self.assertEqual("call-previous", replayed_assistant["tool_calls"][0]["id"])
        replayed_tool = messages[1]
        self.assertEqual("call-previous", replayed_tool["tool_call_id"])
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual(["user", "assistant"], [entry.role for entry in committed_entries])
        self.assertEqual(appended_messages[:1], [entry.content_json for entry in committed_entries[:1]])

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
        self.assertIn("For ordinary text or Markdown replies, write the reply directly as assistant content", rendered_prompt)
        self.assertIn("Never describe tool calls, accepted tool results, state updates, message block writes", rendered_prompt)

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

    def test_should_accumulate_streamed_text_state_and_run_playbook_action(self) -> None:
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
            ["我来处理。", "流程已确认。"],
            [block["text"] for block in decision["replyMessage"]["blocks"]],
        )
        self.assertEqual("email", outcome["result"]["sharedState"]["knownPreference"])
        self.assertEqual("REQUESTED", outcome["result"]["sharedState"]["refundStatus"])
        self.assertEqual(1, len([frame for frame in frames if frame["kind"] == "FINAL_OUTCOME"]))
        self.assertEqual(1, len(transcript_store.committed_successes))
        committed_entries = transcript_store.committed_successes[0][2]
        self.assertEqual(["system", "user", "user"], [entry.role for entry in committed_entries[:3]])
        self.assertTrue(committed_entries[1].content_json["content"].startswith("<system-reminder>"))
        self.assertIn("Visible sharedState slice", committed_entries[1].content_json["content"])
        self.assertEqual({"role": "user", "content": "帮我发起退款"}, committed_entries[2].content_json)
        interaction_entries = committed_entries[3:]
        self.assertEqual(["assistant", "tool", "assistant", "tool"], [entry.role for entry in interaction_entries])
        self.assertTrue(all(entry.provider_type == "OPENAI_COMPATIBLE" for entry in committed_entries))
        self.assertEqual("call-2", interaction_entries[0].content_json["tool_calls"][0]["id"])
        self.assertEqual("call-2", interaction_entries[1].content_json["tool_call_id"])
        self.assertEqual("call-3", interaction_entries[2].content_json["tool_calls"][0]["id"])
        self.assertEqual("call-3", interaction_entries[3].content_json["tool_call_id"])

    def test_should_accumulate_human_handoff_operator_reason(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-handoff"
        request["turnExecutionId"] = "exec-handoff"
        request["currentOwner"]["allowedActions"] = ["SESSION_HUMAN_HANDOFF"]

        events = iter(
            [
                OpenAiCompatibleStreamEvent(event_type="content_delta", delta="我会为你转接人工。"),
                OpenAiCompatibleStreamEvent(
                    event_type="tool_call_delta",
                    tool_call_index=0,
                    tool_call_id="call-handoff",
                    tool_name="human_handoff",
                    arguments_delta=json.dumps(
                        {"operatorReason": "  billing escalation  "},
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
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        outcome = frames[-1]["payload"]["outcome"]
        self.assertTrue(outcome["success"])
        decision = outcome["result"]["decision"]
        self.assertEqual("SESSION_HUMAN_HANDOFF", decision["action"])
        self.assertEqual("billing escalation", decision["operatorReason"])
        self.assertEqual("我会为你转接人工。", decision["replyMessage"]["blocks"][0]["text"])

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
                            arguments_delta=json.dumps({"skillId": "skill-ver-1"}, ensure_ascii=False),
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
                    arguments_delta=json.dumps({"skillId": "skill-ver-1"}, ensure_ascii=False),
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

    def test_should_persist_current_prompt_as_provider_transcript(self) -> None:
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
        self.assertEqual(["system", "user", "user", "assistant"], [entry.role for entry in committed_entries])
        self.assertTrue(all(entry.provider_type == "OPENAI_COMPATIBLE" for entry in committed_entries))
        self.assertIn("You are the current session owner agent.", committed_entries[0].content_json["content"])
        self.assertIn("Visible sharedState slice", committed_entries[1].content_json["content"])
        self.assertNotIn("Session trigger:", "\n".join(str(entry.content_json.get("content") or "") for entry in committed_entries))
        self.assertEqual({"role": "user", "content": "帮我发起退款"}, committed_entries[2].content_json)
        self.assertEqual({"role": "assistant", "content": "final answer"}, committed_entries[3].content_json)
        self.assertNotIn("usage", committed_entries[3].content_json)
        self.assertNotIn("finish_reason", committed_entries[3].content_json)
        self.assertNotIn("model", committed_entries[3].content_json)

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

    def test_should_hide_message_block_tool_block_id_from_model_context(self) -> None:
        request = request_payload()
        request["turnId"] = "turn-message-block"
        request["turnExecutionId"] = "exec-message-block"
        captured_payloads: list[dict] = []

        def fake_stream(_settings, payload, *, idle_timeout_seconds):  # noqa: ANN001
            captured_payloads.append(payload)
            if len(captured_payloads) == 1:
                return iter(
                    [
                        OpenAiCompatibleStreamEvent(
                            event_type="tool_call_delta",
                            tool_call_index=0,
                            tool_call_id="call-block",
                            tool_name="append_rich_text_block",
                            arguments_delta=json.dumps(
                                {"format": "MARKDOWN", "content": "**refund details**"},
                                ensure_ascii=False,
                            ),
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
        append_tool = next(
            tool
            for tool in captured_payloads[0]["tools"]
            if tool["function"]["name"] == "append_rich_text_block"
        )
        self.assertNotIn("blockId", append_tool["function"]["description"])
        tool_result_messages = [
            message for message in captured_payloads[1]["messages"] if message.get("role") == "tool"
        ]
        self.assertEqual(1, len(tool_result_messages))
        self.assertIn('"accepted": true', tool_result_messages[0]["content"])
        self.assertNotIn("blockId", tool_result_messages[0]["content"])
        frames = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        completed_frame = next(
            frame
            for frame in frames
            if frame["kind"] == "ACTION_TOOL_COMPLETED"
            and frame["payload"]["toolName"] == "append_rich_text_block"
        )
        self.assertEqual({"messageBlockId": "tool-block-1"}, completed_frame["payload"]["produced"])

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
                            arguments_delta=json.dumps({"skillId": "skill-ver-1"}, ensure_ascii=False),
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
                "append_image_block",
                "append_rich_text_block",
                "append_card_block",
                "update_shared_state",
                "run_playbook",
                "security_block",
            }.issubset(tool_names)
        )
        self.assertNotIn("switch_owner", tool_names)
        self.assertNotIn("human_handoff", tool_names)
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
            enabled = True

            def __init__(self) -> None:
                self.sanitized_payloads: list[tuple[str, dict]] = []

            def sanitize_prompt_instruction(self, instruction):  # noqa: ANN001
                return instruction

            def sanitize_semantic_messages(self, messages):  # noqa: ANN001
                return messages

            def sanitize_outbound(self, channel, payload):  # noqa: ANN001
                self.sanitized_payloads.append((channel, payload))
                if channel == "TOOL_RESULT":
                    return {"accepted": payload.get("accepted"), "content": "sanitized skill content"}
                return payload

            def telemetry(self) -> None:
                return None

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
                            arguments_delta=json.dumps({"skillId": "skill-ver-1"}, ensure_ascii=False),
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
            ("human_handoff", {"operatorReason": "billing"}, "unsupported streaming tool call: human_handoff"),
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
