import os
import subprocess
import sys
import unittest
from pathlib import Path

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.models import AgentTurnRequest
from lynxus_agent_runtime.prompting import (
    build_initial_runtime_messages,
    build_system_instruction,
    build_turn_input_messages,
    render_openai_streaming_messages,
)


def _text_message(message_id: str, sequence: int, role: str, text: str, *, turn_id: str = "turn-1") -> dict:
    sender_type = {
        "USER": "CUSTOMER",
        "ASSISTANT": "AGENT",
        "HUMAN_OPERATOR": "HUMAN_OPERATOR",
        "SYSTEM": "SYSTEM",
    }[role]
    return {
        "messageId": message_id,
        "sessionId": "session-1",
        "sequence": sequence,
        "turnId": turn_id,
        "turnIndex": sequence - 1,
        "producerType": "EXTERNAL",
        "externalMessageId": None,
        "clientMessageId": None,
        "occurredAt": f"2026-04-19T00:00:0{sequence}Z",
        "role": role,
        "sender": {
            "senderType": sender_type,
            "senderId": f"{sender_type.lower()}-1",
            "senderName": f"{sender_type.lower()}-1",
        },
        "status": "DELIVERED",
        "blocks": [{"type": "TEXT", "text": text}],
        "metadata": {},
        "createdAt": f"2026-04-19T00:00:0{sequence}Z",
        "updatedAt": f"2026-04-19T00:00:0{sequence}Z",
    }


def _context_entry(
    entry_id: str,
    entry_type: str,
    revision: int,
    occurred_at: str,
    data: dict,
) -> dict:
    return {
        "entryId": entry_id,
        "entryType": entry_type,
        "revision": revision,
        "occurredAt": occurred_at,
        "data": data,
    }


def _request_payload(**overrides) -> dict:
    payload = {
        "sessionId": "session-1",
        "turnId": "turn-1",
        "turnExecutionId": "turn-1:exec-1",
        "replyMessageId": "session-message-reply-1",
        "assistantId": "assistant-1",
        "assistantReleaseVersion": "2026.04.19",
        "currentOwner": {
            "agentId": "agent-a",
            "name": "Agent A",
            "role": "support",
            "responsibility": "help the customer",
            "allowedActions": ["REPLY", "RUN_PLAYBOOK"],
            "switchableOwnerAgentIds": ["agent-b"],
            "playbookIds": ["pb-1"],
        },
        "availableAgents": [],
        "availablePlaybooks": [{"playbookId": "pb-1", "name": "Playbook 1"}],
        "sharedState": {"knownPreference": "email"},
        "trigger": {
            "triggerType": "USER_MESSAGE",
            "turnId": "turn-1",
            "eventId": None,
            "payload": {"text": "hello"},
        },
        "messages": [_text_message("msg-1", 1, "USER", "hello")],
        "contextEntries": [],
        "transcriptBootstrap": False,
    }
    payload.update(overrides)
    return payload


