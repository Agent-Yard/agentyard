package com.lynxus.worker.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public interface SandboxGateway {
    SandboxExecutionResult executePython(String code, String kernelName, int timeoutSeconds);

    SandboxExecutionResult executeNode(String code, int timeoutSeconds);

    record SandboxExecutionResult(
        String status,
        String stdout,
        String stderr
    ) {
    }

    @Component
    class HttpSandboxGateway implements SandboxGateway {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper;
        private final String sandboxBaseUrl;

        public HttpSandboxGateway(
            @Value("${lynxus.sandbox.base-url}") String sandboxBaseUrl,
            ObjectMapper objectMapper
        ) {
            this.sandboxBaseUrl = sandboxBaseUrl;
            this.objectMapper = objectMapper;
        }

        @Override
        public SandboxExecutionResult executePython(String code, String kernelName, int timeoutSeconds) {
            try {
                JsonNode response = post(
                    "/v1/jupyter/execute",
                    objectMapper.createObjectNode()
                        .put("code", code)
                        .put("kernel_name", kernelName == null || kernelName.isBlank() ? "python3.11" : kernelName)
                        .put("timeout", sanitizeTimeout(timeoutSeconds))
                );
                String status = response.path("status").asText("error");
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                JsonNode outputs = response.path("outputs");
                if (outputs.isArray()) {
                    for (JsonNode item : outputs) {
                        String outputType = item.path("output_type").asText("");
                        if ("error".equals(outputType)) {
                            appendLine(stderr, item.path("evalue").asText(""));
                            JsonNode traceback = item.path("traceback");
                            if (traceback.isArray()) {
                                for (JsonNode line : traceback) {
                                    appendLine(stderr, line.asText(""));
                                }
                            }
                            continue;
                        }
                        appendLine(stdout, item.path("text").asText(""));
                    }
                }
                return new SandboxExecutionResult(status, stdout.toString().trim(), stderr.toString().trim());
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException("failed to execute python step in sandbox", error);
            }
        }

        @Override
        public SandboxExecutionResult executeNode(String code, int timeoutSeconds) {
            try {
                JsonNode response = post(
                    "/v1/nodejs/execute",
                    objectMapper.createObjectNode()
                        .put("code", code)
                        .put("timeout", sanitizeTimeout(timeoutSeconds))
                );
                return new SandboxExecutionResult(
                    response.path("status").asText("error"),
                    response.path("stdout").asText("").trim(),
                    response.path("stderr").asText("").trim()
                );
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException("failed to execute node step in sandbox", error);
            }
        }

        private JsonNode post(String path, JsonNode payload) throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sandboxBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("sandbox request failed: " + response.statusCode() + " " + response.body());
            }
            try {
                return objectMapper.readTree(response.body());
            } catch (JacksonException error) {
                throw new IllegalStateException("failed to parse sandbox response", error);
            }
        }

        private static int sanitizeTimeout(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                return 30;
            }
            return Math.min(timeoutSeconds, 300);
        }

        private static void appendLine(StringBuilder builder, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value.trim());
        }
    }
}
