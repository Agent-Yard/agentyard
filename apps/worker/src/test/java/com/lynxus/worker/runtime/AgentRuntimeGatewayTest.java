package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.LogContextHeaders;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRuntimeGatewayTest {
    @Test
    void shouldSerializeSessionHistoryInstantsAsIsoStrings() throws Exception {
        var payload = new WorkflowContracts.WorkflowStartRequest(
            "task-1",
            "wf-1",
            "scenario-customer-ops",
            "你好",
            "tester",
            new WorkflowContracts.SessionContext(
                "session-1",
                "tester",
                "你好",
                List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", "你好", Instant.parse("2026-03-24T08:35:20Z"))),
                List.of(),
                WorkflowContracts.SharedSessionState.empty()
            ),
            new WorkflowContracts.AssistantRunSnapshot(
                "assistant-customer-ops",
                "客户协同助手",
                "1.0.0",
                new WorkflowContracts.AssistantPolicySnapshot(null, null, false, 0),
                null,
                List.of(),
                List.of(),
                new WorkflowContracts.GraphSnapshot("GRAPH", List.of(), List.of())
            ),
            sampleLogContext("session-1", "wf-1")
        );

        String json = AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper().writeValueAsString(payload);

        assertTrue(json.contains("\"createdAt\":\"2026-03-24T08:35:20Z\""));
    }

    @Test
    void shouldDeserializeWorkflowResultWithHumanTaskSource() throws Exception {
        String payload = """
            {
              "workflowInstanceId": "wf-1",
              "status": "WAITING_HUMAN",
              "summary": "等待人工处理",
              "finalReply": "已进入人工协同流程。",
              "currentNodeKey": "human-review",
              "checkpoint": null,
              "humanTask": {
                "nodeKey": "human-review",
                "title": "人工介入待办",
                "instruction": "请人工确认并补充处理意见。",
                "expectedAction": "CONFIRM",
                "source": "AGENT_REQUEST",
                "allowedActions": ["CONFIRM", "TERMINATE"]
              },
              "pauseReason": {
                "code": "HUMAN_HANDOFF_REQUESTED",
                "detail": "请人工确认并补充处理意见。",
                "source": "AGENT_REQUEST"
              },
              "nodes": [],
              "toolCalls": [],
              "escalationRequired": true,
              "latestToolOutcome": null,
              "loadedSkillResourceVersionIds": [],
              "sharedState": {
                "facts": {},
                "artifacts": {},
                "agentScopes": {}
              }
            }
            """;

        WorkflowContracts.WorkflowResult result = AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper()
            .readValue(payload, WorkflowContracts.WorkflowResult.class);

        assertEquals("AGENT_REQUEST", result.humanTask().source());
    }

    @Test
    void shouldSendInternalBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> traceparent = new AtomicReference<>();
        AtomicReference<String> workflowId = new AtomicReference<>();
        AtomicReference<String> customerId = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-runs/start", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            traceparent.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.TRACEPARENT));
            workflowId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.WORKFLOW_ID));
            customerId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.CUSTOMER_ID));
            userId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.USER_ID));
            writeJson(
                exchange,
                """
                    {
                      "workflowInstanceId": "wf-1",
                      "status": "COMPLETED",
                      "summary": "流程已完成",
                      "finalReply": "完成",
                      "currentNodeKey": "end",
                      "checkpoint": null,
                      "humanTask": null,
                      "pauseReason": null,
                      "latestFailure": null,
                      "nodes": [],
                      "toolCalls": [],
                      "escalationRequired": false,
                      "latestToolOutcome": null,
                      "loadedSkillResourceVersionIds": [],
                      "sharedState": {
                        "facts": {},
                        "artifacts": {},
                        "agentScopes": {}
                      },
                      "agentTurnState": {
                        "phase": "IDLE",
                        "turnIndex": 0,
                        "latestDecision": null,
                        "turnLogs": []
                      }
                    }
                    """
            );
        });
        server.start();

        try {
            AgentRuntimeGateway gateway = new AgentRuntimeGateway.HttpAgentRuntimeGateway(
                "http://localhost:" + server.getAddress().getPort(),
                "internal-token",
                AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper()
            );

            gateway.start(new WorkflowContracts.WorkflowStartRequest(
                "task-1",
                "wf-1",
                "scenario-customer-ops",
                "你好",
                "tester",
                new WorkflowContracts.SessionContext(
                    "session-1",
                    "tester",
                    "你好",
                    List.of(),
                    List.of(),
                    WorkflowContracts.SharedSessionState.empty()
                ),
                new WorkflowContracts.AssistantRunSnapshot(
                    "assistant-customer-ops",
                    "客户协同助手",
                    "1.0.0",
                    new WorkflowContracts.AssistantPolicySnapshot(null, null, false, 0),
                    null,
                    List.of(),
                    List.of(),
                    new WorkflowContracts.GraphSnapshot("GRAPH", List.of(), List.of())
                ),
                sampleLogContext("session-1", "wf-1")
            ));

            assertEquals("Bearer internal-token", authorization.get());
            assertTrue(traceparent.get().startsWith("00-0123456789abcdef0123456789abcdef-"));
            assertEquals("wf-1", workflowId.get());
            assertEquals("customer-1", customerId.get());
            assertEquals("user-1", userId.get());
        } finally {
            server.stop(0);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static WorkflowContracts.LogContext sampleLogContext(String sessionId, String workflowId) {
        return new WorkflowContracts.LogContext(
            "0123456789abcdef0123456789abcdef",
            sessionId,
            workflowId,
            "customer-1",
            "user-1"
        );
    }
}