class AgentRuntimePromptingTest(unittest.TestCase):
    def test_should_directly_import_prompting_and_semantic_modules(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                (
                    "import lynxus_agent_runtime.prompting; "
                    "import lynxus_agent_runtime.semantic; "
                    "from lynxus_agent_runtime.privacy_pipeline import PrivacyPipeline, build_privacy_pipeline; "
                    "print('ok')"
                ),
            ],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual("ok", result.stdout.strip(), result.stderr)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_should_build_prompt_instruction_and_delta_runtime_context(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                messages=[
                    _text_message("msg-2", 2, "USER", "second"),
                    _text_message("msg-1", 1, "USER", "first"),
                ],
                contextEntries=[
                    _context_entry(
                        "shared-state-patch:session-1:2",
                        "SHARED_STATE_PATCH",
                        2,
                        "2026-04-19T00:00:04Z",
                        {"patch": {"knownPreference": "email"}},
                    ),
                    _context_entry(
                        "event-1",
                        "SESSION_EVENT",
                        1,
                        "2026-04-19T00:00:03Z",
                        {"eventType": "HUMAN_RESUME_RECEIVED", "payload": {"note": "resume"}},
                    ),
                ],
            )
        )

        instruction = build_system_instruction(request)
        runtime_messages = build_initial_runtime_messages(request)

        self.assertIn("Function tools define the current owner capability boundary", instruction)
        self.assertIn("Use function tool names, parameter schemas, and parameter descriptions", instruction)
        self.assertIn("Use exactly one customer-visible output channel for the same reply content", instruction)
        self.assertNotIn("Knowledge binding:", instruction)
        self.assertEqual(["system_event", "system_event", "user_turn", "user_turn"], [message.kind for message in runtime_messages])
        self.assertIn("HUMAN_RESUME_RECEIVED", runtime_messages[0].content)
        self.assertIn("SHARED_STATE_PATCH", runtime_messages[1].content)
        self.assertEqual(["first", "second"], [message.content for message in runtime_messages[2:]])
        self.assertIn("Owner identity: Agent A", instruction)

    def test_should_not_render_shared_state_without_context_entry(self) -> None:
        request = AgentTurnRequest.model_validate(_request_payload(sharedState={"secret": "value"}, contextEntries=[]))

        runtime_messages = build_turn_input_messages(request)

        rendered = "\n".join(message.content for message in runtime_messages)
        self.assertNotIn("secret", rendered)
        self.assertEqual(["user_turn"], [message.kind for message in runtime_messages])
        self.assertEqual("hello", runtime_messages[0].content)

    def test_should_render_shared_state_snapshot_from_context_entry(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                contextEntries=[
                    _context_entry(
                        "shared-state-snapshot:session-1:7",
                        "SHARED_STATE_SNAPSHOT",
                        7,
                        "2026-04-19T00:00:02Z",
                        {"sharedState": {"customerId": "customer-1", "knownPreference": "email"}},
                    )
                ]
            )
        )

        runtime_messages = build_initial_runtime_messages(request)
        snapshot_message = runtime_messages[0]

        self.assertEqual("system_event", snapshot_message.kind)
        self.assertIn("SHARED_STATE_SNAPSHOT", snapshot_message.content)
        self.assertIn('"knownPreference": "email"', snapshot_message.content)

    def test_should_render_active_playbook_summary_from_context_entry(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                activePlaybook={
                    "runId": "run-secret-1",
                    "playbookId": "pb-active",
                    "playbookName": "Active Refund Flow",
                    "status": "RUNNING",
                    "latestResult": {"summary": "ticket is open"},
                },
                contextEntries=[
                    _context_entry(
                        "active-playbook:run-secret-1:3",
                        "ACTIVE_PLAYBOOK_SUMMARY",
                        3,
                        "2026-04-19T00:00:02Z",
                        {
                            "runId": "run-secret-1",
                            "playbookId": "pb-active",
                            "status": "RUNNING",
                            "latestResult": {"summary": "ticket is open"},
                        },
                    )
                ],
            )
        )

        runtime_messages = build_initial_runtime_messages(request)
        active_playbook_message = runtime_messages[0]

        self.assertIn("ACTIVE_PLAYBOOK_SUMMARY", active_playbook_message.content)
        self.assertIn('"playbookId": "pb-active"', active_playbook_message.content)
        self.assertIn('"summary": "ticket is open"', active_playbook_message.content)
        self.assertIn('"runId": "run-secret-1"', active_playbook_message.content)

    def test_should_add_knowledge_lookup_instruction_when_knowledge_is_enabled(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                currentOwner={
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "knowledgeEnabled": True,
                    "knowledgeBaseId": "kb-1",
                    "knowledgeBinding": {
                        "knowledgeBaseId": "kb-1",
                        "knowledgeBaseName": "Refund Knowledge",
                        "knowledgeReleaseId": "kr-1",
                        "knowledgeReleaseVersion": "1.0.0",
                        "snapshotId": "snapshot-1",
                        "defaultTopK": 5,
                        "retrievalMode": "HYBRID",
                        "minScore": 0.1,
                    },
                    "allowedActions": ["REPLY"],
                }
            )
        )

        instruction = build_system_instruction(request)

        self.assertNotIn("Knowledge binding:", instruction)
        self.assertNotIn("Refund Knowledge", instruction)
        self.assertIn(
            "For factual questions about enterprises, products, policies, or other domain facts, query the knowledge base first; do not answer from pretrained knowledge.",
            instruction,
        )

    def test_should_not_add_knowledge_lookup_instruction_when_knowledge_is_disabled(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                currentOwner={
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "knowledgeEnabled": False,
                    "knowledgeBaseId": "kb-1",
                    "knowledgeBinding": {
                        "knowledgeBaseId": "kb-1",
                        "knowledgeBaseName": "Refund Knowledge",
                        "knowledgeReleaseId": "kr-1",
                        "knowledgeReleaseVersion": "1.0.0",
                        "snapshotId": "snapshot-1",
                        "defaultTopK": 5,
                        "retrievalMode": "HYBRID",
                        "minScore": 0.1,
                    },
                    "allowedActions": ["REPLY"],
                }
            )
        )

        instruction = build_system_instruction(request)

        self.assertNotIn("Knowledge binding:", instruction)
        self.assertNotIn("For factual questions about enterprises, products", instruction)

    def test_should_keep_action_handles_and_structured_runtime_context_in_rendered_prompt(self) -> None:
        request = AgentTurnRequest.model_validate(
            _request_payload(
                currentOwner={
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "systemPrompt": "Check refund eligibility before answering.",
                    "allowedActions": ["REPLY", "RUN_PLAYBOOK"],
                    "switchableOwnerAgentIds": ["agent-b"],
                    "playbookIds": ["pb-1"],
                    "skills": [
                        {
                            "resourceId": "skill-1",
                            "resourceName": "Refund Skill",
                            "resourceVersionId": "skill-ver-1",
                            "resourceVersion": "1.0.0",
                            "skillName": "Refund Policy",
                            "skillDesc": "Refund constraints.",
                            "skillPrompt": "Check order status first.",
                        }
                    ],
                    "tools": [
                        {
                            "resourceId": "tool-1",
                            "resourceName": "Ticket Tool",
                            "resourceVersionId": "tool-ver-1",
                            "resourceVersion": "1.0.0",
                            "operations": [
                                {
                                    "name": "create_ticket",
                                    "description": "Create a support ticket.",
                                    "inputSchema": "{}",
                                    "outputSchema": "{}",
                                }
                            ],
                        }
                    ],
                },
                trigger={
                    "triggerType": "PLAYBOOK_COMPLETED",
                    "turnId": "turn-1",
                    "eventId": "evt-2",
                    "payload": {"customerId": "customer-1", "playbookRunId": "run-1", "status": "SUCCEEDED"},
                },
                messages=[],
                contextEntries=[
                    _context_entry(
                        "evt-1",
                        "SESSION_EVENT",
                        1,
                        "2026-04-19T00:00:01Z",
                        {"eventType": "PLAYBOOK_STARTED", "payload": {"customerId": "customer-1", "runId": "run-1"}},
                    ),
                    _context_entry(
                        "shared-state-snapshot:session-1:1",
                        "SHARED_STATE_SNAPSHOT",
                        1,
                        "2026-04-19T00:00:02Z",
                        {"sharedState": {"customerId": "customer-1", "knownPreference": "email"}},
                    ),
                ],
            )
        )

        rendered_messages = render_openai_streaming_messages(
            build_system_instruction(request),
            request.currentOwner.systemPrompt.strip(),
            build_initial_runtime_messages(request),
        )
        rendered_prompt = "\n\n".join(str(message.get("content") or "") for message in rendered_messages)

        self.assertEqual("system", rendered_messages[0]["role"])
        self.assertEqual("user", rendered_messages[1]["role"])
        self.assertEqual(
            "<system-reminder>Check refund eligibility before answering.</system-reminder>",
            rendered_messages[1]["content"],
        )
        self.assertFalse(any(message["role"] == "system" for message in rendered_messages[1:]))
        self.assertIn("customer-1", rendered_prompt)
        self.assertIn("run-1", rendered_prompt)
        self.assertIn("knownPreference", rendered_prompt)
        self.assertNotIn("agent-b", rendered_prompt)
        self.assertNotIn("skill-ver-1", rendered_prompt)
        self.assertNotIn("tool-1", rendered_prompt)
        self.assertNotIn("Create a support ticket.", rendered_prompt)


if __name__ == "__main__":
    unittest.main()
