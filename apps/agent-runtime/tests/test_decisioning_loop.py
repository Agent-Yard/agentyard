import json
import os
import unittest
from unittest.mock import patch

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.decisioning import execute_agent_turn
from lynxus_agent_runtime.models import AgentTurnRequest
from lynxus_agent_runtime.tooling import execute_tool_call, openai_tool_definitions, resource_tool_function_name


class _FakeResponse:
    def __init__(self, payload: dict):
        self._payload = payload
        self.status_code = 200
        self.text = json.dumps(payload, ensure_ascii=False)

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


class _FakeTransport:
    def __init__(
        self,
        chat_responses: list[dict],
        request_log: list[dict],
        tool_payload: dict | None = None,
        payloads_by_url: dict[str, dict] | None = None,
    ) -> None:
        self._chat_responses = list(chat_responses)
        self._request_log = request_log
        self._tool_payload = tool_payload or {"ok": True}
        self._payloads_by_url = payloads_by_url or {}

    def handle(self, method: str, url: str, kwargs: dict) -> _FakeResponse:
        self._request_log.append({"method": method, "url": url, **kwargs})
        if url.endswith("/chat/completions"):
            return _FakeResponse(self._chat_responses.pop(0))
        return _FakeResponse(self._payloads_by_url.get(url, self._tool_payload))


class _FakeClient:
    def __init__(self, transport: _FakeTransport) -> None:
        self._transport = transport

    def __enter__(self) -> "_FakeClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def post(self, url: str, **kwargs) -> _FakeResponse:
        return self._transport.handle("POST", url, kwargs)

    def request(self, method: str, url: str, **kwargs) -> _FakeResponse:
        return self._transport.handle(method.upper(), url, kwargs)


