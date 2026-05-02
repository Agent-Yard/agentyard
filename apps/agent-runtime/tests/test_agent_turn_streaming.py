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

from test_decisioning_loop import _request_payload


class FakeRedisClient:
    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


@contextmanager
def agent_runtime_client():
    with patch("lynxus_agent_runtime.main.create_redis_client", return_value=FakeRedisClient()):
        with TestClient(app) as client:
            yield client


class AgentTurnStreamingTest(unittest.TestCase):
    def test_should_emit_ndjson_frames_with_single_final_outcome_for_non_streaming_fallback(self) -> None:
        os.environ.pop("TEST_OPENAI_COMPATIBLE_API_KEY", None)
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"
        request["ownershipEpoch"] = 3
        reply = SessionMessageInput.model_validate(
            {"blocks": [{"type": "TEXT", "text": "已查到订单。"}], "metadata": {}}
        )
        outcome = AgentTurnExecutionOutcome(
            success=True,
            result=AgentTurnResult(
                decision=AgentDecision(action="REPLY", replyMessage=reply),
                sharedState={"knownPreference": "email"},
            ),
        )

        with patch("lynxus_agent_runtime.streaming.execute_agent_turn", return_value=(outcome, object())):
            with agent_runtime_client() as client:
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
        self.assertTrue(final_frames[0]["payload"]["outcome"]["success"])
        self.assertEqual([], [frame for frame in frames if frame["kind"] == "REPLY_BLOCK_DELTA"])

    def test_should_stream_provider_text_delta_before_final_outcome(self) -> None:
        request = _request_payload()
        request["turnId"] = "turn-1"
        request["turnExecutionId"] = "exec-1"

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
            with agent_runtime_client() as client:
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
