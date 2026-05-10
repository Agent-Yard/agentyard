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

    def test_no_op_allows_empty_reply(self) -> None:
        decision = AgentDecision.model_validate(
            {
                "action": "NO_OP",
            }
        )
        self.assertEqual(decision.action, "NO_OP")

    def test_rejects_removed_accompanying_message(self) -> None:
        with self.assertRaises(ValidationError):
            AgentDecision.model_validate(
                {
                    "action": "REPLY",
                    "replyMessage": _text_message_input("hello"),
                    "accompanyingMessage": _text_message_input("removed"),
                }
            )

    def test_security_block_action_is_valid(self) -> None:
        decision = AgentDecision.model_validate({"action": "SECURITY_BLOCK"})
        self.assertEqual(decision.action, "SECURITY_BLOCK")

    def test_human_handoff_operator_reason_is_trimmed_and_action_scoped(self) -> None:
        decision = AgentDecision.model_validate(
            {
                "action": "SESSION_HUMAN_HANDOFF",
                "operatorReason": "  billing escalation  ",
            }
        )
        self.assertEqual("billing escalation", decision.operatorReason)

        blank_reason = AgentDecision.model_validate(
            {
                "action": "SESSION_HUMAN_HANDOFF",
                "operatorReason": "  ",
            }
        )
        self.assertIsNone(blank_reason.operatorReason)

        with self.assertRaises(ValidationError):
            AgentDecision.model_validate(
                {
                    "action": "REPLY",
                    "replyMessage": _text_message_input("hello"),
                    "operatorReason": "billing escalation",
                }
            )

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
                    "retryPolicy": _retry_policy(),
                    "config": {},
                    "operationMappings": {},
                },
            }
        )

        self.assertEqual(tool.connector.accountSnapshot.accountId, "integration-account-1")
        self.assertEqual(tool.connector.accountSnapshot.externalSecretRef, "vault://tool-secret")
        self.assertEqual(tool.connector.retryPolicy.mode, "NONE")
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
                    "retryPolicy": _retry_policy(),
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
                        "retryPolicy": _retry_policy(),
                        "config": {},
                        "operationMappings": {},
                    },
                }
            )

    def test_tool_connector_release_shape_rejects_legacy_retry_policy_string(self) -> None:
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
                        "timeoutSeconds": 15,
                        "retryPolicy": "NONE",
                        "config": {},
                        "operationMappings": {},
                    },
                }
            )


def _retry_policy() -> dict:
    return {
        "mode": "NONE",
        "maxAttempts": 1,
        "initialDelayMs": 0,
        "maxDelayMs": 0,
        "backoffMultiplier": 1.0,
        "retryableCategories": [],
        "retryableErrorCodes": [],
    }
