import os
import unittest
import json
import subprocess
import sys
from pathlib import Path

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.models import AgentTurnRequest
from lynxus_agent_runtime.privacy_contracts import PrivacyStrategy
from lynxus_agent_runtime.prompting import (
    build_initial_runtime_messages,
    build_system_instruction,
    build_turn_input_messages,
    render_openai_streaming_messages,
)


def _text_message(message_id: str, sequence: int, role: str, text: str) -> dict:
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

    def test_should_build_prompt_instruction_and_runtime_context(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
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
                "availablePlaybooks": [
                    {
                        "playbookId": "pb-1",
                        "name": "Playbook 1",
                    }
                ],
                "sharedState": {"knownPreference": "email"},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-2",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [
                    _text_message("msg-1", 1, "USER", "hi"),
                    _text_message("msg-2", 2, "USER", "hello"),
                ],
                "recentEvents": [],
            }
        )

        instruction = build_system_instruction(request)
        runtime_messages = build_initial_runtime_messages(request)

        self.assertIn("Function tools define the current owner capability boundary", instruction)
        self.assertNotIn("Knowledge binding:", instruction)
        self.assertNotIn("For factual questions about enterprises, products", instruction)
        self.assertIn("Use function tool names, parameter schemas, and parameter descriptions", instruction)
        self.assertIn("Use exactly one customer-visible output channel for the same reply content", instruction)
        self.assertIn("For ordinary text or Markdown replies, write the reply directly as assistant content", instruction)
        self.assertIn("Never describe tool calls, accepted tool results, state updates, message block writes", instruction)
        self.assertIn("If a message block tool already wrote the complete customer reply", instruction)
        self.assertFalse(any(message.content.startswith("Session trigger:") for message in runtime_messages))
        self.assertEqual(runtime_messages[0].kind, "user_turn")
        self.assertEqual(runtime_messages[0].content, "hi")
        self.assertEqual(runtime_messages[1].privacy_source, "shared_state_slice")
        self.assertEqual(runtime_messages[-1].kind, "user_turn")
        self.assertEqual(runtime_messages[-1].content, "hello")
        self.assertIn("You are the current session owner agent.", instruction)
        self.assertIn("Owner identity: Agent A", instruction)

    def test_should_skip_privacy_for_empty_shared_state_slice(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY"],
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-1",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
                "recentEvents": [],
            }
        )

        runtime_messages = build_initial_runtime_messages(request)
        shared_state_message = next(
            message for message in runtime_messages if message.privacy_source == "shared_state_slice"
        )

        self.assertIn('"sharedState": {}', shared_state_message.content)
        self.assertEqual(PrivacyStrategy.SKIP, shared_state_message.privacy_strategy)

    def test_should_not_repeat_shared_state_in_turn_input_messages(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY"],
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {"knownPreference": "email"},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-1",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
                "recentEvents": [],
            }
        )

        turn_input_messages = build_turn_input_messages(request)

        self.assertFalse(any(message.privacy_source == "shared_state_slice" for message in turn_input_messages))
        self.assertEqual(["user_turn"], [message.kind for message in turn_input_messages])
        self.assertEqual("hello", turn_input_messages[0].content)

    def test_should_reject_user_trigger_without_resolved_message(self) -> None:
        base_payload = {
            "sessionId": "session-1",
            "replyMessageId": "session-message-reply-1",
            "assistantId": "assistant-1",
            "assistantReleaseVersion": "2026.04.19",
            "currentOwner": {
                "agentId": "agent-a",
                "name": "Agent A",
                "role": "support",
                "responsibility": "help the customer",
                "allowedActions": ["REPLY"],
            },
            "availableAgents": [],
            "availablePlaybooks": [],
            "sharedState": {},
            "trigger": {
                "triggerType": "USER_MESSAGE",
                "eventId": "evt-1",
                "triggerMessageId": "msg-missing",
                "payload": {"text": "hello"},
            },
            "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
            "recentEvents": [],
        }

        request = AgentTurnRequest.model_validate(base_payload)
        with self.assertRaisesRegex(ValueError, "USER_MESSAGE triggerMessageId does not resolve"):
            build_initial_runtime_messages(request)

        payload_without_trigger_message_id = {
            **base_payload,
            "trigger": {
                "triggerType": "USER_MESSAGE",
                "eventId": "evt-1",
                "payload": {"text": "hello"},
            },
        }
        request_without_trigger_message_id = AgentTurnRequest.model_validate(payload_without_trigger_message_id)
        with self.assertRaisesRegex(ValueError, "USER_MESSAGE trigger requires triggerMessageId"):
            build_initial_runtime_messages(request_without_trigger_message_id)

    def test_should_keep_action_handles_and_structured_runtime_context_in_rendered_prompt(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
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
                "availableAgents": [
                    {
                        "agentId": "agent-b",
                        "name": "Agent B",
                        "role": "ops",
                        "responsibility": "take over escalations",
                        "allowedActions": ["REPLY"],
                    }
                ],
                "availablePlaybooks": [
                    {
                        "playbookId": "pb-1",
                        "name": "Refund Playbook",
                        "description": "Handle refunds.",
                    }
                ],
                "activePlaybook": {
                    "runId": "run-1",
                    "playbookId": "pb-1",
                    "playbookName": "Refund Playbook",
                    "status": "RUNNING",
                    "latestResult": {"customerId": "customer-1"},
                },
                "sharedState": {"customerId": "customer-1", "knownPreference": "email"},
                "trigger": {
                    "triggerType": "PLAYBOOK_COMPLETED",
                    "eventId": "evt-2",
                    "payload": {"customerId": "customer-1", "playbookRunId": "run-1", "status": "SUCCEEDED"},
                },
                "recentMessages": [
                    {
                        **_text_message("msg-1", 1, "SYSTEM", ""),
                        "blocks": [],
                    }
                ],
                "recentEvents": [
                    {
                        "eventId": "evt-1",
                        "sessionId": "session-1",
                        "sequence": 1,
                        "eventType": "PLAYBOOK_STARTED",
                        "actorType": "AGENT",
                        "actorId": "agent-a",
                        "payload": {"customerId": "customer-1", "runId": "run-1"},
                        "relatedPlaybookRunId": "run-1",
                        "relatedOwnerAgentId": "agent-a",
                    }
                ],
            }
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
        system_reminder_messages = [
            message
            for message in rendered_messages[1:]
            if str(message.get("content") or "").startswith("<system-reminder>")
        ]
        self.assertGreaterEqual(len(system_reminder_messages), 4)
        self.assertTrue(
            all(
                str(message.get("content") or "").endswith("</system-reminder>")
                for message in system_reminder_messages
            )
        )
        for runtime_id in (
            "session-1",
            "assistant-1",
            "evt-1",
            "evt-2",
            "msg-1",
        ):
            self.assertNotIn(runtime_id, rendered_prompt)
        self.assertIn("customer-1", rendered_prompt)
        self.assertIn("run-1", rendered_prompt)
        self.assertIn("pb-1", rendered_prompt)
        self.assertNotIn("agent-b", rendered_prompt)
        self.assertNotIn("skill-ver-1", rendered_prompt)
        self.assertNotIn("tool-1", rendered_prompt)
        self.assertNotIn("tool-ver-1", rendered_prompt)
        self.assertNotIn("Create a support ticket.", rendered_prompt)
        self.assertNotIn('"eventId"', rendered_prompt)
        self.assertIn('"customerId"', rendered_prompt)
        self.assertIn("Use function tools for context reads", rendered_prompt)
        self.assertIn("Refund Playbook", rendered_prompt)
        self.assertIn("knownPreference", rendered_prompt)

    def test_should_render_active_playbook_summary_without_path_filtering(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY"],
                    "playbookIds": ["pb-active"],
                },
                "availableAgents": [],
                "availablePlaybooks": [
                    {
                        "playbookId": "pb-active",
                        "name": "Active Refund Flow",
                    }
                ],
                "activePlaybook": {
                    "runId": "run-secret-1",
                    "playbookId": "pb-active",
                    "playbookName": "Active Refund Flow",
                    "status": "RUNNING",
                    "latestResult": {
                        "customerId": "customer-secret-1",
                        "ticketId": "ticket-secret-1",
                        "summary": "ticket is open",
                    },
                },
                "sharedState": {},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-1",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
                "recentEvents": [],
            }
        )

        runtime_messages = build_initial_runtime_messages(request)
        active_playbook_message = next(
            message for message in runtime_messages if message.content.startswith("Active playbook summary:")
        )

        self.assertIn('"playbookId": "pb-active"', active_playbook_message.content)
        self.assertIn('"summary": "ticket is open"', active_playbook_message.content)
        self.assertIn('"customerId": "customer-secret-1"', active_playbook_message.content)
        self.assertIn('"ticketId": "ticket-secret-1"', active_playbook_message.content)
        self.assertIn('"runId": "run-secret-1"', active_playbook_message.content)

    def test_should_add_knowledge_lookup_instruction_when_knowledge_is_enabled(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
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
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-1",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
                "recentEvents": [],
            }
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
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
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
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "triggerMessageId": "msg-1",
                    "payload": {"text": "hello"},
                },
                "recentMessages": [_text_message("msg-1", 1, "USER", "hello")],
                "recentEvents": [],
            }
        )

        instruction = build_system_instruction(request)

        self.assertNotIn("Knowledge binding:", instruction)
        self.assertNotIn("For factual questions about enterprises, products", instruction)

    def test_should_apply_memory_window_and_truncate_shared_state_view(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "memoryWindowSize": 2,
                    "allowedActions": ["REPLY"],
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {f"key{i}": f"value{i}" for i in range(10)},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-5",
                    "triggerMessageId": "msg-5",
                    "payload": {"text": "latest"},
                },
                "recentMessages": [_text_message(f"msg-{index}", index, "USER", f"msg-{index}") for index in range(1, 6)],
                "recentEvents": [],
            }
        )

        runtime_messages = build_initial_runtime_messages(request)

        shared_state_message = next(
            message for message in runtime_messages if message.privacy_source == "shared_state_slice"
        )
        shared_state_payload = json.loads(shared_state_message.content.split(":\n", 1)[1])
        self.assertTrue(shared_state_payload["truncated"])
        self.assertEqual([message.kind for message in runtime_messages[0:2]], ["user_turn", "user_turn"])
        self.assertEqual([message.content for message in runtime_messages[0:2]], ["msg-3", "msg-4"])
        self.assertEqual(runtime_messages[2].privacy_source, "shared_state_slice")

    def test_should_render_recent_events_as_native_messages(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "replyMessageId": "session-message-reply-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY"],
                },
                "availableAgents": [],
                "availablePlaybooks": [],
                "sharedState": {},
                "trigger": {
                    "triggerType": "PLAYBOOK_COMPLETED",
                    "eventId": "evt-4",
                    "payload": {"playbookRunId": "run-1", "status": "SUCCEEDED"},
                },
                "recentMessages": [
                    _text_message("msg-1", 1, "USER", "我想退款"),
                    _text_message("msg-2", 2, "ASSISTANT", "我来帮你处理"),
                ],
                "recentEvents": [
                    {
                        "eventId": "evt-3",
                        "sessionId": "session-1",
                        "sequence": 3,
                        "eventType": "PLAYBOOK_STARTED",
                        "actorType": "AGENT",
                        "payload": {"runId": "run-1"},
                    },
                    {
                        "eventId": "evt-4",
                        "sessionId": "session-1",
                        "sequence": 4,
                        "eventType": "PLAYBOOK_COMPLETED",
                        "actorType": "SYSTEM",
                        "payload": {"playbookRunId": "run-1", "status": "SUCCEEDED"},
                    },
                ],
            }
        )

        runtime_messages = build_initial_runtime_messages(request)

        self.assertEqual(runtime_messages[0].kind, "user_turn")
        self.assertEqual(runtime_messages[0].content, "我想退款")
        self.assertEqual(runtime_messages[1].kind, "assistant_turn")
        self.assertEqual(runtime_messages[1].content, "我来帮你处理")
        self.assertEqual(runtime_messages[2].kind, "system_event")
        self.assertIn("PLAYBOOK_STARTED", runtime_messages[2].content)
        self.assertEqual(runtime_messages[3].privacy_source, "shared_state_slice")
        self.assertEqual(runtime_messages[-1].kind, "system_event")
        self.assertTrue(runtime_messages[-1].content.startswith("Session trigger event:"))
        self.assertIn('"triggerType": "PLAYBOOK_COMPLETED"', runtime_messages[-1].content)
        self.assertIn('"status": "SUCCEEDED"', runtime_messages[-1].content)
        self.assertEqual(
            1,
            sum(1 for message in runtime_messages if '"triggerType": "PLAYBOOK_COMPLETED"' in message.content),
        )
