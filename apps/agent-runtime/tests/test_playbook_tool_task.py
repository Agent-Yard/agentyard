import os
import unittest
from unittest.mock import patch

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.models import PlaybookToolTaskRequest
from lynxus_agent_runtime.tooling import execute_playbook_tool_task


def _request_payload() -> dict:
    return {
        "sessionId": "session-1",
        "playbookRunId": "run-1",
        "playbookId": "pb-1",
        "nodeKey": "tool-node",
        "nodeName": "Create Ticket",
        "ownerAgent": {
            "agentId": "agent-a",
            "name": "Agent A",
            "role": "support",
            "responsibility": "help the customer",
            "tools": [
                {
                    "resourceId": "tool-1",
                    "resourceName": "Ticket Tool",
                    "resourceVersionId": "rv-tool-1",
                    "resourceVersion": "1.0.0",
                    "providerType": "HTTP",
                    "authType": "SERVICE_ACCOUNT",
                    "operations": [
                        {
                            "name": "create_ticket",
                            "description": "Create a ticket",
                            "inputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"},"note":{"type":"string"}},"additionalProperties":false}',
                            "outputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"},"routeKey":{"type":"string"}},"additionalProperties":true}',
                        }
                    ],
                    "http": {"endpoint": "https://tool.example/create", "method": "POST"},
                }
            ],
        },
        "toolId": "tool-1",
        "toolOperation": "create_ticket",
        "input": {"ticketId": "t-100", "note": "customer asked for help"},
        "config": {
            "arguments": {
                "ticketId": "{{input.ticketId}}",
                "note": "{{input.note}}",
            },
            "outputKey": "workflow.ticket",
        },
    }


class PlaybookToolTaskExecutionTest(unittest.TestCase):
    @patch("lynxus_agent_runtime.tooling._call_http_tool")
    def test_should_execute_playbook_tool_task_and_map_output(self, mock_call_http_tool) -> None:
        mock_call_http_tool.return_value = {
            "ticketId": "t-100",
            "routeKey": "success",
            "status": "created",
        }
        request = PlaybookToolTaskRequest.model_validate(_request_payload())

        result = execute_playbook_tool_task(request)

        self.assertEqual(result.routeKey, "success")
        self.assertEqual(result.statePatch["workflow.ticket"]["ticketId"], "t-100")
