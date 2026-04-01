package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.contracts.runtime.LogContextHeaders;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.worker.logging.WorkerLogContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnowledgeServiceGatewayTest {
    @Test
    void shouldSendInternalBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> workflowId = new AtomicReference<>();
        AtomicReference<String> customerId = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/import-jobs/job-1/run", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            workflowId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.WORKFLOW_ID));
            customerId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.CUSTOMER_ID));
            userId.set(exchange.getRequestHeaders().getFirst(LogContextHeaders.USER_ID));
            writeJson(exchange, "{\"status\":\"SUCCEEDED\"}");
        });
        server.start();

        try {
            KnowledgeServiceGateway gateway = new KnowledgeServiceGateway.HttpKnowledgeServiceGateway(
                serverUrl(server),
                "internal-token"
            );

            try (WorkerLogContext.Scope ignored = WorkerLogContext.open(new WorkflowContracts.LogContext(
                "0123456789abcdef0123456789abcdef",
                "session-1",
                "wf-1",
                "customer-1",
                "user-1"
            ))) {
                String status = gateway.runImportJob("job-1");

                assertEquals("Bearer internal-token", authorization.get());
                assertEquals("wf-1", workflowId.get());
                assertEquals("customer-1", customerId.get());
                assertEquals("user-1", userId.get());
                assertEquals("SUCCEEDED", status);
            }
        } finally {
            server.stop(0);
        }
    }

    private static String serverUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
