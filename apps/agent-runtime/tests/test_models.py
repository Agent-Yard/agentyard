import unittest

from pydantic import ValidationError

from lynxus_agent_runtime.models import AgentDecision, ToolDescriptor


def _text_message_input(text: str) -> dict:
    return {
        "blocks": [{"type": "TEXT", "text": text}],
        "metadata": {},
    }


class AgentDecisionModelTest(unittest.TestCase):
    def test_run_playbook_requires_playbook_input(self) -> None:
        with self.assertRaises(ValidationError):
            AgentDecision.model_validate(
                {
                    "action": "RUN_PLAYBOOK",
                    "playbookId": "pb-1",
                }
            )

    def test_switch_owner_rejects_playbook_fields(self) -> None:
        decision = AgentDecision.model_validate(
            {
                "action": "SWITCH_OWNER",
                "targetAgentId": "agent-b",
                "playbookInput": {"unexpected": True},
            }
        )
        self.assertEqual(decision.action, "SWITCH_OWNER")
        self.assertEqual(decision.playbookInput, {"unexpected": True})

    def test_no_reply_allows_accompanying_reply_for_soft_validation(self) -> None:
        decision = AgentDecision.model_validate(
            {
                "action": "NO_REPLY",
                "accompanyingMessage": _text_message_input("should not block runtime parsing"),
            }
        )
        self.assertEqual(decision.action, "NO_REPLY")
        self.assertEqual(decision.accompanyingMessage.blocks[0].text, "should not block runtime parsing")

    def test_reply_allows_accompanying_reply_for_soft_validation(self) -> None:
        decision = AgentDecision.model_validate(
            {
                "action": "REPLY",
                "replyMessage": _text_message_input("hello"),
                "accompanyingMessage": _text_message_input("should not block runtime parsing"),
            }
        )
        self.assertEqual(decision.action, "REPLY")
        self.assertEqual(decision.replyMessage.blocks[0].text, "hello")
        self.assertEqual(decision.accompanyingMessage.blocks[0].text, "should not block runtime parsing")

    def test_tool_connector_release_shape_reads_account_snapshot(self) -> None:
        tool = ToolDescriptor.model_validate(
            {
                "resourceId": "tool-1",
                "resourceName": "Ticket Tool",
                "resourceVersionId": "tool-ver-1",
                "resourceVersion": "1.0.0",
                "operations": [],
                "connector": {
                    "connectorType": "simple-http",
                    "accountSnapshot": {
                        "accountId": "integration-account-1",
                        "externalSecretRef": "vault://tool-secret",
                    },
                    "timeoutSeconds": 15,
                    "retryPolicy": "NONE",
                    "config": {},
                    "operationMappings": {},
                },
            }
        )

        self.assertEqual(tool.connector.accountSnapshot.accountId, "integration-account-1")
        self.assertEqual(tool.connector.accountSnapshot.externalSecretRef, "vault://tool-secret")
        self.assertFalse(hasattr(tool.connector, "accountId"))

    def test_tool_connector_release_shape_does_not_require_account_snapshot(self) -> None:
        tool = ToolDescriptor.model_validate(
            {
                "resourceId": "tool-1",
                "resourceName": "Ticket Tool",
                "resourceVersionId": "tool-ver-1",
                "resourceVersion": "1.0.0",
                "operations": [],
                "connector": {
                    "connectorType": "simple-http",
                    "timeoutSeconds": 15,
                    "retryPolicy": "NONE",
                    "config": {},
                    "operationMappings": {},
                },
            }
        )

        self.assertIsNone(tool.connector.accountSnapshot)

    def test_tool_connector_release_shape_rejects_legacy_top_level_account_id(self) -> None:
        with self.assertRaises(ValidationError):
            ToolDescriptor.model_validate(
                {
                    "resourceId": "tool-1",
                    "resourceName": "Ticket Tool",
                    "resourceVersionId": "tool-ver-1",
                    "resourceVersion": "1.0.0",
                    "operations": [],
                    "connector": {
                        "connectorType": "simple-http",
                        "accountId": "integration-account-legacy",
                        "timeoutSeconds": 15,
                        "retryPolicy": "NONE",
                        "config": {},
                        "operationMappings": {},
                    },
                }
            )
