import asyncio
import logging
import os
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from app.main import (
    AGENT_HUMAN_TASK_SOURCE,
    AGENT_DECISION_SKILL_READ,
    AgentExecutionPolicySnapshot,
    AgentSnapshot,
    AgentTurnError,
    AssistantPolicySnapshot,
    AssistantRunSnapshot,
    ExecutionCheckpoint,
    GraphEdgeSnapshot,
    GraphNodeSnapshot,
    GraphSnapshot,
    ResumeAction,
    HttpToolProviderConfig,
    HumanNodeConfig,
    KnowledgeBindingSnapshot,
    LlmModelConfig,
    ResourceConfigurationSnapshot,
    ResourceVersionSnapshot,
    SessionContext,
    SessionMessageSnapshot,
    SkillConfig,
    ToolConfig,
    ToolOperationConfig,
    WorkflowResumeRequest,
    available_routes_for_prompt,
    available_skill_catalog,
    build_conversation_history,
    build_structured_agent_prompt,
    build_system_prompt,
    build_tool_outcome,
    build_runtime_context_block,
    categorize_agent_turn_error,
    call_http_tool,
    call_llm,
    configure_runtime_logger,
    execute_agent_node,
    execute_end_node,
    execute_human_node,
    execute_start_node,
    format_timestamp_to_minute,
    loaded_skill_details,
    memory_window_for_agent,
    merge_loaded_skills,
    prompt_user_messages,
    parse_agent_structured_response,
    retrieve_knowledge,
    restore_state,
    validate_graph,
    validate_tool_result,
    workflow_result_from_state,
)


def make_agent(memory_window_size: int) -> AgentSnapshot:
    return AgentSnapshot(
        agentId="agent-1",
        name="test-agent",
        role="support",
        responsibility="help user",
        executionPolicy=AgentExecutionPolicySnapshot(
            inheritAssistantDefaults=True,
            modelResourceId=None,
            modelResourceVersionId=None,
            systemPrompt="你是测试智能体",
            ragEnabled=False,
            inheritAssistantKnowledge=False,
            knowledge=None,
            memoryWindowSize=memory_window_size,
            skillResourceIds=[],
            skillResourceVersionIds=[],
            toolResourceIds=[],
            toolResourceVersionIds=[],
        ),
    )


def make_assistant(memory_enabled: bool, memory_window_size: int) -> AssistantRunSnapshot:
    return AssistantRunSnapshot(
        assistantId="assistant-1",
        assistantName="test-assistant",
        assistantReleaseVersion="1.0.0",
        assistantPolicy=AssistantPolicySnapshot(
            providerResourceId=None,
            providerResourceVersionId=None,
            memoryEnabled=memory_enabled,
            memoryWindowSize=memory_window_size,
        ),
        agents=[],
        resources=[],
        graph=GraphSnapshot(executionMode="GRAPH", nodes=[], edges=[]),
    )


def make_skill_resource(version_id: str, name: str, desc: str, prompt: str) -> ResourceVersionSnapshot:
    return ResourceVersionSnapshot(
        resourceId=f"resource-{version_id}",
        resourceName=name,
        resourceType="SKILL",
        resourceVersionId=version_id,
        resourceVersion="1.0.0",
        boundAgents=[],
        configuration=ResourceConfigurationSnapshot(
            type="SKILL",
            skill=SkillConfig(skillName=name, skillDesc=desc, skillPrompt=prompt),
        ),
    )


def make_model_resource(version_id: str = "model-v1") -> ResourceVersionSnapshot:
    return ResourceVersionSnapshot(
        resourceId="resource-model",
        resourceName="默认模型",
        resourceType="LLM_MODEL",
        resourceVersionId=version_id,
        resourceVersion="1.0.0",
        boundAgents=[],
        configuration=ResourceConfigurationSnapshot(
            type="LLM_MODEL",
            llmModel=LlmModelConfig(
                providerType="OPENAI",
                modelId="gpt-test",
                baseUrl="https://example.invalid",
                apiKeyEnvVar="DUMMY_KEY",
                organization="",
                project="",
                region="",
                temperature=0.1,
                maxTokens=512,
            ),
        ),
    )


def make_tool_resource(
    version_id: str = "tool-v1",
    operations: list[ToolOperationConfig] | None = None,
) -> ResourceVersionSnapshot:
    return ResourceVersionSnapshot(
        resourceId="resource-tool",
        resourceName="退款策略工具",
        resourceType="TOOL",
        resourceVersionId=version_id,
        resourceVersion="1.0.0",
        boundAgents=[],
        configuration=ResourceConfigurationSnapshot(
            type="TOOL",
            tool=ToolConfig(
                operations=operations
                or [
                    ToolOperationConfig(
                        name="evaluate_refund",
                        description="判断退款资格",
                        inputSchema='{"type":"object"}',
                        outputSchema='{"type":"object"}',
                    )
                ],
                providerType="HTTP",
                authType="NONE",
                timeoutSeconds=10,
                retryPolicy="NONE",
                http=HttpToolProviderConfig(endpoint="https://tool.invalid/refund", method="POST"),
            ),
        ),
    )


def make_agent_state(assistant: AssistantRunSnapshot, graph: GraphSnapshot, question: str = "可以帮我退款吗") -> dict:
    return {
        "workflow_instance_id": "wf-1",
        "question": question,
        "session_context": {
            "sessionId": "session-1",
            "customerId": "u-1",
            "latestMessage": text_message_snapshot_payload(question),
            "history": [],
            "loadedSkillResourceVersionIds": [],
            "sharedState": {"facts": {}, "artifacts": {}, "agentScopes": {}},
        },
        "assistant": assistant.model_dump(mode="json"),
        "graph": graph.model_dump(mode="json"),
        "entry_node_key": graph.nodes[0].nodeKey,
        "current_node_key": None,
        "next_node_key": None,
        "route_key": None,
        "summary": "",
        "output_messages": [],
        "retrieval_hits": [],
        "retrieval_cache": {},
        "tool_history": [],
        "tool_calls": [],
        "node_snapshots": [],
        "resume_task": None,
        "checkpoint": None,
        "escalation_required": False,
        "latest_tool_outcome": None,
        "resume_input": None,
        "resume_count": 0,
        "agent_turn_state": {"phase": "IDLE", "turnIndex": 0, "turnLogs": []},
        "pause_reason": None,
        "latest_failure": None,
        "workflow_status": "RUNNING",
        "model_hits": [],
    }


def text_message_snapshot(text: str, role: str = "USER", sender_name: str = "u-1") -> SessionMessageSnapshot:
    return SessionMessageSnapshot(
        role=role,
        senderName=sender_name,
        payloadType="TEXT",
        payload={"text": text},
        content=text,
        createdAt="2026-03-24T08:35:20Z",
    )


def text_message_snapshot_payload(text: str, role: str = "USER", sender_name: str = "u-1") -> dict:
    return text_message_snapshot(text, role=role, sender_name=sender_name).model_dump(mode="json")


