import json
import os
import unittest
from unittest.mock import patch

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.decisioning import execute_agent_turn
from lynxus_agent_runtime.data_security.rewriter import PrivateLlmRewriter
from lynxus_agent_runtime.data_security.rewriter import RewriteResult
from lynxus_agent_runtime.data_security.rules import SanitizationResult
from lynxus_agent_runtime.http_clients import reset_shared_http_client_registry
from lynxus_agent_runtime.models import AgentTurnRequest
from lynxus_agent_runtime.openai_compatible import (
    LlmUsageTracker,
    OpenAiCompatibleSettings,
    chat_completion,
)
from lynxus_agent_runtime.openai_adapter import render_openai_tool_definitions
from lynxus_agent_runtime.privacy_contracts import PrivacyMappingTelemetry, PrivacyStrategy
from lynxus_agent_runtime.privacy_pipeline import PrivacyPipeline, build_privacy_policy
from lynxus_agent_runtime.tooling import execute_tool_call, resource_tool_function_name, semantic_tool_definitions


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

    def close(self) -> None:
        return None


class _FakePrivacyStore:
    fragment_cache: dict[str, object] = {}
    fragment_metadata: dict[str, dict[str, object]] = {}
    forward_mapping: dict[tuple[str, str], str] = {}
    reverse_mapping: dict[str, str] = {}

    def __init__(self, policy) -> None:
        self._summary = PrivacyMappingTelemetry(
            enabled=policy.enabled,
            privacyModelResourceId=None if policy.model_binding is None else policy.model_binding.resource_id,
            privacyModelResourceName=None if policy.model_binding is None else policy.model_binding.resource_name,
            sanitizeCountByChannel={},
            restoreCountByChannel={},
            entityTypeBreakdown={},
            placeholderCount=0,
            unresolvedPlaceholderCount=0,
            blockedEventCount=0,
            lastProcessedAt=None,
        )

    @classmethod
    def reset_fragment_cache(cls) -> None:
        cls.fragment_cache = {}
        cls.fragment_metadata = {}
        cls.forward_mapping = {}
        cls.reverse_mapping = {}

    def ensure_mapping(self, entity_type: str, raw_value: str):  # noqa: ANN001
        key = (entity_type, raw_value)
        existing_placeholder = self.forward_mapping.get(key)
        if existing_placeholder is not None:
            return type(
                "MappingEntry",
                (),
                {
                    "placeholder_id": existing_placeholder,
                    "raw_value": raw_value,
                    "entity_type": entity_type,
                    "created": False,
                },
            )()

        placeholder_id = f"[{entity_type}_{len(self.forward_mapping) + 1:03d}]"
        self.forward_mapping[key] = placeholder_id
        self.reverse_mapping[placeholder_id] = raw_value
        self._summary.placeholderCount += 1
        self._summary.entityTypeBreakdown[entity_type] = self._summary.entityTypeBreakdown.get(entity_type, 0) + 1
        return type(
            "MappingEntry",
            (),
            {
                "placeholder_id": placeholder_id,
                "raw_value": raw_value,
                "entity_type": entity_type,
                "created": True,
            },
        )()

    def restore_placeholder(self, placeholder_id: str) -> str | None:
        return self.reverse_mapping.get(placeholder_id)

    def read_sanitized_fragment(self, cache_key: str):
        return self.fragment_cache.get(cache_key)

    def write_sanitized_fragment(self, cache_key: str, value, metadata: dict[str, object]) -> None:  # noqa: ANN001
        self.fragment_cache[cache_key] = value
        self.fragment_metadata[cache_key] = metadata

    def fingerprint_fragment_content(self, payload: bytes) -> str:
        return "fake-hmac:" + str(abs(hash(payload)))

    def record_sanitize(self, channel: str, new_placeholder_count: int, entity_type_breakdown: dict[str, int]) -> None:
        self._summary.sanitizeCountByChannel[channel] = self._summary.sanitizeCountByChannel.get(channel, 0) + 1

    def record_restore(self, channel: str, unresolved_count: int) -> None:
        self._summary.restoreCountByChannel[channel] = self._summary.restoreCountByChannel.get(channel, 0) + 1
        self._summary.unresolvedPlaceholderCount += unresolved_count

    def record_blocked(self) -> None:
        self._summary.blockedEventCount += 1

    def summary_telemetry(self) -> PrivacyMappingTelemetry:
        return self._summary

    def close(self) -> None:
        return None


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
                    "connector": {
                        "connectorType": "simple-http",
                        "accountSnapshot": None,
                        "timeoutSeconds": 15,
                        "retryPolicy": _retry_policy(),
                        "config": {"baseUrl": "https://tool.example"},
                        "operationMappings": {
                            "create_ticket": {"method": "POST", "path": "/invoke", "requestPlacement": "JSON_BODY"}
                        },
                    },
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
            "triggerMessageId": "msg-1",
            "payload": {"text": "帮我发起退款"},
        },
        "recentMessages": [
            {
                "messageId": "msg-1",
                "sessionId": "session-1",
                "sequence": 1,
                "role": "USER",
                "sender": {
                    "senderType": "CUSTOMER",
                    "senderId": "customer-1",
                    "senderName": "customer-1",
                },
                "status": "DELIVERED",
                "blocks": [{"type": "TEXT", "text": "帮我发起退款"}],
                "metadata": {},
                "createdAt": "2026-04-19T00:00:01Z",
                "updatedAt": "2026-04-19T00:00:01Z",
            }
        ],
        "recentEvents": [],
    }


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


