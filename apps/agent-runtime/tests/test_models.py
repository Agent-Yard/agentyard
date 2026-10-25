import unittest
import pytest
from pydantic import ValidationError

from agentyard_agent_runtime.models import ActivationRuntime, ActivationStart, ActivationOutcome, AgentProfile, ToolDescriptor, ToolRunNodeRequest
from runtime_fixtures import request_payload, _retry_policy


class ConnectorModelTest(unittest.TestCase):
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



@pytest.mark.parametrize("field", ["ownershipEpoch", "currentOwner", "assistantId", "assistantReleaseVersion", "taskExecutionId", "transcriptBootstrap"])
def test_activation_rejects_removed_execution_dimensions(field):
    payload = request_payload()
    payload[field] = "forged"
    with pytest.raises(ValidationError):
        ActivationRuntime.model_validate(payload)


def test_profile_is_configuration_and_has_no_instance_identity_or_permissions():
    profile = request_payload()["agent"]
    for field in ["agentId", "allowedActions", "switchableOwnerAgentIds", "canOwnSession"]:
        with pytest.raises(ValidationError):
            AgentProfile.model_validate({**profile, field: "forged"})


def test_activation_start_cannot_supply_forged_context():
    with pytest.raises(ValidationError):
        ActivationStart.model_validate({"activationId": "a", "context": {"admin": True}})


def test_outcome_has_no_lifecycle_action_and_empty_memory_means_no_patch():
    value = ActivationOutcome(success=True, completion="COMPLETED")
    assert value.workMemory == {}
    with pytest.raises(ValidationError):
        ActivationOutcome(success=True, decision={"action": "SWITCH_OWNER"})
    with pytest.raises(ValidationError):
        ActivationOutcome(success=False)


def test_tool_node_request_contains_no_caller_chosen_identity_or_profile():
    value = {"toolRunId": "run-1", "nodeKey": "node", "executionId": "run:node:1", "input": {}}
    assert ToolRunNodeRequest.model_validate(value).executionId == "run:node:1"
    with pytest.raises(ValidationError):
        ToolRunNodeRequest.model_validate({**value, "agent": {"name": "admin"}})
