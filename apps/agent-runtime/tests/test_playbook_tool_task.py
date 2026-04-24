import os
import unittest
from unittest.mock import patch
import hmac
import hashlib
import json

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.http_clients import reset_shared_http_client_registry
from lynxus_agent_runtime.models import PlaybookToolTaskRequest
from lynxus_agent_runtime.tooling import execute_playbook_tool_task


class _FakeResponse:
    def __init__(self, payload: dict) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


class _FakeClient:
    def __init__(self, request_log: list[dict]) -> None:
        self._request_log = request_log

    def get(self, url: str, **kwargs) -> _FakeResponse:
        self._request_log.append({"method": "GET", "url": url, **kwargs})
        return _FakeResponse(
            {
                "data": {
                    "accountId": "integration-account-1",
                    "connectorType": "BUSINESS_CODE_SECRET_HTTP",
                    "status": "ACTIVE",
                    "config": {},
                    "credential": {
                        "businessCode": "biz-001",
                        "secretKey": "secret-001",
                    },
                }
            }
        )

    def request(self, method: str, url: str, **kwargs) -> _FakeResponse:
        self._request_log.append({"method": method.upper(), "url": url, **kwargs})
        return _FakeResponse({"ticketId": "t-100", "routeKey": "success"})

    def post(self, url: str, **kwargs) -> _FakeResponse:
        self._request_log.append({"method": "POST", "url": url, **kwargs})
        return _FakeResponse({"ticketId": "t-100", "routeKey": "success"})

    def close(self) -> None:
        return None


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
                    "operations": [
                        {
                            "name": "create_ticket",
                            "description": "Create a ticket",
                            "inputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"},"note":{"type":"string"}},"additionalProperties":false}',
                            "outputSchema": '{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"},"routeKey":{"type":"string"}},"additionalProperties":true}',
                        }
                    ],
                    "connector": {
                        "connectorType": "SIMPLE_HTTP",
                        "accountId": None,
                        "timeoutSeconds": 15,
                        "retryPolicy": "NONE",
                        "config": {"baseUrl": "https://tool.example"},
                        "operationMappings": {
                            "create_ticket": {"method": "POST", "path": "/create", "requestPlacement": "JSON_BODY"}
                        },
                    },
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
    def tearDown(self) -> None:
        reset_shared_http_client_registry()

    @patch("lynxus_agent_runtime.tooling._call_connector_tool")
    def test_should_execute_playbook_tool_task_and_map_output(self, mock_call_connector_tool) -> None:
        mock_call_connector_tool.return_value = {
            "ticketId": "t-100",
            "routeKey": "success",
            "status": "created",
        }
        request = PlaybookToolTaskRequest.model_validate(_request_payload())

        result = execute_playbook_tool_task(request)

        self.assertEqual(result.routeKey, "success")
        self.assertEqual(result.statePatch["workflow.ticket"]["ticketId"], "t-100")

    def test_business_code_secret_http_should_sign_payload_with_runtime_credential(self) -> None:
        payload = _request_payload()
        tool = payload["ownerAgent"]["tools"][0]
        tool["connector"] = {
            "connectorType": "BUSINESS_CODE_SECRET_HTTP",
            "accountId": "integration-account-1",
            "timeoutSeconds": 15,
            "retryPolicy": "NONE",
            "config": {
                "baseUrl": "https://vendor.example",
                "businessCodeField": "businessCode",
                "encryptedField": "encrypted",
            },
            "operationMappings": {
                "create_ticket": {"method": "POST", "path": "/tickets", "requestPlacement": "JSON_BODY"}
            },
        }
        request = PlaybookToolTaskRequest.model_validate(payload)
        request_log: list[dict] = []

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=lambda *args, **kwargs: _FakeClient(request_log)):
            result = execute_playbook_tool_task(request)

        signed_payload = request_log[1]["json"]
        expected_signature = hmac.new(
            b"secret-001",
            json.dumps({"ticketId": "t-100", "note": "customer asked for help"}, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        self.assertEqual(result.routeKey, "success")
        self.assertEqual(request_log[0]["url"], "http://127.0.0.1:8080/api/internal/integration/accounts/integration-account-1/credential")
        self.assertEqual(request_log[0]["headers"], {"Authorization": "Bearer test-internal-token"})
        self.assertEqual(request_log[1]["url"], "https://vendor.example/tickets")
        self.assertNotIn("headers", request_log[1])
        self.assertEqual(signed_payload["businessCode"], "biz-001")
        self.assertEqual(signed_payload["encrypted"], expected_signature)

    def test_business_code_secret_http_should_reject_account_type_mismatch(self) -> None:
        payload = _request_payload()
        tool = payload["ownerAgent"]["tools"][0]
        tool["connector"] = {
            "connectorType": "BUSINESS_CODE_SECRET_HTTP",
            "accountId": "integration-account-1",
            "timeoutSeconds": 15,
            "retryPolicy": "NONE",
            "config": {"baseUrl": "https://vendor.example"},
            "operationMappings": {
                "create_ticket": {"method": "POST", "path": "/tickets", "requestPlacement": "JSON_BODY"}
            },
        }
        request = PlaybookToolTaskRequest.model_validate(payload)

        with patch(
            "lynxus_agent_runtime.tooling._load_runtime_integration_account",
            return_value={
                "accountId": "integration-account-1",
                "connectorType": "SIMPLE_HTTP",
                "status": "ACTIVE",
                "config": {},
                "credential": {"businessCode": "biz-001", "secretKey": "secret-001"},
            },
        ):
            with self.assertRaisesRegex(ValueError, "connectorType must be BUSINESS_CODE_SECRET_HTTP"):
                execute_playbook_tool_task(request)

    def test_mcp_connector_should_not_send_internal_auth_by_default(self) -> None:
        payload = _request_payload()
        tool = payload["ownerAgent"]["tools"][0]
        tool["connector"] = {
            "connectorType": "MCP",
            "accountId": None,
            "timeoutSeconds": 15,
            "retryPolicy": "NONE",
            "config": {
                "serverName": "mcp-server",
                "transport": "STREAMABLE_HTTP",
                "connectionUri": "https://mcp.example/invoke",
                "namespace": "default.namespace",
            },
            "operationMappings": {"create_ticket": {"tool": "ticket.create"}},
        }
        request = PlaybookToolTaskRequest.model_validate(payload)
        request_log: list[dict] = []

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=lambda *args, **kwargs: _FakeClient(request_log)):
            result = execute_playbook_tool_task(request)

        self.assertEqual(result.routeKey, "success")
        self.assertEqual(request_log[0]["url"], "https://mcp.example/invoke")
        self.assertEqual(request_log[0]["headers"], {})

    def test_mcp_connector_should_send_internal_auth_when_explicitly_enabled(self) -> None:
        payload = _request_payload()
        tool = payload["ownerAgent"]["tools"][0]
        tool["connector"] = {
            "connectorType": "MCP",
            "accountId": None,
            "timeoutSeconds": 15,
            "retryPolicy": "NONE",
            "config": {
                "serverName": "mcp-server",
                "transport": "STREAMABLE_HTTP",
                "connectionUri": "https://mcp-gateway.internal/invoke",
                "namespace": "default.namespace",
                "internalAuthEnabled": True,
            },
            "operationMappings": {"create_ticket": {"tool": "ticket.create"}},
        }
        request = PlaybookToolTaskRequest.model_validate(payload)
        request_log: list[dict] = []

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=lambda *args, **kwargs: _FakeClient(request_log)):
            result = execute_playbook_tool_task(request)

        self.assertEqual(result.routeKey, "success")
        self.assertEqual(request_log[0]["headers"], {"Authorization": "Bearer test-internal-token"})
