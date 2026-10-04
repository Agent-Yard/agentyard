package com.agentyard.platform.task;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic OpenAI-compatible SSE provider. The real Python tool loop executes every emitted call. */
final class TaskModelFixture implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    final HttpServer server;
    private final ObjectMapper mapper;

    TaskModelFixture(ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                JsonNode messages = mapper.readTree(body).path("messages");
                JsonNode lastTool = null;
                for (JsonNode message : messages) {
                    if ("tool".equals(message.path("role").asText())) lastTool = message;
                }
                Map<String, Object> delta;
                String finish;
                if (lastTool == null) {
                    delta = tool("task_read_context", Map.of()); finish = "tool_calls";
                } else {
                    JsonNode result = mapper.readTree(lastTool.path("content").asText());
                    if (result.has("context")) {
                        if (body.contains("DECOMPOSE")) {
                            delta = tool("task_contribute", Map.of("requestId", UUID.randomUUID().toString(),
                                "expectedRevision", result.path("context").path("task").path("revision").asLong(),
                                "kind", "CREATE_CHILD", "data", Map.of("title", "Validate feasibility", "goal", "Run a small experiment",
                                    "participants", List.of(Map.of("actorId", result.path("context").path("task").path("createdBy").asText(), "roles", List.of("owner"))))));
                        } else {
                        var data = new LinkedHashMap<String, Object>();
                        data.put("kind", "ARTIFACT");
                        data.put("title", body.contains("APPROVAL_GRANTED") ? "Validated recommendation" : "Evidence assessment");
                        data.put("body", Map.of("text", "Findings derived from the selected evidence", "confidence", 0.85));
                        data.put("visibleToRoles", mapper.convertValue(result.path("permittedOutputRoles"), List.class));
                        long revision = result.path("context").path("task").path("revision").asLong();
                        if (body.contains("STALE_ONCE") && !body.contains("REVISION_CONFLICT")) revision--;
                        delta = tool("task_contribute", Map.of("requestId", UUID.randomUUID().toString(),
                            "expectedRevision", revision,
                            "kind", "PUT_CONTEXT", "data", data));
                        }
                        finish = "tool_calls";
                    } else if (result.path("error").path("code").asText().equals("REVISION_CONFLICT")) {
                        delta = tool("task_read_context", Map.of()); finish = "tool_calls";
                    } else if (result.has("revision")) {
                        if (body.contains("NEEDS_APPROVAL") && !body.contains("APPROVAL_GRANTED")) {
                            delta = tool("human_handoff", Map.of("operatorReason", "Confirm the evidence before recommending a supplier"));
                            delta.put("content", "Please confirm the evidence and supplier selection.");
                            finish = "tool_calls";
                        } else {
                            delta = Map.of("content", "Evidence assessed; the durable artifact is ready for independent review.");
                            finish = "stop";
                        }
                    } else {
                        throw new IllegalStateException("Unexpected tool result: " + result);
                    }
                }
                String event = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta, "finish_reason", finish)),
                    "usage", Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)));
                byte[] bytes = ("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception error) {
                byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }

    private Map<String, Object> tool(String name, Map<String, Object> arguments) {
        var delta = new LinkedHashMap<String, Object>();
        delta.put("tool_calls", List.of(Map.of("index", 0, "id", UUID.randomUUID().toString(), "type", "function",
            "function", Map.of("name", name, "arguments", mapper.writeValueAsString(arguments)))));
        return delta;
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    @Override public void close() { server.stop(0); }
}
