package com.agentyard.platform.task;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic model only: every emitted business tool executes in the real runtime and API. */
final class TaskModelFixture implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<JsonNode> supplierRequests = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper;
    volatile Function<Turn, Step> script = turn -> Step.reply("Acknowledged.");

    record Step(String name, Map<String, Object> arguments, String text) {
        static Step tool(String name, Map<String, Object> arguments) { return new Step(name, arguments, null); }
        static Step reply(String text) { return tool("activation_finish", Map.of("status", "COMPLETED", "summary", "Contribution complete", "reply", text)); }
        static Step commentary(String text) { return new Step(null, Map.of(), text); }
    }

    final class Turn {
        final JsonNode request;
        final JsonNode trigger;
        final JsonNode context;
        final List<JsonNode> tools;
        final Map<String, JsonNode> toolResults = new LinkedHashMap<>();
        final Map<String, Integer> toolCounts = new HashMap<>();
        final String system;
        Turn(JsonNode request) {
            this.request = request;
            JsonNode incoming = null, work = null;
            String incomingJson = null, workJson = null;
            int inputIndex = -1;
            var messages = request.path("messages");
            system = messages.get(0).path("content").asText();
            for (int i = 0; i < messages.size(); i++) {
                String content = messages.get(i).path("content").asText();
                if (content.startsWith("Incoming collaboration message:\n")) {
                    incomingJson = content.substring(content.indexOf('\n') + 1);
                    inputIndex = i;
                }
                if (content.contains("Current authorized work context:\n")) {
                    String json = content.substring(content.indexOf("Current authorized work context:\n") + "Current authorized work context:\n".length());
                    if (json.endsWith("</system-reminder>")) json = json.substring(0, json.length() - "</system-reminder>".length());
                    workJson = json;
                }
            }
            if (incomingJson != null) incoming = mapper.readTree(incomingJson);
            if (workJson != null) work = mapper.readTree(workJson);
            if (incoming == null || work == null) throw new IllegalStateException("missing trusted activation context");
            trigger = incoming; context = work;
            Map<String, String> names = new HashMap<>();
            var currentTools = new ArrayList<JsonNode>();
            for (int i = inputIndex + 1; i < messages.size(); i++) {
                var message = messages.get(i);
                for (var call : message.path("tool_calls")) names.put(call.path("id").asText(), call.path("function").path("name").asText());
                if ("tool".equals(message.path("role").asText())) {
                    JsonNode result = mapper.readTree(message.path("content").asText());
                    currentTools.add(result);
                    String name = names.get(message.path("tool_call_id").asText());
                    if (name != null) toolResults.put(name, result);
                    if (name != null) toolCounts.merge(name, 1, Integer::sum);
                }
            }
            tools = List.copyOf(currentTools);
        }
        boolean used(String name) { return toolResults.containsKey(name); }
        int count(String name) { return toolCounts.getOrDefault(name, 0); }
        JsonNode result(String name) {
            JsonNode result = toolResults.get(name);
            if (result == null) return mapper.createObjectNode();
            return result.has("result") ? result.path("result") : result;
        }
        String text() {
            var body=trigger.path("body");
            if(body.has("text")) return body.path("text").asText();
            var text=new StringBuilder();
            for(var block:body.path("blocks")) text.append(block.path("text").asText());
            return text.toString();
        }
        String taskId() { return trigger.path("taskId").asText(""); }
        String sender() { return trigger.path("senderId").asText(); }
        String messageId() { return trigger.path("id").asText(); }
        String participantId() { return context.path("participantId").asText(); }
        boolean reception() { return system.contains("E2E_RECEPTION"); }
        JsonNode currentTask() { return context.path("authorizedContext").path("currentTask"); }
    }

    TaskModelFixture(ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                Step next = script.apply(new Turn(mapper.readTree(body)));
                Map<String, Object> delta = next.name() == null ? Map.of("content", next.text()) :
                    Map.of("tool_calls", List.of(Map.of("index", 0, "id", UUID.randomUUID().toString(), "type", "function",
                        "function", Map.of("name", next.name(), "arguments", mapper.writeValueAsString(next.arguments())))));
                String event = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta,
                    "finish_reason", next.name() == null ? "stop" : "tool_calls")),
                    "usage", Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)));
                byte[] bytes = ("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception error) {
                errors.add(error.toString());
                byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/suppliers/query", exchange -> {
            try {
                supplierRequests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] body = mapper.writeValueAsBytes(Map.of("supplier", "Northwind Components", "leadDays", 14,
                    "annualCapacity", 1200, "source", "supplier-api-fixture", "reportId", "report-supplier-1"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    String serviceUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.close(); }
}