class MemoryPromptTests(unittest.TestCase):
    def test_agent_snapshot_uses_responsibility_field(self) -> None:
        agent = AgentSnapshot.model_validate(
            {
                "agentId": "agent-1",
                "name": "legacy-agent",
                "role": "sales",
                "responsibility": "负责旅游相关保险销售",
                "executionPolicy": {
                    "inheritAssistantDefaults": True,
                    "modelResourceId": None,
                    "modelResourceVersionId": None,
                    "systemPrompt": "",
                    "ragEnabled": False,
                    "inheritAssistantKnowledge": False,
                    "knowledge": None,
                    "memoryWindowSize": 10,
                    "skillResourceIds": [],
                    "skillResourceVersionIds": [],
                    "toolResourceIds": [],
                    "toolResourceVersionIds": [],
                },
            }
        )

        self.assertEqual("负责旅游相关保险销售", agent.responsibility)

    def test_graph_edge_snapshot_requires_explicit_route_key(self) -> None:
        edge = GraphEdgeSnapshot.model_validate(
            {
                "edgeKey": "edge-1",
                "sourceNodeKey": "start",
                "targetNodeKey": "agent",
                "routeKey": "default",
                "label": "默认流转",
                "defaultEdge": True,
            }
        )

        self.assertEqual("default", edge.routeKey)

    def test_validate_graph_accepts_valid_graph(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10).model_copy(
            update={"agents": [make_agent(10)]}
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="agent", nodeName="分诊", nodeType="AGENT", description="处理问题", agentId="agent-1", humanNode=None),
                GraphNodeSnapshot(nodeKey="human", nodeName="人工处理", nodeType="HUMAN", description="人工接手", agentId=None, humanNode=HumanNodeConfig(title="人工待办", instruction="请审核", expectedAction="填写意见", resumeRouteKey="default")),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="出口", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent", routeKey="default", label="开始", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-human", sourceNodeKey="agent", targetNodeKey="human", routeKey="needs_review", label="转人工", defaultEdge=False),
                GraphEdgeSnapshot(edgeKey="edge-agent-end", sourceNodeKey="agent", targetNodeKey="end", routeKey="default", label="直接完成", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-human-end", sourceNodeKey="human", targetNodeKey="end", routeKey="default", label="人工完成", defaultEdge=True),
            ],
        )

        validate_graph(graph, assistant)

    def test_validate_graph_rejects_start_node_with_multiple_edges(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end-a", nodeName="结束A", nodeType="END", description="出口A", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end-b", nodeName="结束B", nodeType="END", description="出口B", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-a", sourceNodeKey="start", targetNodeKey="end-a", routeKey="default", label="默认", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-b", sourceNodeKey="start", targetNodeKey="end-b", routeKey="other", label="其他", defaultEdge=False),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("START node must have exactly one outgoing edge", str(ctx.exception.detail))

    def test_validate_graph_rejects_start_node_without_default_route(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="出口", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="end", routeKey="sales", label="销售", defaultEdge=False),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("START node outgoing edge must be routeKey=default and defaultEdge=true", str(ctx.exception.detail))

    def test_validate_graph_rejects_unknown_agent_binding(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="agent", nodeName="处理", nodeType="AGENT", description="处理节点", agentId="agent-missing", humanNode=None),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="出口", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-end", sourceNodeKey="agent", targetNodeKey="end", routeKey="default", label="结束", defaultEdge=True),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("unknown agent for node agent", str(ctx.exception.detail))

    def test_validate_graph_rejects_human_node_without_config(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="human", nodeName="人工处理", nodeType="HUMAN", description="人工接手", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="出口", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="human", routeKey="default", label="转人工", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-human-end", sourceNodeKey="human", targetNodeKey="end", routeKey="default", label="完成", defaultEdge=True),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("human node human requires humanNode config", str(ctx.exception.detail))

    def test_validate_graph_rejects_duplicate_route_keys(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10).model_copy(
            update={"agents": [make_agent(10)]}
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="agent", nodeName="处理", nodeType="AGENT", description="处理节点", agentId="agent-1", humanNode=None),
                GraphNodeSnapshot(nodeKey="end-a", nodeName="结束A", nodeType="END", description="出口A", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end-b", nodeName="结束B", nodeType="END", description="出口B", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end-c", nodeName="结束C", nodeType="END", description="出口C", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-a", sourceNodeKey="agent", targetNodeKey="end-a", routeKey="default", label="默认完成", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-b", sourceNodeKey="agent", targetNodeKey="end-b", routeKey="same_route", label="结束B", defaultEdge=False),
                GraphEdgeSnapshot(edgeKey="edge-agent-c", sourceNodeKey="agent", targetNodeKey="end-c", routeKey="same_route", label="结束C", defaultEdge=False),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("duplicate routeKey for node agent", str(ctx.exception.detail))

    def test_validate_graph_rejects_branching_node_without_default_edge(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10).model_copy(
            update={"agents": [make_agent(10)]}
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="agent", nodeName="处理", nodeType="AGENT", description="处理节点", agentId="agent-1", humanNode=None),
                GraphNodeSnapshot(nodeKey="end-a", nodeName="结束A", nodeType="END", description="出口A", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="end-b", nodeName="结束B", nodeType="END", description="出口B", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-a", sourceNodeKey="agent", targetNodeKey="end-a", routeKey="approve", label="通过", defaultEdge=False),
                GraphEdgeSnapshot(edgeKey="edge-agent-b", sourceNodeKey="agent", targetNodeKey="end-b", routeKey="reject", label="拒绝", defaultEdge=False),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("branching node requires default edge", str(ctx.exception.detail))

    def test_validate_graph_rejects_unreachable_nodes(self) -> None:
        assistant = make_assistant(memory_enabled=True, memory_window_size=10).model_copy(
            update={"agents": [make_agent(10)]}
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="入口", agentId=None, humanNode=None),
                GraphNodeSnapshot(nodeKey="agent", nodeName="处理", nodeType="AGENT", description="处理节点", agentId="agent-1", humanNode=None),
                GraphNodeSnapshot(nodeKey="detached", nodeName="孤立节点", nodeType="AGENT", description="未接入", agentId="agent-1", humanNode=None),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="出口", agentId=None, humanNode=None),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-agent-end", sourceNodeKey="agent", targetNodeKey="end", routeKey="default", label="结束", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-detached-end", sourceNodeKey="detached", targetNodeKey="end", routeKey="default", label="孤立结束", defaultEdge=True),
            ],
        )

        with self.assertRaises(HTTPException) as ctx:
            validate_graph(graph, assistant)
        self.assertIn("graph contains unreachable nodes", str(ctx.exception.detail))

    def test_build_tool_outcome_keeps_business_result_without_orchestration_mapping(self) -> None:
        tool_resource = make_tool_resource()
        operation = tool_resource.configuration.tool.operations[0]

        outcome = build_tool_outcome(
            tool_resource,
            operation,
            {"status": "COMPLETED", "ticketId": "TICKET-1001", "reason": "需要人工复核"},
        )

        self.assertEqual(
            outcome,
            {
                "toolResourceId": "resource-tool",
                "toolResourceName": "退款策略工具",
                "operation": "evaluate_refund",
                "providerType": "HTTP",
                "result": {"status": "COMPLETED", "ticketId": "TICKET-1001", "reason": "需要人工复核"},
            },
        )

    def test_validate_tool_result_rejects_non_object_response(self) -> None:
        tool_resource = make_tool_resource()
        operation = tool_resource.configuration.tool.operations[0]

        with self.assertRaises(AgentTurnError) as captured:
            validate_tool_result(tool_resource, operation, ["not", "an", "object"])

        self.assertEqual("TOOL_RESPONSE_INVALID", captured.exception.code)

    def test_validate_tool_result_rejects_output_schema_mismatch(self) -> None:
        tool_resource = make_tool_resource(
            operations=[
                ToolOperationConfig(
                    name="evaluate_refund",
                    description="判断退款资格",
                    outputSchema='{"type":"object","required":["ticketId"],"properties":{"ticketId":{"type":"string"}}}',
                )
            ]
        )
        operation = tool_resource.configuration.tool.operations[0]

        with self.assertRaises(AgentTurnError) as captured:
            validate_tool_result(tool_resource, operation, {"status": "COMPLETED"})

        self.assertEqual("TOOL_RESPONSE_INVALID", captured.exception.code)
        self.assertIn("$.ticketId is required", captured.exception.message)

    def test_categorize_agent_turn_error_maps_tool_schema_invalid_to_configuration_failure(self) -> None:
        self.assertEqual("CONFIGURATION_FAILURE", categorize_agent_turn_error("TOOL_SCHEMA_INVALID"))

    def test_should_send_get_http_tool_payload_as_query_params(self) -> None:
        tool_resource = make_tool_resource()
        tool_resource.configuration.tool.http.method = "GET"
        tool_resource.configuration.tool.http.endpoint = "https://example.invalid/refund"
        operation = tool_resource.configuration.tool.operations[0]
        payload = {"orderId": "ord-1", "includeHistory": True}
        captured_request: dict = {}

        class FakeResponse:
            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"status": "ok"}

        class FakeAsyncClient:
            def __init__(self, *args, **kwargs) -> None:
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb) -> bool:
                return False

            async def request(self, method: str, url: str, **kwargs) -> FakeResponse:
                captured_request["method"] = method
                captured_request["url"] = url
                captured_request["kwargs"] = kwargs
                return FakeResponse()

        with patch("app.main.httpx.AsyncClient", FakeAsyncClient):
            result = asyncio.run(call_http_tool(tool_resource, operation, payload))

        self.assertEqual({"status": "ok"}, result)
        self.assertEqual("GET", captured_request["method"])
        self.assertEqual("https://example.invalid/refund", captured_request["url"])
        self.assertEqual(payload, captured_request["kwargs"].get("params"))
        self.assertNotIn("json", captured_request["kwargs"])

    def test_should_send_non_get_http_tool_payload_as_json_body(self) -> None:
        tool_resource = make_tool_resource()
        tool_resource.configuration.tool.http.endpoint = "https://example.invalid/refund"
        operation = tool_resource.configuration.tool.operations[0]
        payload = {"orderId": "ord-1", "reason": "duplicate"}
        captured_request: dict = {}

        class FakeResponse:
            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"status": "ok"}

        class FakeAsyncClient:
            def __init__(self, *args, **kwargs) -> None:
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb) -> bool:
                return False

            async def request(self, method: str, url: str, **kwargs) -> FakeResponse:
                captured_request["method"] = method
                captured_request["url"] = url
                captured_request["kwargs"] = kwargs
                return FakeResponse()

        with patch("app.main.httpx.AsyncClient", FakeAsyncClient):
            result = asyncio.run(call_http_tool(tool_resource, operation, payload))

        self.assertEqual({"status": "ok"}, result)
        self.assertEqual("POST", captured_request["method"])
        self.assertEqual("https://example.invalid/refund", captured_request["url"])
        self.assertEqual(payload, captured_request["kwargs"].get("json"))
        self.assertNotIn("params", captured_request["kwargs"])

    def test_should_skip_knowledge_hits_when_retrieval_is_low_confidence(self) -> None:
        binding = KnowledgeBindingSnapshot(
            knowledgeBaseId="knowledge-base-support",
            knowledgeBaseName="客服知识库",
            knowledgeReleaseId="knowledge-release-support-v1",
            knowledgeReleaseVersion="1.0.0",
            snapshotId="snapshot-kb-support-v1",
            defaultTopK=4,
            retrievalMode="HYBRID",
            minScore=0.2,
        )

        class FakeResponse:
            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"lowConfidence": True, "hits": [{"snippet": "不应返回"}]}

        class FakeAsyncClient:
            def __init__(self, *args, **kwargs) -> None:
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb) -> bool:
                return False

            async def post(self, url: str, json: dict, headers: dict | None = None) -> FakeResponse:
                return FakeResponse()

        with patch("app.main.httpx.AsyncClient", FakeAsyncClient):
            hits = asyncio.run(retrieve_knowledge(binding, "怎么重置密码"))

        self.assertEqual(hits, [])

    def test_should_return_knowledge_hits_when_retrieval_succeeds(self) -> None:
        binding = KnowledgeBindingSnapshot(
            knowledgeBaseId="knowledge-base-support",
            knowledgeBaseName="客服知识库",
            knowledgeReleaseId="knowledge-release-support-v1",
            knowledgeReleaseVersion="1.0.0",
            snapshotId="snapshot-kb-support-v1",
            defaultTopK=3,
            retrievalMode="HYBRID",
            minScore=0.1,
        )

        class FakeResponse:
            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"lowConfidence": False, "hits": [{"snippet": "请通过忘记密码完成重置"}]}

        class FakeAsyncClient:
            def __init__(self, *args, **kwargs) -> None:
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb) -> bool:
                return False

            async def post(self, url: str, json: dict, headers: dict | None = None) -> FakeResponse:
                assert headers == {"Authorization": "Bearer test-internal-token"}
                return FakeResponse()

        with patch("app.main.httpx.AsyncClient", FakeAsyncClient):
            hits = asyncio.run(retrieve_knowledge(binding, "怎么重置密码"))

        self.assertEqual([{"snippet": "请通过忘记密码完成重置"}], hits)

    def test_configure_runtime_logger_attaches_console_handler(self) -> None:
        logger_name = "lynxus.agent_runtime"
        test_logger = logging.getLogger(logger_name)
        original_handlers = list(test_logger.handlers)
        original_level = test_logger.level
        original_propagate = test_logger.propagate

        for handler in list(test_logger.handlers):
            test_logger.removeHandler(handler)

        try:
            with patch.dict(os.environ, {"LYNXUS_AGENT_RUNTIME_LOG_LEVEL": "INFO"}, clear=False):
                configured = configure_runtime_logger()

            self.assertEqual(configured.name, "lynxus.agent_runtime")
            self.assertFalse(configured.propagate)
            self.assertTrue(configured.handlers)
            self.assertEqual(configured.level, logging.INFO)
        finally:
            for handler in list(test_logger.handlers):
                test_logger.removeHandler(handler)
            for handler in original_handlers:
                test_logger.addHandler(handler)
            test_logger.setLevel(original_level)
            test_logger.propagate = original_propagate

    def test_build_conversation_history_uses_recent_messages_and_skips_current_question(self) -> None:
        session_context = {
            "history": [
                {"role": "USER", "senderName": "用户", "content": "你好", "createdAt": "2026-03-24T08:31:20Z"},
                {"role": "ASSISTANT", "senderName": "助手", "content": "您好，我在。", "createdAt": "2026-03-24T08:32:20Z"},
                {"role": "USER", "senderName": "用户", "content": "订单号是 123", "createdAt": "2026-03-24T08:33:20Z"},
                {"role": "ASSISTANT", "senderName": "助手", "content": "已收到订单号。", "createdAt": "2026-03-24T08:34:20Z"},
                {"role": "USER", "senderName": "用户", "content": "可以帮我退款吗", "createdAt": "2026-03-24T08:35:20Z"},
            ]
        }

        history = build_conversation_history(session_context, "可以帮我退款吗", 2)

        self.assertEqual(
            history,
            "[USER][2026-03-24 08:33] 用户: 订单号是 123\n[ASSISTANT][2026-03-24 08:34] 助手: 已收到订单号。",
        )

    def test_build_runtime_context_block_contains_dynamic_context_with_minute_timestamps(self) -> None:
        prompt = build_runtime_context_block(
            "可以帮我退款吗",
            "2026-03-24 08:35",
            "[USER][2026-03-24 08:33] 用户: 订单号是 123",
            [],
            [{"operation": "evaluate_refund", "detail": "符合规则"}],
            {"human": "none"},
            {"customer": {"tier": "gold"}},
            {"refundCheck": {"status": "done"}},
            {"draft": {"step": "confirm"}},
            2,
        )

        self.assertIn("当前用户消息：", prompt)
        self.assertIn("[2026-03-24 08:35] 用户: 可以帮我退款吗", prompt)
        self.assertIn("可以帮我退款吗", prompt)
        self.assertIn("会话记忆：\n[USER][2026-03-24 08:33] 用户: 订单号是 123", prompt)
        self.assertIn("工具结果：", prompt)
        self.assertIn("共享事实（facts）：", prompt)
        self.assertIn("共享产物（artifacts）：", prompt)
        self.assertIn("当前智能体私有上下文（agentScope）：", prompt)
        self.assertIn("当前执行轮次：\n2", prompt)

    def test_build_structured_agent_prompt_splits_instruction_capability_and_runtime_blocks(self) -> None:
        skill_resources = [
            make_skill_resource("skill-v2", "售后技能", "用于售后策略", "请结合规则与工具结果判断售后策略。"),
        ]
        tool_resources = [make_tool_resource()]
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId="agent-1",
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认流转",
                    defaultEdge=True,
                )
            ],
        )

        prompt = build_structured_agent_prompt(
            "可以帮我退款吗",
            "2026-03-24 08:35",
            "[USER][2026-03-24 08:33] 用户: 订单号是 123",
            {"customer": {"tier": "gold"}},
            {"refundCheck": {"status": "done"}},
            {"draft": {"step": "confirm"}},
            available_skill_catalog(skill_resources),
            loaded_skill_details(skill_resources, {"loadedSkillResourceVersionIds": ["skill-v2"]}),
            [],
            [],
            None,
            tool_resources,
            available_routes_for_prompt(graph, "agent-node"),
            0,
        )

        self.assertIn('"decisionType"', prompt["instruction_block"])
        self.assertIn('"decisionSemantics"', prompt["instruction_block"])
        self.assertIn('"sessionStatePatch"', prompt["instruction_block"])
        self.assertIn('"routeKey": "default"', prompt["capability_block"])
        self.assertIn('"targetNodeKey": "end"', prompt["capability_block"])
        self.assertIn('"toolResourceVersionId": "tool-v1"', prompt["capability_block"])
        self.assertIn("请结合规则与工具结果判断售后策略。", prompt["capability_block"])
        self.assertIn("[2026-03-24 08:35] 用户: 可以帮我退款吗", prompt["runtime_context_block"])
        self.assertIn('"customer": {', prompt["runtime_context_block"])

    def test_build_structured_agent_prompt_only_changes_runtime_block_for_question_change(self) -> None:
        prompt_one = build_structured_agent_prompt(
            "问题 A",
            "2026-03-24 08:35",
            "[USER][2026-03-24 08:33] 用户: 历史 A",
            {},
            {},
            {},
            [],
            [],
            [],
            [],
            None,
            [],
            [],
            0,
        )
        prompt_two = build_structured_agent_prompt(
            "问题 B",
            "2026-03-24 08:36",
            "[USER][2026-03-24 08:33] 用户: 历史 A",
            {},
            {},
            {},
            [],
            [],
            [],
            [],
            None,
            [],
            [],
            0,
        )

        self.assertEqual(prompt_one["instruction_block"], prompt_two["instruction_block"])
        self.assertEqual(prompt_one["capability_block"], prompt_two["capability_block"])
        self.assertNotEqual(prompt_one["runtime_context_block"], prompt_two["runtime_context_block"])

    def test_build_structured_agent_prompt_only_changes_capability_block_for_loaded_skill_change(self) -> None:
        skill_resources = [
            make_skill_resource("skill-v1", "FAQ 技能", "用于 FAQ 回答", "请基于知识库直接回答 FAQ。"),
            make_skill_resource("skill-v2", "售后技能", "用于售后策略", "请结合规则与工具结果判断售后策略。"),
        ]

        prompt_one = build_structured_agent_prompt(
            "问题 A",
            "2026-03-24 08:35",
            "",
            {},
            {},
            {},
            available_skill_catalog(skill_resources),
            loaded_skill_details(skill_resources, {"loadedSkillResourceVersionIds": ["skill-v1"]}),
            [],
            [],
            None,
            [],
            [],
            0,
        )
        prompt_two = build_structured_agent_prompt(
            "问题 A",
            "2026-03-24 08:35",
            "",
            {},
            {},
            {},
            available_skill_catalog(skill_resources),
            loaded_skill_details(skill_resources, {"loadedSkillResourceVersionIds": ["skill-v2"]}),
            [],
            [],
            None,
            [],
            [],
            0,
        )

        self.assertEqual(prompt_one["instruction_block"], prompt_two["instruction_block"])
        self.assertEqual(prompt_one["runtime_context_block"], prompt_two["runtime_context_block"])
        self.assertNotEqual(prompt_one["capability_block"], prompt_two["capability_block"])

    def test_format_timestamp_to_minute_normalizes_iso_timestamp(self) -> None:
        self.assertEqual("2026-03-24 08:35", format_timestamp_to_minute("2026-03-24T08:35:20Z"))

    def test_memory_window_for_agent_respects_assistant_toggle(self) -> None:
        assistant = make_assistant(memory_enabled=False, memory_window_size=10)
        agent = make_agent(memory_window_size=6)

        self.assertEqual(memory_window_for_agent(assistant, agent), 0)

    def test_build_system_prompt_does_not_fallback_to_responsibility(self) -> None:
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "responsibility": "这是给人看的职责说明",
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={"systemPrompt": ""}
                ),
            }
        )

        prompt = build_system_prompt(agent)

        self.assertIn("你是企业级智能体执行节点，可能扮演不同角色。", prompt)
        self.assertNotIn("这是给人看的职责说明", prompt)
        self.assertNotIn("2026-", prompt)

    def test_call_llm_openai_uses_structured_prompt_blocks(self) -> None:
        model_resource = make_model_resource()
        captured_request: dict = {}

        class FakeResponse:
            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"choices": [{"message": {"content": "{\"decisionType\":\"FINAL\"}"}}]}

        class FakeAsyncClient:
            def __init__(self, *args, **kwargs) -> None:
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, tb) -> bool:
                return False

            async def post(self, url: str, headers: dict | None = None, json: dict | None = None) -> FakeResponse:
                captured_request["url"] = url
                captured_request["headers"] = headers
                captured_request["json"] = json
                return FakeResponse()

        prompt_payload = {
            "system_prompt": "system",
            "instruction_block": "instruction",
            "capability_block": "capability",
            "runtime_context_block": "runtime",
        }

        with patch("app.main.httpx.AsyncClient", FakeAsyncClient), patch.dict(os.environ, {"DUMMY_KEY": "test-key"}, clear=False):
            content = asyncio.run(call_llm(model_resource, prompt_payload))

        self.assertEqual("{\"decisionType\":\"FINAL\"}", content)
        self.assertEqual(
            [
                {"role": "system", "content": "system"},
                {"role": "user", "content": "instruction"},
                {"role": "user", "content": "capability"},
                {"role": "user", "content": "runtime"},
            ],
            captured_request["json"]["messages"],
        )

    def test_prompt_user_messages_keep_stable_order(self) -> None:
        self.assertEqual(
            ["instruction", "capability", "runtime"],
            prompt_user_messages(
                {
                    "system_prompt": "system",
                    "instruction_block": "instruction",
                    "capability_block": "capability",
                    "runtime_context_block": "runtime",
                }
            ),
        )

    def test_merge_loaded_skills_is_deduplicated(self) -> None:
        session_context = {"loadedSkillResourceVersionIds": ["skill-v1"]}

        merged = merge_loaded_skills(session_context, ["skill-v1", "skill-v2"])

        self.assertEqual(merged, ["skill-v1", "skill-v2"])
        self.assertEqual(session_context["loadedSkillResourceVersionIds"], ["skill-v1", "skill-v2"])

    def test_execute_agent_node_applies_session_state_patch(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={
                        "modelResourceVersionId": model_resource.resourceVersionId,
                    }
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="节点", nodeType="AGENT", description="说明", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-1", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="默认", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph, question="请继续处理")

        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "处理完成"}}],
                  "routeDecision": "default",
                  "sessionStatePatch": {
                    "ops": [
                      {"target": "FACTS", "op": "UPSERT", "path": ["entities", "ticketRef"], "value": "T-001"},
                      {"target": "ARTIFACTS", "op": "UPSERT", "path": ["refund", "status"], "value": "approved"},
                      {"target": "AGENT_SCOPE", "op": "UPSERT", "path": ["draft", "nextStep"], "value": "notify-user"}
                    ]
                  }
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        shared_state = state["session_context"]["sharedState"]
        self.assertEqual(shared_state["facts"]["entities"]["ticketRef"], "T-001")
        self.assertEqual(shared_state["artifacts"]["refund"]["status"], "approved")
        self.assertEqual(shared_state["agentScopes"][agent.agentId]["draft"]["nextStep"], "notify-user")

    def test_execute_agent_node_records_assistant_default_model_hit(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="节点", nodeType="AGENT", description="说明", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-1", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="默认", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph)

        with patch(
            "app.main.call_llm",
            AsyncMock(return_value='{"decisionType":"FINAL","outputMessages":[{"payloadType":"TEXT","payload":{"text":"处理完成"}}],"routeDecision":"default"}'),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertEqual(len(state["model_hits"]), 1)
        self.assertEqual(state["model_hits"][0]["source"], "ASSISTANT_DEFAULT")
        self.assertEqual(state["model_hits"][0]["resourceVersionId"], model_resource.resourceVersionId)
        self.assertEqual(state["model_hits"][0]["turnIndex"], 1)

    def test_execute_agent_node_records_agent_override_model_hit_in_workflow_result(self) -> None:
        default_model_resource = make_model_resource("model-default-v1")
        override_model_resource = make_model_resource("model-override-v1").model_copy(
            update={"resourceId": "resource-model-override", "resourceName": "覆盖模型"}
        )
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={
                        "inheritAssistantDefaults": False,
                        "modelResourceId": override_model_resource.resourceId,
                        "modelResourceVersionId": override_model_resource.resourceVersionId,
                    }
                )
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="节点", nodeType="AGENT", description="说明", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-1", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="默认", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [default_model_resource, override_model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=default_model_resource.resourceId,
                    providerResourceVersionId=default_model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph)

        with patch(
            "app.main.call_llm",
            AsyncMock(return_value='{"decisionType":"FINAL","outputMessages":[{"payloadType":"TEXT","payload":{"text":"处理完成"}}],"routeDecision":"default"}'),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        result = workflow_result_from_state(state)
        self.assertEqual(len(result.modelHits), 1)
        self.assertEqual(result.modelHits[0].source, "AGENT_OVERRIDE")
        self.assertEqual(result.modelHits[0].resourceId, override_model_resource.resourceId)
        self.assertEqual(result.modelHits[0].resourceVersionId, override_model_resource.resourceVersionId)

    def test_graph_execution_advances_from_start_to_agent_to_end_with_text_output(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="处理节点", nodeType="AGENT", description="执行处理", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent-node", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-end", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="完成", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph, question="帮我处理退款")

        execute_start_node(state, graph.nodes[0])
        self.assertEqual(state["current_node_key"], "start")
        self.assertEqual(state["next_node_key"], "agent-node")

        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "退款已处理完成。"}}],
                  "routeDecision": "default"
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertEqual(state["current_node_key"], "agent-node")
        self.assertEqual(state["next_node_key"], "end")
        self.assertEqual(state["output_messages"][0]["messageKey"], "agent-node:1:1")
        self.assertEqual(state["output_messages"][0]["payload"]["text"], "退款已处理完成。")
        self.assertEqual(state["summary"], "退款已处理完成。")

        execute_end_node(state, graph.nodes[2])
        result = workflow_result_from_state(state)

        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(result.currentNodeKey, "end")
        self.assertEqual([node.nodeKey for node in result.nodes], ["start", "agent-node", "end"])
        self.assertEqual(result.outputMessages[0].messageKey, "agent-node:1:1")
        self.assertEqual(result.outputMessages[0].payload["text"], "退款已处理完成。")

    def test_graph_execution_uses_route_decision_to_select_target_branch(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="分流节点", nodeType="AGENT", description="判断去向", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="approved-end", nodeName="通过结束", nodeType="END", description="通过"),
                GraphNodeSnapshot(nodeKey="rejected-end", nodeName="拒绝结束", nodeType="END", description="拒绝"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent-node", routeKey="default", label="进入处理", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-default", sourceNodeKey="agent-node", targetNodeKey="approved-end", routeKey="default", label="默认通过", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-reject", sourceNodeKey="agent-node", targetNodeKey="rejected-end", routeKey="reject", label="驳回", defaultEdge=False),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph, question="这个退款不符合条件")

        execute_start_node(state, graph.nodes[0])
        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "不满足退款条件。"}}],
                  "routeDecision": "reject"
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertEqual(state["route_key"], "reject")
        self.assertEqual(state["next_node_key"], "rejected-end")

        execute_end_node(state, graph.nodes[3])
        result = workflow_result_from_state(state)

        self.assertEqual(result.currentNodeKey, "rejected-end")
        self.assertEqual(result.summary, "不满足退款条件。")
        self.assertEqual(result.outputMessages[0].payload["text"], "不满足退款条件。")

    def test_execute_agent_node_pauses_for_invalid_session_state_patch(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={"modelResourceVersionId": model_resource.resourceVersionId}
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="节点", nodeType="AGENT", description="说明", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-1", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="默认", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph, question="请审核")
        state["session_context"]["sharedState"]["facts"] = {"existing": "value"}

        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "处理完成"}}],
                  "routeDecision": "default",
                  "sessionStatePatch": {
                    "ops": [
                      {"target": "FACTS", "op": "UPSERT", "path": ["existing", "child"], "value": "broken"}
                    ]
                  }
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertIsNotNone(state["resume_task"])
        self.assertIn("MODEL_OUTPUT_INVALID", state["summary"])
        self.assertIsNotNone(state["latest_failure"])
        self.assertEqual("PARSING_FAILURE", state["latest_failure"]["category"])
        self.assertEqual("MODEL_OUTPUT_INVALID", state["latest_failure"]["code"])

    def test_parse_agent_structured_response_rejects_invalid_skill_reads(self) -> None:
        skill_resource = make_skill_resource("skill-v1", "FAQ 技能", "用于 FAQ 回答", "请基于知识库直接回答 FAQ。")
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="节点",
                    nodeType="AGENT",
                    description="说明",
                    agentId=agent.agentId,
                )
            ],
            edges=[],
        )
        node = graph.nodes[0]
        state = make_agent_state(make_assistant(True, 4), graph, question="你好")
        llm_output = """{
          "decisionType": "FINAL",
          "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "读取技能"}}],
          "skillReads": ["skill-v1", "skill-v9"]
        }"""

        with self.assertRaises(AgentTurnError) as captured:
            parse_agent_structured_response(
                llm_output,
                graph,
                node,
                [skill_resource],
                [],
            )

        self.assertEqual(captured.exception.code, "MODEL_OUTPUT_INVALID")

    def test_parse_agent_structured_response_rejects_external_interaction_projection_fields(self) -> None:
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="节点",
                    nodeType="AGENT",
                    description="说明",
                    agentId=agent.agentId,
                )
            ],
            edges=[],
        )
        node = graph.nodes[0]
        llm_output = """{
          "decisionType": "FINAL",
          "outputMessages": [{
            "payloadType": "EXTERNAL_INTERACTION",
            "payload": {
              "spec": {
                "interactionType": "OAUTH_REDIRECT",
                "title": "完成授权",
                "instruction": "请前往授权页面。",
                "provider": "oauth-demo",
                "providerReference": "ref-1",
                "launchUrl": "https://example.com/oauth",
                "returnPath": "/console/runtime",
                "expiresAt": null,
                "primaryActionLabel": "去授权",
                "secondaryActions": [],
                "displayHints": {}
              },
              "projection": {
                "status": "SUCCEEDED"
              }
            }
          }]
        }"""

        with self.assertRaises(AgentTurnError) as captured:
            parse_agent_structured_response(
                llm_output,
                graph,
                node,
                [],
                [],
            )

        self.assertEqual(captured.exception.code, "MODEL_OUTPUT_INVALID")
        self.assertIn("unsupported fields: projection", captured.exception.message)

    def test_parse_agent_structured_response_rejects_external_interaction_when_not_last(self) -> None:
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="节点",
                    nodeType="AGENT",
                    description="说明",
                    agentId=agent.agentId,
                )
            ],
            edges=[],
        )
        node = graph.nodes[0]
        llm_output = """{
          "decisionType": "FINAL",
          "outputMessages": [
            {
              "payloadType": "EXTERNAL_INTERACTION",
              "payload": {
                "spec": {
                  "interactionType": "GENERIC_REDIRECT",
                  "title": "处理工单",
                  "instruction": "请先处理。",
                  "provider": null,
                  "providerReference": null,
                  "launchUrl": null,
                  "returnPath": null,
                  "expiresAt": null,
                  "primaryActionLabel": null,
                  "secondaryActions": [],
                  "displayHints": {}
                }
              }
            },
            {
              "payloadType": "TEXT",
              "payload": {
                "text": "返回后继续"
              }
            }
          ]
        }"""

        with self.assertRaises(AgentTurnError) as captured:
            parse_agent_structured_response(
                llm_output,
                graph,
                node,
                [],
                [],
            )

        self.assertEqual(captured.exception.code, "MODEL_OUTPUT_INVALID")
        self.assertIn("must be the last", captured.exception.message)

    def test_parse_agent_structured_response_rejects_multiple_external_interactions(self) -> None:
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="节点",
                    nodeType="AGENT",
                    description="说明",
                    agentId=agent.agentId,
                )
            ],
            edges=[],
        )
        node = graph.nodes[0]
        llm_output = """{
          "decisionType": "FINAL",
          "outputMessages": [
            {
              "payloadType": "TEXT",
              "payload": {
                "text": "先说明"
              }
            },
            {
              "payloadType": "EXTERNAL_INTERACTION",
              "payload": {
                "spec": {
                  "interactionType": "GENERIC_REDIRECT",
                  "title": "外部处理一",
                  "instruction": "先处理第一步。",
                  "provider": null,
                  "providerReference": null,
                  "launchUrl": null,
                  "returnPath": null,
                  "expiresAt": null,
                  "primaryActionLabel": null,
                  "secondaryActions": [],
                  "displayHints": {}
                }
              }
            },
            {
              "payloadType": "EXTERNAL_INTERACTION",
              "payload": {
                "spec": {
                  "interactionType": "FORM_REDIRECT",
                  "title": "外部处理二",
                  "instruction": "再处理第二步。",
                  "provider": null,
                  "providerReference": null,
                  "launchUrl": null,
                  "returnPath": null,
                  "expiresAt": null,
                  "primaryActionLabel": null,
                  "secondaryActions": [],
                  "displayHints": {}
                }
              }
            }
          ]
        }"""

        with self.assertRaises(AgentTurnError) as captured:
            parse_agent_structured_response(
                llm_output,
                graph,
                node,
                [],
                [],
            )

        self.assertEqual(captured.exception.code, "MODEL_OUTPUT_INVALID")
        self.assertIn("at most one EXTERNAL_INTERACTION", captured.exception.message)

    def test_execute_agent_node_supports_skill_reads_and_tool_requests_in_same_round(self) -> None:
        skill_resource = make_skill_resource(
            "skill-v2",
            "售后技能",
            "用于售后策略",
            "请结合规则与工具结果判断售后策略。",
        )
        model_resource = make_model_resource()
        tool_resource = make_tool_resource()
        agent = AgentSnapshot(
            agentId="agent-1",
            name="after-sales",
            role="support",
            responsibility="处理售后请求",
            executionPolicy=AgentExecutionPolicySnapshot(
                inheritAssistantDefaults=True,
                modelResourceId=None,
                modelResourceVersionId=None,
                systemPrompt="你是售后策略智能体",
                ragEnabled=False,
                inheritAssistantKnowledge=False,
                knowledge=None,
                memoryWindowSize=4,
                skillResourceIds=[skill_resource.resourceId],
                skillResourceVersionIds=[skill_resource.resourceVersionId],
                toolResourceIds=[tool_resource.resourceId],
                toolResourceVersionIds=[tool_resource.resourceVersionId],
            ),
        )
        assistant = make_assistant(memory_enabled=True, memory_window_size=8).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource, skill_resource, tool_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=8,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                )
            ],
        )
        state = make_agent_state(assistant, graph)
        node = graph.nodes[1]
        llm_outputs = [
            """{
              "decisionType": "TOOL_CALL",
              "message": "先读取技能并调用工具。",
              "skillReads": ["skill-v2"],
              "toolRequests": [
                {
                  "toolResourceVersionId": "tool-v1",
                  "operation": "evaluate_refund",
                  "arguments": {"question": "可以帮我退款吗"}
                }
              ]
            }""",
            """{
              "decisionType": "FINAL",
              "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "可以按标准退款流程处理。"}}],
              "routeDecision": "default"
            }""",
        ]
        mock_llm = AsyncMock(side_effect=llm_outputs)
        mock_tool = AsyncMock(
            return_value=(
                tool_resource.configuration.tool.operations[0],
                {
                    "status": "COMPLETED",
                    "eligibility": "APPROVED",
                    "resolution": "STANDARD_REFUND",
                    "reason": "订单符合规则，可直接退款。",
                },
            )
        )

        with patch("app.main.call_llm", mock_llm), patch("app.main.call_tool", mock_tool):
            asyncio.run(execute_agent_node(state, node))

        self.assertEqual(state["session_context"]["loadedSkillResourceVersionIds"], ["skill-v2"])
        self.assertEqual(len(state["tool_history"]), 1)
        self.assertEqual(len(state["output_messages"]), 1)
        self.assertEqual(state["output_messages"][0]["payloadType"], "TEXT")
        self.assertEqual(state["output_messages"][0]["messageKey"], "agent-node:2:1")
        self.assertEqual(state["output_messages"][0]["payload"]["text"], "可以按标准退款流程处理。")
        self.assertEqual(state["summary"], "可以按标准退款流程处理。")
        self.assertEqual(
            state["latest_tool_outcome"]["result"],
            {
                "status": "COMPLETED",
                "eligibility": "APPROVED",
                "resolution": "STANDARD_REFUND",
                "reason": "订单符合规则，可直接退款。",
            },
        )

        first_prompt = mock_llm.await_args_list[0].args[1]
        second_prompt = mock_llm.await_args_list[1].args[1]
        self.assertIn("可用技能目录：", first_prompt["capability_block"])
        self.assertNotIn("请结合规则与工具结果判断售后策略。", first_prompt["capability_block"])
        self.assertIn("已加载技能详情：", second_prompt["capability_block"])
        self.assertIn("请结合规则与工具结果判断售后策略。", second_prompt["capability_block"])
        self.assertIn("工具结果：", second_prompt["runtime_context_block"])
        self.assertIn('"result"', second_prompt["runtime_context_block"])
        self.assertEqual(mock_tool.await_count, 1)

    def test_graph_execution_external_interaction_pauses_with_structured_output_message(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="agent-node", nodeName="授权节点", nodeType="AGENT", description="发起授权", agentId=agent.agentId),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-start", sourceNodeKey="start", targetNodeKey="agent-node", routeKey="default", label="进入授权", defaultEdge=True),
                GraphEdgeSnapshot(edgeKey="edge-end", sourceNodeKey="agent-node", targetNodeKey="end", routeKey="default", label="授权完成", defaultEdge=True),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        state = make_agent_state(assistant, graph, question="请完成授权")

        execute_start_node(state, graph.nodes[0])
        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{
                    "payloadType": "EXTERNAL_INTERACTION",
                    "payload": {
                      "spec": {
                        "interactionType": "OAUTH_REDIRECT",
                        "title": "完成授权",
                        "instruction": "请前往授权页面完成授权。",
                        "provider": "oauth-demo",
                        "providerReference": "oauth-ref-1",
                        "launchUrl": "https://example.com/oauth",
                        "returnPath": "/console/runtime",
                        "expiresAt": null,
                        "primaryActionLabel": "去授权",
                        "secondaryActions": [],
                        "displayHints": {}
                      }
                    }
                  }],
                  "routeDecision": "default"
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        result = workflow_result_from_state(state)

        self.assertEqual(result.status, "WAITING_RESUME")
        self.assertEqual(result.currentNodeKey, "agent-node")
        self.assertEqual(result.outputMessages[0].messageKey, "agent-node:1:1")
        self.assertEqual(result.outputMessages[0].payloadType, "EXTERNAL_INTERACTION")
        self.assertEqual(result.outputMessages[0].payload["spec"]["interactionType"], "OAUTH_REDIRECT")
        self.assertEqual(result.resumeTask.source, "EXTERNAL_INTERACTION")
        self.assertEqual(result.pauseReason.code, "EXTERNAL_INTERACTION_REQUIRED")
        self.assertEqual(result.checkpoint.currentNodeKey, "end")

    def test_execute_agent_node_supports_skill_read_only_turn(self) -> None:
        skill_resource = make_skill_resource(
            "skill-v2",
            "售后规则",
            "售后处理规则",
            "请结合规则与上下文判断售后策略。",
        )
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={
                        "skillResourceIds": [skill_resource.resourceId],
                        "skillResourceVersionIds": [skill_resource.resourceVersionId],
                    }
                )
            }
        )
        assistant = make_assistant(memory_enabled=True, memory_window_size=4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource, skill_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-start",
                    sourceNodeKey="start",
                    targetNodeKey="agent-node",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
                GraphEdgeSnapshot(
                    edgeKey="edge-end",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
            ],
        )
        state = make_agent_state(assistant, graph)
        llm_outputs = [
            f"""{{
              "decisionType": "{AGENT_DECISION_SKILL_READ}",
              "message": "先读取售后规则。",
              "skillReads": ["skill-v2"]
            }}""",
            """{
              "decisionType": "FINAL",
              "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "可以按照售后规则处理。"}}],
              "routeDecision": "default"
            }""",
        ]

        with patch("app.main.call_llm", AsyncMock(side_effect=llm_outputs)):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertEqual(state["session_context"]["loadedSkillResourceVersionIds"], ["skill-v2"])
        self.assertEqual(state["output_messages"][0]["payload"]["text"], "可以按照售后规则处理。")

    def test_parse_agent_structured_response_rejects_final_with_skill_reads(self) -> None:
        skill_resource = make_skill_resource("skill-v1", "FAQ 技能", "用于 FAQ 回答", "请基于知识库直接回答 FAQ。")
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="节点",
                    nodeType="AGENT",
                    description="说明",
                    agentId=agent.agentId,
                )
            ],
            edges=[],
        )
        state = make_agent_state(
            make_assistant(True, 4).model_copy(update={"resources": [model_resource, skill_resource]}),
            graph,
            question="你好",
        )
        llm_output = """{
          "decisionType": "FINAL",
          "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "读取技能"}}],
          "skillReads": ["skill-v1"]
        }"""

        with self.assertRaises(AgentTurnError) as captured:
            parse_agent_structured_response(
                llm_output,
                graph,
                graph.nodes[0],
                [skill_resource],
                [],
            )

        self.assertIn("FINAL decisionType must not include skillReads", captured.exception.message)

    def test_execute_agent_node_reuses_ticket_reference_on_followup_tool_call(self) -> None:
        model_resource = make_model_resource()
        tool_resource = make_tool_resource(
            operations=[
                ToolOperationConfig(name="create_ticket", description="创建工单"),
                ToolOperationConfig(name="append_comment", description="追加备注"),
            ]
        )
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={
                        "toolResourceIds": [tool_resource.resourceId],
                        "toolResourceVersionIds": [tool_resource.resourceVersionId],
                    }
                )
            }
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource, tool_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
                "graph": GraphSnapshot(
                    executionMode="GRAPH",
                    nodes=[
                        GraphNodeSnapshot(
                            nodeKey="agent-node",
                            nodeName="售后节点",
                            nodeType="AGENT",
                            description="售后处理",
                            agentId=agent.agentId,
                        ),
                        GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
                    ],
                    edges=[
                        GraphEdgeSnapshot(
                            edgeKey="edge-1",
                            sourceNodeKey="agent-node",
                            targetNodeKey="end",
                            routeKey="default",
                            label="默认",
                            defaultEdge=True,
                        )
                    ],
                ),
            }
        )
        graph = assistant.graph
        state = make_agent_state(assistant, graph, question="我要人工跟进")
        llm_outputs = [
            """{
              "decisionType": "TOOL_CALL",
              "message": "先创建工单。",
              "toolRequests": [
                {"toolResourceVersionId": "tool-v1", "operation": "create_ticket", "arguments": {"question": "我要人工跟进"}}
              ]
            }""",
            """{
              "decisionType": "TOOL_CALL",
              "message": "补充人工说明。",
              "toolRequests": [
                {"toolResourceVersionId": "tool-v1", "operation": "append_comment", "arguments": {"comment": "请尽快处理"}}
              ]
            }""",
            """{
              "decisionType": "FINAL",
              "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "已经记录工单并补充备注。"}}],
              "routeDecision": "default"
            }""",
        ]
        tool_results = [
            (
                tool_resource.configuration.tool.operations[0],
                {"status": "ACCEPTED", "ticketId": "TICKET-1001", "message": "工单已创建。"},
            ),
            (
                tool_resource.configuration.tool.operations[1],
                {"status": "COMPLETED", "message": "备注已追加。"},
            ),
        ]
        mock_llm = AsyncMock(side_effect=llm_outputs)
        mock_tool = AsyncMock(side_effect=tool_results)

        with patch("app.main.call_llm", mock_llm), patch("app.main.call_tool", mock_tool):
            asyncio.run(execute_agent_node(state, graph.nodes[0]))

        second_tool_payload = mock_tool.await_args_list[1].args[2]
        self.assertEqual(second_tool_payload, {"comment": "请尽快处理"})
        self.assertEqual(len(state["tool_history"]), 2)
        self.assertEqual(state["output_messages"][0]["payload"]["text"], "已经记录工单并补充备注。")
        self.assertEqual(state["summary"], "已经记录工单并补充备注。")
        self.assertEqual(state["latest_tool_outcome"]["result"], {"status": "COMPLETED", "message": "备注已追加。"})

    def test_execute_agent_node_non_json_output_pauses_for_human(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                )
            ],
        )
        state = make_agent_state(assistant, graph, question="帮我处理")

        with patch("app.main.call_llm", AsyncMock(return_value="not json")):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertIsNotNone(state["resume_task"])
        self.assertEqual(state["resume_task"]["source"], AGENT_HUMAN_TASK_SOURCE)
        self.assertEqual(state["next_node_key"], "__end__")
        self.assertIn("MODEL_OUTPUT_INVALID", state["summary"])
        self.assertEqual(state["checkpoint"]["currentNodeKey"], "agent-node")

    def test_execute_agent_node_missing_route_decision_pauses_when_no_default_edge(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="resolved",
                    label="已解决",
                    defaultEdge=False,
                )
            ],
        )
        state = make_agent_state(assistant, graph, question="帮我处理")

        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "FINAL",
                  "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "处理完成"}}]
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertIsNotNone(state["resume_task"])
        self.assertIn("ROUTE_INVALID", state["summary"])
        self.assertIsNotNone(state["latest_failure"])
        self.assertEqual("RUNTIME_FAILURE", state["latest_failure"]["category"])
        self.assertEqual("ROUTE_INVALID", state["latest_failure"]["code"])

    def test_execute_agent_node_missing_model_resource_pauses_for_human(self) -> None:
        agent = make_agent(memory_window_size=4)
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=None,
                    providerResourceVersionId=None,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                )
            ],
        )
        state = make_agent_state(assistant, graph, question="帮我处理")

        asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertIsNotNone(state["resume_task"])
        self.assertEqual(state["resume_task"]["source"], AGENT_HUMAN_TASK_SOURCE)
        self.assertEqual(state["next_node_key"], "__end__")
        self.assertIsNotNone(state["latest_failure"])
        self.assertEqual("CONFIGURATION_FAILURE", state["latest_failure"]["category"])
        self.assertEqual("MODEL_RESOURCE_MISSING", state["latest_failure"]["code"])
        self.assertEqual("agent-node", state["latest_failure"]["failedNodeKey"])

    def test_execute_agent_node_tool_failure_pauses_for_human(self) -> None:
        model_resource = make_model_resource()
        tool_resource = make_tool_resource()
        agent = make_agent(memory_window_size=4).model_copy(
            update={
                "executionPolicy": make_agent(memory_window_size=4).executionPolicy.model_copy(
                    update={
                        "toolResourceIds": [tool_resource.resourceId],
                        "toolResourceVersionIds": [tool_resource.resourceVersionId],
                    }
                )
            }
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource, tool_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                )
            ],
        )
        state = make_agent_state(assistant, graph)
        llm_output = """{
          "decisionType": "TOOL_CALL",
          "message": "调用工具",
          "toolRequests": [
            {"toolResourceVersionId": "tool-v1", "operation": "evaluate_refund", "arguments": {"question": "可以帮我退款吗"}}
          ]
        }"""

        with patch("app.main.call_llm", AsyncMock(return_value=llm_output)), patch(
            "app.main.call_tool",
            AsyncMock(side_effect=RuntimeError("tool failed")),
        ):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertIsNotNone(state["resume_task"])
        self.assertEqual(state["resume_task"]["source"], AGENT_HUMAN_TASK_SOURCE)
        self.assertEqual(state["next_node_key"], "__end__")
        self.assertIsNotNone(state["latest_failure"])
        self.assertEqual("TOOL_FAILURE", state["latest_failure"]["category"])
        self.assertEqual("TOOL_EXECUTION_FAILED", state["latest_failure"]["code"])
        self.assertEqual(tool_resource.resourceId, state["latest_failure"]["failedResourceId"])

    def test_execute_agent_node_human_request_creates_checkpoint(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-1",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                )
            ],
        )
        state = make_agent_state(assistant, graph)
        llm_output = """{
          "decisionType": "HUMAN_HANDOFF",
          "message": "需要人工核查",
          "humanRequest": {
            "title": "人工核查",
            "instruction": "请人工确认退款凭证",
            "expectedAction": "补充核查结果"
          }
        }"""

        with patch("app.main.call_llm", AsyncMock(return_value=llm_output)):
            asyncio.run(execute_agent_node(state, graph.nodes[1]))

        self.assertEqual(state["resume_task"]["title"], "人工核查")
        self.assertEqual(state["resume_task"]["source"], AGENT_HUMAN_TASK_SOURCE)
        self.assertEqual(state["checkpoint"]["currentNodeKey"], "agent-node")
        checkpoint_payload = __import__("json").loads(state["checkpoint"]["statePayload"])
        self.assertEqual(checkpoint_payload["pause_reason"]["code"], "HUMAN_HANDOFF_REQUESTED")
        self.assertIn("请人工确认退款凭证", checkpoint_payload["pause_reason"]["detail"])
        self.assertIsNone(checkpoint_payload["latest_failure"])

    def test_agent_handoff_resume_reenters_agent_with_resume_input(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-start",
                    sourceNodeKey="start",
                    targetNodeKey="agent-node",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
                GraphEdgeSnapshot(
                    edgeKey="edge-end",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        session_context = SessionContext(
            sessionId="session-1",
            customerId="u-1",
            latestMessage=text_message_snapshot("帮我处理退款"),
            history=[],
            loadedSkillResourceVersionIds=[],
        )
        llm_outputs = [
            """{
              "decisionType": "HUMAN_HANDOFF",
              "message": "需要人工补充信息",
              "humanRequest": {
                "title": "补充信息",
                "instruction": "请人工补充订单状态",
                "expectedAction": "填写处理意见"
              }
            }""",
            """{
              "decisionType": "FINAL",
              "outputMessages": [{"payloadType": "TEXT", "payload": {"text": "已根据人工说明完成处理。"}}],
              "routeDecision": "default"
            }""",
        ]
        mock_llm = AsyncMock(side_effect=llm_outputs)
        initial_state = make_agent_state(assistant, graph, question="帮我处理退款")
        initial_state["workflow_instance_id"] = "wf-resume-agent"
        initial_state["session_context"] = session_context.model_dump(mode="json")

        with patch("app.main.call_llm", mock_llm):
            asyncio.run(execute_agent_node(initial_state, graph.nodes[1]))
            resume_request = WorkflowResumeRequest(
                taskId="task-1",
                workflowInstanceId="wf-resume-agent",
                scenarioId="scenario-1",
                action=ResumeAction(type="CONTINUE", source="HUMAN", comment="订单已签收，可退款", userId="user-2"),
                sessionContext=session_context,
                assistant=assistant,
                checkpoint=ExecutionCheckpoint(**initial_state["checkpoint"]),
            )
            resumed_state = restore_state(
                __import__("json").loads(initial_state["checkpoint"]["statePayload"]),
                resume_request,
            )
            asyncio.run(execute_agent_node(resumed_state, graph.nodes[1]))

        self.assertEqual(initial_state["resume_task"]["source"], AGENT_HUMAN_TASK_SOURCE)
        self.assertEqual(initial_state["resume_task"]["allowedActions"], ["CONTINUE", "TERMINATE"])
        self.assertEqual(initial_state["checkpoint"]["currentNodeKey"], "agent-node")
        self.assertIsNone(initial_state["latest_failure"])
        self.assertIsNone(resumed_state["resume_task"])
        self.assertFalse(resumed_state["escalation_required"])
        self.assertEqual(resumed_state["output_messages"][0]["payload"]["text"], "已根据人工说明完成处理。")
        second_prompt = mock_llm.await_args_list[1].args[1]
        self.assertIn("恢复输入：", second_prompt["runtime_context_block"])
        self.assertIn("订单已签收，可退款", second_prompt["runtime_context_block"])

    def test_graph_human_node_checkpoint_and_resume_state_still_work(self) -> None:
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="human",
                    nodeName="人工节点",
                    nodeType="HUMAN",
                    description="人工处理",
                    humanNode=HumanNodeConfig(
                        title="人工审核",
                        instruction="请人工审核订单",
                        expectedAction="填写审核结论",
                        resumeRouteKey="default",
                    ),
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-start",
                    sourceNodeKey="start",
                    targetNodeKey="human",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
                GraphEdgeSnapshot(
                    edgeKey="edge-approved",
                    sourceNodeKey="human",
                    targetNodeKey="end",
                    routeKey="default",
                    label="审核通过",
                    defaultEdge=True,
                ),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(update={"graph": graph})
        state = make_agent_state(assistant, graph, question="请审核订单")
        state["entry_node_key"] = "start"
        state["current_node_key"] = "start"

        execute_human_node(state, graph.nodes[1])

        self.assertEqual(state["resume_task"]["source"], "GRAPH_NODE")
        self.assertEqual(state["checkpoint"]["currentNodeKey"], "end")
        self.assertIsNone(state["latest_failure"])

        session_context = SessionContext(
            sessionId="session-1",
            customerId="u-1",
            latestMessage=text_message_snapshot("请审核订单"),
            history=[],
            loadedSkillResourceVersionIds=[],
        )
        resume_request = WorkflowResumeRequest(
            taskId="task-1",
            workflowInstanceId="wf-human",
            scenarioId="scenario-1",
            action=ResumeAction(type="CONTINUE", source="HUMAN", comment="审核通过", userId="user-2"),
            sessionContext=session_context,
            assistant=assistant,
            checkpoint=ExecutionCheckpoint(**state["checkpoint"]),
        )
        restored = restore_state(
            __import__("json").loads(state["checkpoint"]["statePayload"]),
            resume_request,
        )

        self.assertEqual(restored["current_node_key"], "end")
        self.assertEqual(restored["resume_input"]["comment"], "审核通过")
        self.assertEqual(restored["pause_reason"]["code"], "GRAPH_HUMAN_NODE")
        self.assertIsNone(restored["latest_failure"])

    def test_restore_state_prefers_resume_session_shared_state(self) -> None:
        assistant = make_assistant(True, 4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(edgeKey="edge-1", sourceNodeKey="start", targetNodeKey="end", routeKey="default", label="默认", defaultEdge=True),
            ],
        )
        state = make_agent_state(assistant.model_copy(update={"graph": graph}), graph, question="继续处理")
        state["session_context"]["sharedState"]["facts"] = {"fromCheckpoint": True}
        payload = {
            "question": state["question"],
            "session_context": state["session_context"],
            "assistant": state["assistant"],
            "graph": state["graph"],
            "summary": "",
            "output_messages": [],
            "retrieval_hits": [],
            "retrieval_cache": {},
            "tool_history": [],
            "tool_calls": [],
            "node_snapshots": [],
            "resume_task": None,
            "latest_tool_outcome": None,
            "escalation_required": False,
            "resume_count": 0,
            "agent_turn_state": {"phase": "IDLE", "turnIndex": 0, "turnLogs": []},
            "pause_reason": None,
            "workflow_status": "RUNNING",
        }
        session_context = SessionContext(
            sessionId="session-1",
            customerId="u-1",
            latestMessage=text_message_snapshot("继续处理"),
            history=[],
            loadedSkillResourceVersionIds=[],
            sharedState={"facts": {"fromResume": True}, "artifacts": {}, "agentScopes": {}},
        )
        resume_request = WorkflowResumeRequest(
            taskId="task-1",
            workflowInstanceId="wf-restore",
            scenarioId="scenario-1",
            action=ResumeAction(type="CONTINUE", source="HUMAN", comment="继续", userId="user-2"),
            sessionContext=session_context,
            assistant=assistant.model_copy(update={"graph": graph}),
            checkpoint=ExecutionCheckpoint(
                checkpointId="cp-1",
                currentNodeKey="end",
                waitingNodeKey="human",
                statePayload=__import__("json").dumps(payload),
                resumeCount=0,
            ),
        )

        restored = restore_state(payload, resume_request)

        self.assertEqual(restored["session_context"]["sharedState"]["facts"], {"fromResume": True})

    def test_resume_agent_run_terminate_cancels_workflow(self) -> None:
        model_resource = make_model_resource()
        agent = make_agent(memory_window_size=4)
        graph = GraphSnapshot(
            executionMode="GRAPH",
            nodes=[
                GraphNodeSnapshot(nodeKey="start", nodeName="开始", nodeType="START", description="开始"),
                GraphNodeSnapshot(
                    nodeKey="agent-node",
                    nodeName="售后节点",
                    nodeType="AGENT",
                    description="售后处理",
                    agentId=agent.agentId,
                ),
                GraphNodeSnapshot(nodeKey="end", nodeName="结束", nodeType="END", description="结束"),
            ],
            edges=[
                GraphEdgeSnapshot(
                    edgeKey="edge-start",
                    sourceNodeKey="start",
                    targetNodeKey="agent-node",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
                GraphEdgeSnapshot(
                    edgeKey="edge-end",
                    sourceNodeKey="agent-node",
                    targetNodeKey="end",
                    routeKey="default",
                    label="默认",
                    defaultEdge=True,
                ),
            ],
        )
        assistant = make_assistant(True, 4).model_copy(
            update={
                "agents": [agent],
                "resources": [model_resource],
                "graph": graph,
                "assistantPolicy": AssistantPolicySnapshot(
                    providerResourceId=model_resource.resourceId,
                    providerResourceVersionId=model_resource.resourceVersionId,
                    memoryEnabled=True,
                    memoryWindowSize=4,
                ),
            }
        )
        session_context = SessionContext(
            sessionId="session-1",
            customerId="u-1",
            latestMessage=text_message_snapshot("帮我处理退款"),
            history=[],
            loadedSkillResourceVersionIds=[],
        )
        initial_state = make_agent_state(assistant, graph, question="帮我处理退款")
        initial_state["workflow_instance_id"] = "wf-cancel"
        initial_state["session_context"] = session_context.model_dump(mode="json")
        initial_state["entry_node_key"] = "agent-node"
        with patch(
            "app.main.call_llm",
            AsyncMock(
                return_value="""{
                  "decisionType": "HUMAN_HANDOFF",
                  "message": "需要人工补充信息",
                  "humanRequest": {
                    "title": "补充信息",
                    "instruction": "请人工补充订单状态",
                    "expectedAction": "填写处理意见"
                  }
                }"""
            ),
        ):
            asyncio.run(execute_agent_node(initial_state, graph.nodes[1]))
            resume_request = WorkflowResumeRequest(
                taskId="task-1",
                workflowInstanceId="wf-cancel",
                scenarioId="scenario-1",
                action=ResumeAction(type="TERMINATE", source="HUMAN", comment="无需继续，直接关闭", userId="user-2"),
                sessionContext=session_context,
                assistant=assistant,
                checkpoint=ExecutionCheckpoint(**initial_state["checkpoint"]),
            )
            from app.main import resume_agent_run

            result = asyncio.run(resume_agent_run(resume_request))

        self.assertEqual(result.status, "CANCELLED")
        self.assertIsNone(result.resumeTask)
        self.assertIsNone(result.pauseReason)
        self.assertEqual(result.summary, "人工终止了当前流程。")
        self.assertEqual(result.outputMessages, [])


if __name__ == "__main__":
    unittest.main()
