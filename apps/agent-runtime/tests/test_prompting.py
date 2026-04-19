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
        self.assertEqual(bundle.runtime_messages[-1]["role"], "user")
        self.assertEqual(bundle.runtime_messages[-1]["content"], "hello")
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

        shared_state_payload = json.loads(bundle.runtime_messages[1]["content"].split(":\n", 1)[1])
        recent_event_payload = json.loads(bundle.runtime_messages[2]["content"].split(":\n", 1)[1])
        self.assertTrue(shared_state_payload["truncated"])
        self.assertEqual(len(recent_event_payload), 2)
        self.assertEqual([item["eventId"] for item in recent_event_payload], ["evt-4", "evt-5"])
