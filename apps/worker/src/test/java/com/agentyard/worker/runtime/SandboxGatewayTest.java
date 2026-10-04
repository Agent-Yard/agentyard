package com.agentyard.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SandboxGatewayTest {
    private HttpServer server;
    private SandboxGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        gateway = new SandboxGateway.HttpSandboxGateway(
            "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldReadPythonOutputFromExecutionEnvelope() {
        respond(200, """
            {"success":true,"message":"Code executed successfully","data":{
              "status":"ok","outputs":[
                {"output_type":"stream","name":"stdout","text":"hello\\n"},
                {"output_type":"stream","name":"stderr","text":"warning\\n"},
                {"output_type":"stream","name":"stdout","text":"__AGENTYARD_RESULT__{}\\n"}
              ]}}
            """);

        assertThat(gateway.executePython("print('hello')", null, 15)).isEqualTo(
            new SandboxGateway.SandboxExecutionResult("ok", "hello\n__AGENTYARD_RESULT__{}", "warning")
        );
    }

    @Test
    void shouldPreservePythonErrorDetailsWhenEnvelopeReportsFailure() {
        respond(200, """
            {"success":false,"message":"Code execution error","data":{
              "status":"error","outputs":[
                {"output_type":"error","ename":"ValueError","evalue":"invalid amount",
                 "traceback":["ValueError: invalid amount"]}
              ]}}
            """);

        var result = gateway.executePython("raise ValueError('invalid amount')", null, 15);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.stderr()).contains("invalid amount", "ValueError: invalid amount");
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void shouldReadNodeOutputFromExecutionEnvelope() {
        respond(200, """
            {"success":true,"message":"JavaScript code executed successfully","data":{
              "status":"ok","stdout":"__AGENTYARD_RESULT__{}\\n","stderr":"warning\\n","exit_code":0}}
            """);

        assertThat(gateway.executeNode("console.log('hello')", 15)).isEqualTo(
            new SandboxGateway.SandboxExecutionResult("ok", "__AGENTYARD_RESULT__{}", "warning")
        );
    }

    @Test
    void shouldPreserveNodeErrorDetailsWhenEnvelopeReportsFailure() {
        respond(200, """
            {"success":false,"message":"Code execution error","data":{
              "status":"error","stdout":"before failure\\n","stderr":"Error: invalid amount\\n","exit_code":1}}
            """);

        assertThat(gateway.executeNode("throw new Error('invalid amount')", 15)).isEqualTo(
            new SandboxGateway.SandboxExecutionResult("error", "before failure", "Error: invalid amount")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"success\":false,\"message\":\"kernel unavailable\",\"data\":null}",
        "{\"success\":false,\"message\":\"kernel unavailable\",\"data\":{\"status\":\"ok\"}}"
    })
    void shouldReportApiFailureWithoutAcceptingASuccessfulExecution(String response) {
        respond(200, response);

        assertThatThrownBy(() -> gateway.executePython("pass", null, 15))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("kernel unavailable");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "{}", "{\"status\":\"ok\",\"stdout\":\"legacy\"}",
        "{\"success\":true,\"data\":null}",
        "{\"success\":true,\"data\":{}}",
        "{\"success\":true,\"data\":{\"status\":\"\"}}",
        "{\"success\":\"true\",\"data\":{\"status\":\"ok\"}}"
    })
    void shouldRejectMalformedExecutionEnvelope(String response) {
        respond(200, response);

        assertThatThrownBy(() -> gateway.executeNode("", 15))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid sandbox response");
    }

    @Test
    void shouldReportMalformedJson() {
        respond(200, "not-json");

        assertThatThrownBy(() -> gateway.executeNode("", 15))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failed to parse sandbox response");
    }

    @Test
    void shouldReportHttpFailure() {
        respond(503, "sandbox unavailable");

        assertThatThrownBy(() -> gateway.executeNode("", 15))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("503")
            .hasMessageContaining("sandbox unavailable");
    }

    private void respond(int status, String payload) {
        server.createContext("/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
    }
}