def _text_message_input(text: str) -> dict:
    return {
        "blocks": [{"type": "TEXT", "text": text}],
        "metadata": {},
    }


def _enable_privacy_mapping(payload: dict) -> dict:
    payload["effectivePrivacyMappingEnabled"] = True
    payload["effectivePrivacyModelBinding"] = {
        "resourceId": "privacy-model-1",
        "resourceName": "Private Mapping Model",
        "resourceVersionId": "privacy-model-ver-1",
        "resourceVersion": "1.0.0",
        "providerType": "OPENAI_COMPATIBLE",
        "modelId": "gpt-private",
        "baseUrl": "https://privacy.example",
        "apiKeyEnvVar": "TEST_PRIVATE_API_KEY",
        "temperature": 0,
        "maxTokens": 256,
        "privateDeployment": True,
    }
    return payload


class AgentRuntimeDecisionLoopTest(unittest.TestCase):
    def setUp(self) -> None:
        reset_shared_http_client_registry()
        _FakePrivacyStore.reset_fragment_cache()

    def tearDown(self) -> None:
        reset_shared_http_client_registry()

    def test_should_complete_skill_read_and_tool_loop_before_returning_final_decision(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["LYNXUS_KNOWLEDGE_SERVICE_BASE_URL"] = "https://knowledge.example"
        payload = _request_payload()
        payload["currentOwner"]["tools"][0]["operations"][0]["outputSchema"] = json.dumps(
            {
                "type": "object",
                "properties": {
                    "ticketId": {"type": "string"},
                    "customerId": {"type": "string"},
                    "status": {"type": "string"},
                },
                "required": ["ticketId", "customerId", "status"],
                "additionalProperties": False,
            },
            ensure_ascii=False,
        )
        request = AgentTurnRequest.model_validate(payload)
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
                                            "replyMessage": _text_message_input("已为你创建退款工单。"),
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
                "https://tool.example/invoke": {
                    "ticketId": "ticket-1",
                    "customerId": "customer-secret-1",
                    "status": "RECORDED",
                },
            },
        )

        factory = lambda *args, **kwargs: _FakeClient(transport)
        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        result = outcome.result
        self.assertIsNotNone(result)
        self.assertEqual(result.decision.action, "REPLY")
        self.assertEqual(result.decision.replyMessage.blocks[0].text, "已为你创建退款工单。")
        self.assertEqual(result.sharedState["ticketId"], "ticket-1")
        self.assertEqual(len(request_log), 6)
        self.assertEqual(request_log[1]["url"], "https://knowledge.example/internal/retrieve")
        self.assertEqual(request_log[1]["json"]["indexSnapshotId"], "snapshot-1")
        third_chat_messages = request_log[3]["json"]["messages"]
        self.assertTrue(any("Loaded skill details" in str(message.get("content", "")) for message in third_chat_messages))
        self.assertEqual(request_log[4]["url"], "https://tool.example/invoke")
        self.assertEqual(request_log[4]["json"], {"subject": "退款申请"})
        final_chat_tool_messages = [
            message.get("content", "") for message in request_log[5]["json"]["messages"] if message.get("role") == "tool"
        ]
        self.assertTrue(any("ticket-1" in content for content in final_chat_tool_messages))
        self.assertTrue(any("customer-secret-1" in content for content in final_chat_tool_messages))
        self.assertIn("tools", request_log[0]["json"])
        self.assertEqual(4, len(outcome.llmUsage))
        self.assertEqual([1, 2, 3, 4], [entry.callSequence for entry in outcome.llmUsage])
        self.assertEqual([0, 1, 2, 3], [entry.toolLoopStep for entry in outcome.llmUsage])
        self.assertTrue(all(entry.sourceType == "SESSION_OWNER_MODEL" for entry in outcome.llmUsage))
        self.assertTrue(all(entry.usageAvailable is False for entry in outcome.llmUsage))

    def test_should_fail_when_loop_cannot_finish(self) -> None:
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

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            outcome, _ = execute_agent_turn(request)

        self.assertFalse(outcome.success)
        self.assertEqual("model did not return a final decision within loop step budget", outcome.failureReason)
        self.assertEqual(2, len(outcome.llmUsage))

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

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            result = execute_tool_call(request, "knowledge_read", {"chunkIds": ["chunk-1"]})

        self.assertEqual(result["knowledgeBaseId"], "kb-1")
        self.assertEqual(result["chunks"][0]["chunkId"], "chunk-1")
        self.assertEqual(request_log[0]["url"], "https://knowledge.example/internal/read-chunks")

    def test_should_not_inject_knowledge_tools_when_knowledge_is_disabled_even_if_binding_exists(self) -> None:
        payload = _request_payload()
        payload["currentOwner"]["knowledgeEnabled"] = False
        request = AgentTurnRequest.model_validate(payload)

        definitions = render_openai_tool_definitions(semantic_tool_definitions(request))
        tool_names = {
            item["function"]["name"]
            for item in definitions
            if item.get("type") == "function" and isinstance(item.get("function"), dict)
        }
        owner_capabilities = execute_tool_call(request, "get_owner_capabilities", {})

        self.assertNotIn("knowledge_search", tool_names)
        self.assertNotIn("knowledge_read", tool_names)
        self.assertIsNone(owner_capabilities["knowledgeBinding"])

    def test_should_allow_reply_with_extra_accompanying_reply_field(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        request = AgentTurnRequest.model_validate(_request_payload())
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyMessage": _text_message_input("已收到"),
                                            "accompanyingMessage": _text_message_input("这条不该阻断"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                }
            ],
            [],
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        result = outcome.result
        self.assertIsNotNone(result)
        self.assertEqual(result.decision.action, "REPLY")
        self.assertEqual(result.decision.accompanyingMessage.blocks[0].text, "这条不该阻断")
        self.assertEqual(1, len(outcome.llmUsage))
        self.assertFalse(outcome.llmUsage[0].usageAvailable)
        self.assertIsNone(outcome.llmUsage[0].promptTokens)
        self.assertEqual({}, outcome.llmUsage[0].rawUsage)

    def test_should_parse_block_security_assessment_from_final_decision(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        request = AgentTurnRequest.model_validate(_request_payload())
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {"action": "NO_REPLY"},
                                        "sharedState": {"knownPreference": "email"},
                                        "securityAssessment": {
                                            "action": "BLOCK",
                                            "categories": ["PROMPT_INJECTION"],
                                            "reason": "prompt_injection",
                                            "confidence": 0.92,
                                        },
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                }
            ],
            [],
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        result = outcome.result
        self.assertIsNotNone(result)
        self.assertEqual(result.decision.action, "NO_REPLY")
        self.assertIsNotNone(result.securityAssessment)
        self.assertEqual(result.securityAssessment.action, "BLOCK")
        self.assertEqual(result.securityAssessment.categories, ["PROMPT_INJECTION"])
        self.assertEqual(result.securityAssessment.reason, "prompt_injection")
        self.assertEqual(result.securityAssessment.confidence, 0.92)

    def test_should_preserve_block_security_assessment_when_final_decision_is_invalid(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        request = AgentTurnRequest.model_validate(_request_payload())
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "sharedState": {"unsafe": True},
                                        "securityAssessment": {
                                            "action": "BLOCK",
                                            "categories": ["SECRET_EXFILTRATION"],
                                            "reason": "secret_exfiltration",
                                            "confidence": 0.91,
                                        },
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
            ],
            [],
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        result = outcome.result
        self.assertIsNotNone(result)
        self.assertEqual(result.decision.action, "NO_REPLY")
        self.assertEqual(result.sharedState, request.sharedState)
        self.assertEqual(result.securityAssessment.action, "BLOCK")
        self.assertEqual(result.securityAssessment.categories, ["SECRET_EXFILTRATION"])

    def test_rendered_tool_definitions_should_include_output_schema_metadata(self) -> None:
        request = AgentTurnRequest.model_validate(_request_payload())

        definitions = render_openai_tool_definitions(semantic_tool_definitions(request))
        create_ticket = next(
            item
            for item in definitions
            if item.get("type") == "function"
            and isinstance(item.get("function"), dict)
            and item["function"]["name"].endswith("create_ticket")
        )

        self.assertIn("Output JSON schema:", create_ticket["function"]["description"])
        self.assertIn("\"ticketId\"", create_ticket["function"]["description"])
        self.assertIn("\"status\"", create_ticket["function"]["description"])

    def test_should_fail_closed_when_sanitized_prompt_leaks_sensitive_value(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        payload["trigger"]["payload"]["text"] = "我的邮箱是 alice@example.com"
        request = AgentTurnRequest.model_validate(payload)
        transport = _FakeTransport([], request_log)
        factory = lambda *args, **kwargs: _FakeClient(transport)

        original_sanitize_value = __import__(
            "lynxus_agent_runtime.data_security.mapper",
            fromlist=["sanitize_value"],
        ).sanitize_value

        def leaking_sanitize(value, store):  # noqa: ANN001
            if isinstance(value, str) and "alice@example.com" in value:
                return SanitizationResult(value, {}, 0)
            return original_sanitize_value(value, store)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ), patch("lynxus_agent_runtime.data_security.mapper.sanitize_value", side_effect=leaking_sanitize):
            outcome, _ = execute_agent_turn(request)

        self.assertFalse(outcome.success)
        self.assertTrue(outcome.failureReason)
        self.assertFalse(any(entry["url"] == "https://runtime.example/chat/completions" for entry in request_log))

    def test_should_not_require_private_rewriter_for_context_name_detected_by_rules(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ.pop("TEST_PRIVATE_API_KEY", None)
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        payload["trigger"]["payload"]["text"] = "user Alice Johnson"
        request = AgentTurnRequest.model_validate(payload)
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyMessage": _text_message_input("已处理"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                }
            ],
            request_log,
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        self.assertFalse(any(entry["url"] == "https://privacy.example/chat/completions" for entry in request_log))
        prompt_payload = json.dumps(request_log[0]["json"]["messages"], ensure_ascii=False)
        self.assertIn("[PERSON_001]", prompt_payload)
        self.assertNotIn("Alice Johnson", prompt_payload)

    def test_should_not_send_static_prompt_or_response_contract_to_private_rewriter(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        payload["trigger"]["payload"]["text"] = "hello"
        payload["recentMessages"][0]["blocks"] = [{"type": "TEXT", "text": "hello"}]
        request = AgentTurnRequest.model_validate(payload)
        transport = _FakeTransport(
            [
                {"choices": [{"message": {"content": json.dumps({"entities": []})}}]},
                {"choices": [{"message": {"content": json.dumps({"entities": []})}}]},
                {"choices": [{"message": {"content": json.dumps({"entities": []})}}]},
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyMessage": _text_message_input("ok"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
            ],
            request_log,
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        private_requests = [entry for entry in request_log if entry["url"] == "https://privacy.example/chat/completions"]
        self.assertGreaterEqual(len(private_requests), 1)
        private_payload = json.dumps([entry["json"]["messages"] for entry in private_requests], ensure_ascii=False)
        self.assertNotIn("Owner identity:", private_payload)
        self.assertNotIn("Final output must be JSON only", private_payload)
        self.assertNotIn("Response contract", private_payload)
        self.assertNotIn("SessionMessageInput", private_payload)

    def test_should_reuse_cached_runtime_fragment_on_later_sanitize_call(self) -> None:
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        request = AgentTurnRequest.model_validate(_enable_privacy_mapping(_request_payload()))
        policy = build_privacy_policy(request)

        with patch("lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore", _FakePrivacyStore), patch(
            "lynxus_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite",
            return_value=RewriteResult("Please help [PERSON_001]", {"PERSON": 1}, 1, 1),
        ) as rewrite:
            first_pipeline = PrivacyPipeline(policy)
            first = first_pipeline.sanitize_fragment(
                "PROMPT_RUNTIME_MESSAGE",
                "Please help Jane Doe",
                PrivacyStrategy.RULES_THEN_PRIVATE_LLM,
                source="recent_message:msg-99:99",
            )
            first_pipeline.close()

            second_pipeline = PrivacyPipeline(policy)
            second = second_pipeline.sanitize_fragment(
                "PROMPT_RUNTIME_MESSAGE",
                "Please help Jane Doe",
                PrivacyStrategy.RULES_THEN_PRIVATE_LLM,
                source="recent_message:msg-99:99",
            )
            second_pipeline.close()

        self.assertEqual("Please help [PERSON_001]", first)
        self.assertEqual("Please help [PERSON_001]", second)
        self.assertEqual(1, rewrite.call_count)

    def test_should_sanitize_nested_tool_results_with_private_rewriter_and_reuse_fragment_cache(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        payload = _enable_privacy_mapping(_request_payload())
        payload["currentOwner"]["tools"][0]["operations"][0]["outputSchema"] = json.dumps(
            {
                "type": "object",
                "properties": {
                    "ticketId": {"type": "string"},
                    "status": {"type": "string"},
                    "detail": {
                        "type": "object",
                        "properties": {"note": {"type": "string"}},
                        "required": ["note"],
                        "additionalProperties": False,
                    },
                },
                "required": ["ticketId", "status", "detail"],
                "additionalProperties": False,
            },
            ensure_ascii=False,
        )
        request = AgentTurnRequest.model_validate(payload)
        operation = request.currentOwner.tools[0].operations[0]
        function_name = resource_tool_function_name(request.currentOwner.tools[0], operation)
        request_log: list[dict] = []
        tool_call_response = {
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
        final_response = {
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "decision": {
                                    "action": "REPLY",
                                    "replyMessage": _text_message_input("ok"),
                                },
                                "sharedState": {"knownPreference": "email"},
                            },
                            ensure_ascii=False,
                        )
                    }
                }
            ]
        }
        transport = _FakeTransport(
            [tool_call_response, final_response, tool_call_response, final_response],
            request_log,
            payloads_by_url={
                "https://tool.example/invoke": {
                    "ticketId": "ticket-1",
                    "status": "RECORDED",
                    "detail": {"note": "Please help Jane Doe"},
                }
            },
        )

        def rewrite(text: str, store) -> RewriteResult:  # noqa: ANN001
            if "Jane Doe" not in text:
                return RewriteResult(text, {}, 0, 0)
            entry = store.ensure_mapping("PERSON", "Jane Doe")
            return RewriteResult(text.replace("Jane Doe", entry.placeholder_id), {"PERSON": 1}, 1 if entry.created else 0, 1)

        factory = lambda *args, **kwargs: _FakeClient(transport)
        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ), patch(
            "lynxus_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite",
            side_effect=rewrite,
        ) as rewrite_mock:
            first_outcome, _ = execute_agent_turn(request)
            second_outcome, _ = execute_agent_turn(request)

        self.assertTrue(first_outcome.success, first_outcome.failureReason)
        self.assertTrue(second_outcome.success, second_outcome.failureReason)
        owner_tool_prompts = [
            json.dumps(entry["json"]["messages"], ensure_ascii=False)
            for entry in request_log
            if entry["url"] == "https://runtime.example/chat/completions"
            and any(message.get("role") == "tool" for message in entry["json"]["messages"])
        ]
        self.assertGreaterEqual(len(owner_tool_prompts), 2)
        self.assertTrue(all("[PERSON_" in prompt for prompt in owner_tool_prompts))
        self.assertTrue(all("Jane Doe" not in prompt for prompt in owner_tool_prompts))
        self.assertEqual(1, len([call for call in rewrite_mock.call_args_list if "Jane Doe" in call.args[0]]))
        self.assertTrue(any(metadata["source"] == f"tool_result:{function_name}" for metadata in _FakePrivacyStore.fragment_metadata.values()))

    def test_should_allow_private_rewriter_for_runtime_user_content_not_covered_by_rules(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        payload["trigger"]["payload"]["text"] = "Please help Jane Doe"
        payload["recentMessages"][0]["blocks"] = [{"type": "TEXT", "text": "Please help Jane Doe"}]
        request = AgentTurnRequest.model_validate(payload)
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {"entities": [{"rawValue": "Jane Doe", "entityType": "PERSON"}]},
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {"entities": [{"rawValue": "Jane Doe", "entityType": "PERSON"}]},
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {"entities": [{"rawValue": "Jane Doe", "entityType": "PERSON"}]},
                                    ensure_ascii=False,
                                )
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
                                            "replyMessage": _text_message_input("ok"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
            ],
            request_log,
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ):
            outcome, _ = execute_agent_turn(request)

        self.assertTrue(outcome.success)
        self.assertTrue(any(entry["url"] == "https://privacy.example/chat/completions" for entry in request_log))
        owner_request = next(entry for entry in request_log if entry["url"] == "https://runtime.example/chat/completions")
        owner_prompt = json.dumps(owner_request["json"]["messages"], ensure_ascii=False)
        self.assertIn("[PERSON_", owner_prompt)
        self.assertNotIn("Jane Doe", owner_prompt)

    def test_should_reuse_final_response_cache_for_restored_assistant_history(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        previous_private_key = os.environ.pop("TEST_PRIVATE_API_KEY", None)
        self.addCleanup(
            lambda: os.environ.__setitem__("TEST_PRIVATE_API_KEY", previous_private_key)
            if previous_private_key is not None
            else os.environ.pop("TEST_PRIVATE_API_KEY", None)
        )
        request_log: list[dict] = []
        first_payload = _enable_privacy_mapping(_request_payload())
        first_payload["trigger"]["payload"]["text"] = "user Jane Doe"
        first_payload["recentMessages"][0]["blocks"] = [{"type": "TEXT", "text": "user Jane Doe"}]
        first_request = AgentTurnRequest.model_validate(first_payload)

        second_payload = _enable_privacy_mapping(_request_payload())
        second_payload["trigger"] = {
            "triggerType": "USER_MESSAGE",
            "eventId": "evt-2",
            "triggerMessageId": "msg-3",
            "payload": {"text": "谢谢"},
        }
        second_payload["recentMessages"] = [
            {
                **first_payload["recentMessages"][0],
                "blocks": [{"type": "TEXT", "text": "user Jane Doe"}],
            },
            {
                "messageId": "msg-2",
                "sessionId": "session-1",
                "sequence": 2,
                "role": "ASSISTANT",
                "sender": {
                    "senderType": "AGENT",
                    "senderId": "agent-a",
                    "senderName": "Agent A",
                },
                "status": "DELIVERED",
                "blocks": [{"type": "TEXT", "text": "已为 Jane Doe 创建工单"}],
                "metadata": {},
                "createdAt": "2026-04-19T00:00:02Z",
                "updatedAt": "2026-04-19T00:00:02Z",
            },
            {
                **first_payload["recentMessages"][0],
                "messageId": "msg-3",
                "sequence": 3,
                "blocks": [{"type": "TEXT", "text": "谢谢"}],
                "createdAt": "2026-04-19T00:00:03Z",
                "updatedAt": "2026-04-19T00:00:03Z",
            },
        ]
        second_request = AgentTurnRequest.model_validate(second_payload)
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyMessage": _text_message_input("已为 [PERSON_001] 创建工单"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
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
                                            "replyMessage": _text_message_input("不用谢"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ]
                },
            ],
            request_log,
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ):
            first_outcome, _ = execute_agent_turn(first_request)
            second_outcome, _ = execute_agent_turn(second_request)

        self.assertTrue(first_outcome.success, first_outcome.failureReason)
        self.assertEqual(
            "已为 Jane Doe 创建工单",
            first_outcome.result.decision.replyMessage.blocks[0].text,
        )
        self.assertTrue(second_outcome.success, second_outcome.failureReason)
        runtime_requests = [entry for entry in request_log if entry["url"] == "https://runtime.example/chat/completions"]
        self.assertEqual(2, len(runtime_requests))
        assistant_history_messages = [
            message for message in runtime_requests[1]["json"]["messages"] if message.get("role") == "assistant"
        ]
        self.assertEqual(["已为 [PERSON_001] 创建工单"], [message["content"] for message in assistant_history_messages])
        self.assertNotIn("Jane Doe", json.dumps(runtime_requests[1]["json"]["messages"], ensure_ascii=False))

    def test_should_fail_closed_when_rewriter_returns_raw_sensitive_text(self) -> None:
        os.environ["TEST_OPENAI_COMPATIBLE_API_KEY"] = "secret"
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        payload["trigger"]["payload"]["text"] = "email alice@example.com"
        request = AgentTurnRequest.model_validate(payload)
        transport = _FakeTransport([], request_log)
        factory = lambda *args, **kwargs: _FakeClient(transport)

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory), patch(
            "lynxus_agent_runtime.privacy_pipeline.SessionPrivacyMapStore",
            _FakePrivacyStore,
        ), patch(
            "lynxus_agent_runtime.data_security.mapper.sanitize_value",
            return_value=SanitizationResult("email alice@example.com", {}, 0, 0),
        ), patch(
            "lynxus_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite",
            return_value=type(
                "RewriteResult",
                (),
                {
                    "value": "email alice@example.com",
                    "entity_type_breakdown": {},
                    "new_placeholder_count": 0,
                    "total_replacement_count": 1,
                },
            )(),
        ):
            outcome, _ = execute_agent_turn(request)

        self.assertFalse(outcome.success)
        self.assertIn("alice@example.com", outcome.failureReason)
        self.assertFalse(any(entry["url"] == "https://runtime.example/chat/completions" for entry in request_log))

    def test_should_collect_usage_for_private_rewriter_and_owner_model(self) -> None:
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        request_log: list[dict] = []
        payload = _enable_privacy_mapping(_request_payload())
        request = AgentTurnRequest.model_validate(payload)
        usage_tracker = LlmUsageTracker()
        transport = _FakeTransport(
            [
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "entities": [
                                            {
                                                "rawValue": "Alice Johnson",
                                                "entityType": "PERSON",
                                            }
                                        ]
                                    },
                                    ensure_ascii=False,
                                ),
                            }
                        }
                    ],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
                },
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "decision": {
                                            "action": "REPLY",
                                            "replyMessage": _text_message_input("已处理"),
                                        },
                                        "sharedState": {"knownPreference": "email"},
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 6, "total_tokens": 18},
                },
            ],
            request_log,
        )
        factory = lambda *args, **kwargs: _FakeClient(transport)

        class _Store:
            def ensure_mapping(self, entity_type: str, raw_value: str):  # noqa: ANN001
                return type(
                    "MappingEntry",
                    (),
                    {
                        "placeholder_id": "[PERSON_001]",
                        "raw_value": raw_value,
                        "entity_type": entity_type,
                        "created": True,
                    },
                )()

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            rewritten = PrivateLlmRewriter(build_privacy_policy(request).model_binding, usage_tracker).rewrite(
                "user Alice Johnson",
                _Store(),
            )

        self.assertEqual("user [PERSON_001]", rewritten.value)
        self.assertEqual(1, rewritten.total_replacement_count)
        self.assertEqual(1, len(usage_tracker.entries()))
        self.assertEqual("SESSION_PRIVACY_MODEL", usage_tracker.entries()[0].sourceType)
        self.assertEqual("privacy-model-1", usage_tracker.entries()[0].modelResourceId)
        self.assertEqual(9, usage_tracker.entries()[0].totalTokens)
        self.assertEqual([1], [entry.callSequence for entry in usage_tracker.entries()])

    def test_should_reuse_shared_client_for_same_origin_with_different_base_paths(self) -> None:
        request_log: list[dict] = []
        created_clients: list[_FakeClient] = []

        def factory(*args, **kwargs):  # noqa: ANN002, ANN003
            client = _FakeClient(
                _FakeTransport(
                    [
                        {"choices": [{"message": {"content": json.dumps({"ok": True})}}]},
                        {"choices": [{"message": {"content": json.dumps({"ok": True})}}]},
                    ],
                    request_log,
                )
            )
            created_clients.append(client)
            return client

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            chat_completion(
                OpenAiCompatibleSettings(
                    base_url="https://runtime.example/v1",
                    model_id="gpt-test",
                    api_key="secret",
                    provider_type="OPENAI_COMPATIBLE",
                ),
                {"model": "gpt-test", "messages": []},
                timeout_seconds=5.0,
            )
            chat_completion(
                OpenAiCompatibleSettings(
                    base_url="https://runtime.example/openai",
                    model_id="gpt-test",
                    api_key="secret",
                    provider_type="OPENAI_COMPATIBLE",
                ),
                {"model": "gpt-test", "messages": []},
                timeout_seconds=5.0,
            )

        self.assertEqual(1, len(created_clients))
        self.assertEqual(
            [
                "https://runtime.example/v1/chat/completions",
                "https://runtime.example/openai/chat/completions",
            ],
            [entry["url"] for entry in request_log],
        )

    def test_should_log_raw_llm_request_and_response_at_debug_level(self) -> None:
        request_log: list[dict] = []

        def factory(*args, **kwargs):  # noqa: ANN002, ANN003
            return _FakeClient(
                _FakeTransport(
                    [
                        {
                            "choices": [
                                {
                                    "message": {
                                        "content": json.dumps({"decision": {"action": "NO_REPLY"}}),
                                    }
                                }
                            ],
                            "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3},
                        }
                    ],
                    request_log,
                )
            )

        payload = {"model": "gpt-test", "messages": [{"role": "user", "content": "raw input"}]}
        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            with self.assertLogs("lynxus-agent-runtime", level="DEBUG") as logs:
                chat_completion(
                    OpenAiCompatibleSettings(
                        base_url="https://runtime.example/v1",
                        model_id="gpt-test",
                        api_key="secret",
                        provider_type="OPENAI_COMPATIBLE",
                        model_resource_id="model-1",
                        model_resource_version_id="model-ver-1",
                    ),
                    payload,
                    timeout_seconds=5.0,
                    source_type="SESSION_OWNER_MODEL",
                    tool_loop_step=7,
                )

        request_record = next(record for record in logs.records if record.getMessage() == "openai-compatible llm request")
        response_record = next(record for record in logs.records if record.getMessage() == "openai-compatible llm response")
        self.assertEqual(
            {
                "url": "https://runtime.example/v1/chat/completions",
                "headers": {
                    "Authorization": "Bearer [REDACTED]",
                    "Content-Type": "application/json",
                },
                "payload": payload,
                "timeoutSeconds": 5.0,
            },
            request_record.llmRequest,
        )
        self.assertEqual("SESSION_OWNER_MODEL", request_record.sourceType)
        self.assertEqual(7, request_record.toolLoopStep)
        self.assertEqual("gpt-test", request_record.modelId)
        self.assertEqual("https://runtime.example/v1/chat/completions", response_record.llmResponse["url"])
        self.assertEqual(200, response_record.llmResponse["statusCode"])
        self.assertIn('"total_tokens": 3', response_record.llmResponse["body"])
        self.assertNotIn("secret", str(request_record.llmRequest["headers"]))

    def test_should_reuse_shared_client_for_knowledge_calls_on_same_origin(self) -> None:
        os.environ["LYNXUS_KNOWLEDGE_SERVICE_BASE_URL"] = "https://knowledge.example/api"
        request = AgentTurnRequest.model_validate(_request_payload())
        request_log: list[dict] = []
        created_clients: list[_FakeClient] = []

        def factory(*args, **kwargs):  # noqa: ANN002, ANN003
            client = _FakeClient(
                _FakeTransport(
                    [],
                    request_log,
                    payloads_by_url={
                        "https://knowledge.example/api/internal/retrieve": {"hits": []},
                        "https://knowledge.example/api/internal/read-chunks": {"chunks": []},
                    },
                )
            )
            created_clients.append(client)
            return client

        with patch("lynxus_agent_runtime.http_clients.httpx.Client", side_effect=factory):
            execute_tool_call(request, "knowledge_search", {"query": "refund"})
            execute_tool_call(request, "knowledge_read", {"chunkIds": ["chunk-1"]})

        self.assertEqual(1, len(created_clients))
        self.assertEqual(
            [
                "https://knowledge.example/api/internal/retrieve",
                "https://knowledge.example/api/internal/read-chunks",
            ],
            [entry["url"] for entry in request_log],
        )
