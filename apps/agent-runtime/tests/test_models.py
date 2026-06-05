import unittest

from pydantic import ValidationError

from agentyard_agent_runtime.models import AgentDecision, AgentTurnRequest, ToolDescriptor


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

    def test_agent_turn_request_accepts_delta_contract(self) -> None:
        request = AgentTurnRequest.model_validate(
            {
                "sessionId": "session-1",
                "turnId": "turn-1",
                "turnExecutionId": "turn-1:exec-1",
                "replyMessageId": "session-message-reply-1",
                "ownershipEpoch": 1,
                "assistantId": "assistant-1",
                "assistantReleaseVersion": "1.0.0",
                "currentOwner": _agent_config("agent-1"),
                "availableAgents": [_agent_config("agent-1")],
                "availablePlaybooks": [],
                "sharedState": {},
                "effectivePrivacyMappingEnabled": False,
                "trigger": {
                    "triggerType": "USER_MESSAGE",
                    "turnId": "turn-1",
                    "eventId": "event-1",
                    "payload": {},
                },
                "messages": [_session_message("msg-1", 1, "turn-1", 0, "hello")],
                "contextEntries": [
                    {
                        "entryId": "event-1",
                        "entryType": "SESSION_EVENT",
                        "revision": 1,
                        "occurredAt": "2026-05-01T00:00:00Z",
                        "data": {"eventType": "HUMAN_RESUME_RECEIVED"},
                    }
                ],
                "transcriptBootstrap": True,
            }
        )

        self.assertEqual("turn-1", request.turnId)
        self.assertEqual("session-message-reply-1", request.replyMessageId)
        self.assertEqual(["msg-1"], [message.messageId for message in request.messages])
        self.assertEqual("SESSION_EVENT", request.contextEntries[0].entryType)
        self.assertTrue(request.transcriptBootstrap)

    def test_agent_turn_request_rejects_removed_recent_window_fields(self) -> None:
        payload = {
            "sessionId": "session-1",
            "turnId": "turn-1",
            "turnExecutionId": "turn-1:exec-1",
            "replyMessageId": "session-message-reply-1",
            "assistantId": "assistant-1",
            "assistantReleaseVersion": "1.0.0",
            "currentOwner": _agent_config("agent-1"),
            "trigger": {
                "triggerType": "USER_MESSAGE",
                "eventId": "event-1",
                "triggerMessageId": "msg-1",
            },
            "recentMessages": [_session_message("msg-1", 1, "turn-1", 0, "hello")],
            "recentEvents": [],
        }

        with self.assertRaises(ValidationError) as failure:
            AgentTurnRequest.model_validate(payload)

        errors = failure.exception.errors()
        self.assertTrue(any(error["loc"] == ("recentMessages",) and error["type"] == "extra_forbidden" for error in errors))
        self.assertTrue(any(error["loc"] == ("recentEvents",) and error["type"] == "extra_forbidden" for error in errors))
        self.assertTrue(
            any(error["loc"] == ("trigger", "triggerMessageId") and error["type"] == "extra_forbidden" for error in errors)
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


def _agent_config(agent_id: str) -> dict:
    return {
        "agentId": agent_id,
        "name": "Owner Agent",
        "role": "support",
        "responsibility": "Handle the session",
    }


def _session_message(message_id: str, sequence: int, turn_id: str, turn_index: int, text: str) -> dict:
    return {
        "messageId": message_id,
        "sessionId": "session-1",
        "sequence": sequence,
        "turnId": turn_id,
        "turnIndex": turn_index,
        "producerType": "EXTERNAL",
        "externalMessageId": None,
        "clientMessageId": f"client-{message_id}",
        "occurredAt": "2026-05-01T00:00:00Z",
        "role": "USER",
        "sender": {
            "senderType": "CUSTOMER",
            "senderId": "customer-1",
            "senderName": "Customer",
        },
        "status": "SENT",
        "blocks": [{"type": "TEXT", "text": text}],
        "metadata": {},
        "relatedPlaybookRunId": None,
        "relatedOwnerAgentId": None,
        "sourceEventId": None,
        "createdAt": "2026-05-01T00:00:01Z",
        "updatedAt": "2026-05-01T00:00:01Z",
    }
