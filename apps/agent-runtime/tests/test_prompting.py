import os
import unittest
import json

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.decisioning import execute_agent_turn
from lynxus_agent_runtime.models import AgentTurnRequest
from lynxus_agent_runtime.prompting import build_prompt_bundle


class AgentRuntimePromptingTest(unittest.TestCase):
    def test_should_build_prompt_bundle_with_runtime_context(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY", "RUN_PLAYBOOK"],
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
                    "payload": {"text": "hello"},
                },
                "recentEvents": [
                    {
                        "eventId": "evt-0",
                        "sessionId": "session-1",
                        "sequence": 1,
                        "eventType": "USER_MESSAGE",
                        "actorType": "USER",
                        "payload": {"text": "hi"},
                    }
                ],
            }
        )

        bundle = build_prompt_bundle(request)

        self.assertIn("allowedActions", bundle.capabilities)
        self.assertEqual(bundle.capabilities["playbookIds"], ["pb-1"])
        self.assertIn("availableSkills", bundle.capabilities)
        self.assertEqual(bundle.runtime_messages[2].kind, "user_turn")
        self.assertEqual(bundle.runtime_messages[2].content, "hi")
        self.assertEqual(bundle.runtime_messages[-1].kind, "user_turn")
        self.assertEqual(bundle.runtime_messages[-1].content, "hello")
        self.assertIn("sharedState", bundle.response_contract)
        self.assertIn("skillReads", bundle.response_contract)

    def test_should_fallback_to_switch_owner_decision_when_provider_not_configured(self) -> None:
        os.environ.pop("LYNXUS_OPENAI_COMPATIBLE_BASE_URL", None)
        os.environ.pop("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID", None)
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "2026.04.19",
                "currentOwner": {
                    "agentId": "agent-a",
                    "name": "Agent A",
                    "role": "support",
                    "responsibility": "help the customer",
                    "allowedActions": ["REPLY", "SWITCH_OWNER"],
                    "switchableOwnerAgentIds": ["agent-b"],
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
                "availablePlaybooks": [],
                "sharedState": {},
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "eventId": "evt-1",
                    "payload": {"text": "/switch agent-b"},
                },
                "recentEvents": [],
            }
        )

        result, _ = execute_agent_turn(request)

        self.assertEqual(result.decision.action, "SWITCH_OWNER")
        self.assertEqual(result.decision.targetAgentId, "agent-b")
        self.assertEqual(result.sharedState["currentOwnerAgentId"], "agent-a")

    def test_should_hide_knowledge_binding_from_prompt_when_knowledge_is_disabled(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
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
                    "payload": {"text": "hello"},
                },
                "recentEvents": [],
            }
        )

        bundle = build_prompt_bundle(request)

        self.assertIsNone(bundle.capabilities["knowledgeBinding"])

    def test_should_apply_memory_window_and_truncate_shared_state_view(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
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
                    "payload": {"text": "latest"},
                },
                "recentEvents": [
                    {
                        "eventId": f"evt-{index}",
                        "sessionId": "session-1",
                        "sequence": index,
                        "eventType": "USER_MESSAGE",
                        "actorType": "USER",
                        "payload": {"text": f"msg-{index}"},
                    }
                    for index in range(1, 6)
                ],
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
                "recentEvents": [
                    {
                        "eventId": "evt-1",
                        "sessionId": "session-1",
                        "sequence": 1,
                        "eventType": "USER_MESSAGE",
                        "actorType": "USER",
                        "payload": {"text": "我想退款"},
                    },
                    {
                        "eventId": "evt-2",
                        "sessionId": "session-1",
                        "sequence": 2,
                        "eventType": "OWNER_REPLY",
                        "actorType": "AGENT",
                        "payload": {"text": "我来帮你处理"},
                    },
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