def _request_payload() -> dict:
    return {
        "sessionId": "session-1",
        "assistantId": "assistant-1",
        "assistantReleaseVersion": "2026.04.19",
        "currentOwner": {
            "agentId": "agent-a",
            "name": "Agent A",
            "role": "support",
            "responsibility": "help the customer",
            "model": {
                "resourceId": "model-1",
                "resourceName": "OpenAI Compatible Model",
                "resourceVersionId": "model-ver-1",
                "resourceVersion": "1.0.0",
                "providerType": "OPENAI_COMPATIBLE",
                "modelId": "gpt-test",
                "baseUrl": "https://runtime.example",
                "apiKeyEnvVar": "TEST_OPENAI_COMPATIBLE_API_KEY",
                "temperature": 0,
                "maxTokens": 512,
            },
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
            "allowedActions": ["REPLY", "RUN_PLAYBOOK"],
            "playbookIds": ["pb-1"],
            "skills": [
                {
                    "resourceId": "skill-1",
                    "resourceName": "Refund Skill",
                    "resourceVersionId": "skill-ver-1",
                    "resourceVersion": "1.0.0",
                    "skillName": "Refund Policy",
                    "skillDesc": "Read refund constraints before answering.",
                    "skillPrompt": "退款时必须先确认订单状态，再决定是否走退款 playbook。",
                }
            ],
            "tools": [
                {
                    "resourceId": "tool-1",
                    "resourceName": "Ticket Tool",
                    "resourceVersionId": "tool-ver-1",
                    "resourceVersion": "1.0.0",
                    "providerType": "HTTP",
                    "authType": "SERVICE_ACCOUNT",
                    "timeoutSeconds": 15,
                    "retryPolicy": "NONE",
                    "http": {"endpoint": "https://tool.example/invoke", "method": "POST"},
                    "operations": [
                        {
                            "name": "create_ticket",
                            "description": "Create a service ticket.",
                            "inputSchema": json.dumps(
                                {
                                    "type": "object",
                                    "properties": {"subject": {"type": "string"}},
                                    "required": ["subject"],
                                    "additionalProperties": False,
                                },
                                ensure_ascii=False,
                            ),
                            "outputSchema": json.dumps(
                                {
                                    "type": "object",
                                    "properties": {
                                        "ticketId": {"type": "string"},
                                        "status": {"type": "string"},
                                    },
                                    "required": ["ticketId", "status"],
                                    "additionalProperties": False,
                                },
                                ensure_ascii=False,
                            ),
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
                "responsibility": "handle escalations",
                "allowedActions": ["REPLY"],
            }
        ],
        "availablePlaybooks": [
            {
                "playbookId": "pb-1",
                "name": "Playbook 1",
                "description": "refund flow",
            }
        ],
        "sharedState": {"knownPreference": "email"},
        "trigger": {
            "triggerType": "USER_MESSAGE",
            "eventId": "evt-1",
            "payload": {"text": "帮我发起退款"},
        },
        "recentEvents": [],
    }


class AgentRuntimeDecisionLoopTest(unittest.TestCase):
    def test_should_complete_skill_read_and_tool_loop_before_returning_final_decision(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["LYNXUS_KNOWLEDGE_SERVICE_BASE_URL"] = "https://knowledge.example"
        request = AgentTurnRequest.model_validate(_request_payload())
        operation = request.currentOwner.tools[0].operations[0]
        function_name = resource_tool_function_name(request.currentOwner.tools[0], operation)
        request_log: list[dict] = []
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": "",
                                "tool_calls": [
                                    {
                                        "id": "call-k1",
                                        "type": "function",
                                        "function": {
                                            "name": "knowledge_search",
                                            "arguments": json.dumps({"query": "退款规则"}, ensure_ascii=False),
                                        },
                                    }
                                ],
                            }
                        }
                    ]
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps({"skillReads": ["skill-ver-1"]}, ensure_ascii=False),
                            }
                        }
                    ]
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": "",
                                "tool_calls": [
                                    {
                                        "id": "call-1",
                                        "type": "function",
                                        "function": {
                                            "name": function_name,
                                            "arguments": json.dumps({"subject": "退款申请"}, ensure_ascii=False),
                                        },
                                    }
                                ],
                            }
                        }
                    ]
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyContent": "已为你创建退款工单。",
                                        },
                                        "sharedState": {
                                            "knownPreference": "email",
                                            "ticketId": "ticket-1",
                                        },
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
            ],
            request_log,
            payloads_by_url={
                "https://knowledge.example/internal/retrieve": {
                    "hits": [
                        {
                            "chunkId": "chunk-1",
                            "documentId": "doc-1",
                            "documentTitle": "退款规则",
                            "sourceUri": "kb://refund",
                            "snippet": "退款前先确认订单状态",
                            "score": 0.9,
                            "headingPath": "规则",
                        }
                    ]
                },
                "https://tool.example/invoke": {"ticketId": "ticket-1", "status": "RECORDED"},
            },
        )

        factory = lambda *args, **kwargs: _FakeClient(transport)
        with patch("lynxus_agent_runtime.decisioning.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.tooling.httpx.Client", side_effect=factory
        ):
            result, _ = execute_agent_turn(request)

        self.assertEqual(result.decision.action, "REPLY")
        self.assertEqual(result.decision.replyContent, "已为你创建退款工单。")
        self.assertEqual(result.sharedState["ticketId"], "ticket-1")
        self.assertEqual(len(request_log), 6)
        self.assertEqual(request_log[1]["url"], "https://knowledge.example/internal/retrieve")
        self.assertEqual(request_log[1]["json"]["indexSnapshotId"], "snapshot-1")
        third_chat_messages = request_log[3]["json"]["messages"]
        self.assertTrue(any("Loaded skill details" in str(message.get("content", "")) for message in third_chat_messages))
        self.assertEqual(request_log[4]["url"], "https://tool.example/invoke")
        self.assertEqual(request_log[4]["json"], {"subject": "退款申请"})
        self.assertIn("tools", request_log[0]["json"])

    def test_should_fallback_when_loop_cannot_finish(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["LYNXUS_AGENT_RUNTIME_MAX_TOOL_STEPS"] = "1"
        request = AgentTurnRequest.model_validate(_request_payload())
        operation = request.currentOwner.tools[0].operations[0]
        function_name = resource_tool_function_name(request.currentOwner.tools[0], operation)
        looping_response = {
            "choices": [
                {
                    "message": {
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                    "name": function_name,
                                    "arguments": json.dumps({"subject": "退款申请"}, ensure_ascii=False),
                                },
                            }
                        ],
                    }
                }
            ]
        }
        transport = _FakeTransport(
            [looping_response, looping_response],
            [],
            {"ticketId": "ticket-1", "status": "RECORDED"},
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.decisioning.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.tooling.httpx.Client", side_effect=factory
        ):
            result, _ = execute_agent_turn(request)

        self.assertEqual(result.decision.action, "REPLY")
        self.assertIn("lastUserMessage", result.sharedState)

        os.environ.pop("LYNXUS_AGENT_RUNTIME_MAX_TOOL_STEPS", None)

    def test_should_remote_read_knowledge_chunks(self) -> None:
        os.environ["LYNXUS_KNOWLEDGE_SERVICE_BASE_URL"] = "https://knowledge.example"
        request = AgentTurnRequest.model_validate(_request_payload())
        request_log: list[dict] = []
        transport = _FakeTransport(
            [],
            request_log,
            payloads_by_url={
                "https://knowledge.example/internal/read-chunks": {
                    "chunks": [
                        {
                            "chunkId": "chunk-1",
                            "documentId": "doc-1",
                            "documentTitle": "退款规则",
                            "sourceUri": "kb://refund",
                            "headingPath": "规则",
                            "content": "退款前先确认订单状态",
                        }
                    ]
                }
            },
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.tooling.httpx.Client", side_effect=factory):
            result = execute_tool_call(request, "knowledge_read", {"chunkIds": ["chunk-1"]})

        self.assertEqual(result["knowledgeBaseId"], "kb-1")
        self.assertEqual(result["chunks"][0]["chunkId"], "chunk-1")
        self.assertEqual(request_log[0]["url"], "https://knowledge.example/internal/read-chunks")

    def test_should_not_inject_knowledge_tools_when_knowledge_is_disabled_even_if_binding_exists(self) -> None:
        payload = _request_payload()
        payload["currentOwner"]["knowledgeEnabled"] = False
        request = AgentTurnRequest.model_validate(payload)

        definitions = openai_tool_definitions(request)
        tool_names = {
            item["function"]["name"]
            for item in definitions
            if item.get("type") == "function" and isinstance(item.get("function"), dict)
        }
        owner_capabilities = execute_tool_call(request, "get_owner_capabilities", {})

        self.assertNotIn("knowledge_search", tool_names)
        self.assertNotIn("knowledge_read", tool_names)
        self.assertIsNone(owner_capabilities["knowledgeBinding"])
