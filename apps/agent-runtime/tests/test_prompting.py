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
    build_prompt_bundle,
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

    def test_should_build_prompt_bundle_with_runtime_context(self) -> None:
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

        bundle = build_prompt_bundle(request)

        self.assertIn("Function tools define the current owner capability boundary", bundle.capability_summary)
        self.assertNotIn("Knowledge binding:", bundle.capability_summary)
        self.assertNotIn("For factual questions about enterprises, products", bundle.capability_summary)
        self.assertIn("Use function tool names, parameter schemas, and parameter descriptions", bundle.capability_summary)
        self.assertEqual(bundle.runtime_messages[2].kind, "user_turn")
        self.assertEqual(bundle.runtime_messages[2].content, "hi")
        self.assertEqual(bundle.runtime_messages[-1].kind, "user_turn")
        self.assertEqual(bundle.runtime_messages[-1].content, "hello")
        self.assertIn("You are the current session owner agent.", bundle.instruction)
        self.assertIn("Owner identity: Agent A", bundle.instruction)

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

        bundle = build_prompt_bundle(request)
        shared_state_message = next(
            message for message in bundle.runtime_messages if message.privacy_source == "shared_state_slice"
        )

        self.assertIn('"sharedState": {}', shared_state_message.content)
        self.assertEqual(PrivacyStrategy.SKIP, shared_state_message.privacy_strategy)

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

        rendered_prompt = "\n\n".join(
            str(message.get("content") or "")
            for message in render_openai_streaming_messages(build_prompt_bundle(request))
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

        bundle = build_prompt_bundle(request)
        active_playbook_message = next(
            message for message in bundle.runtime_messages if message.content.startswith("Active playbook summary:")
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

        bundle = build_prompt_bundle(request)

        self.assertNotIn("Knowledge binding:", bundle.capability_summary)
        self.assertNotIn("Refund Knowledge", bundle.capability_summary)
        self.assertIn(
            "For factual questions about enterprises, products, policies, or other domain facts, query the knowledge base first; do not answer from pretrained knowledge.",
            bundle.capability_summary,
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

        bundle = build_prompt_bundle(request)

        self.assertNotIn("Knowledge binding:", bundle.capability_summary)
        self.assertNotIn("For factual questions about enterprises, products", bundle.capability_summary)

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

        bundle = build_prompt_bundle(request)

        shared_state_payload = json.loads(bundle.runtime_messages[1].content.split(":\n", 1)[1])
        self.assertTrue(shared_state_payload["truncated"])
        self.assertEqual([message.kind for message in bundle.runtime_messages[2:4]], ["user_turn", "user_turn"])
        self.assertEqual([message.content for message in bundle.runtime_messages[2:4]], ["msg-3", "msg-4"])

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

        bundle = build_prompt_bundle(request)

        self.assertEqual(bundle.runtime_messages[2].kind, "user_turn")
        self.assertEqual(bundle.runtime_messages[2].content, "我想退款")
        self.assertEqual(bundle.runtime_messages[3].kind, "assistant_turn")
        self.assertEqual(bundle.runtime_messages[3].content, "我来帮你处理")
        self.assertEqual(bundle.runtime_messages[4].kind, "system_event")
        self.assertIn("PLAYBOOK_STARTED", bundle.runtime_messages[4].content)
        self.assertEqual(bundle.runtime_messages[-1].kind, "system_event")
        self.assertIn('"status": "SUCCEEDED"', bundle.runtime_messages[-1].content)
