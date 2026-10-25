package com.agentyard.worker.runtime;

import com.agentyard.contracts.http.HttpUrls;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

/** Authenticated, bounded transport shared by activation and long-running tool activities. */
@Component
public class WorkerHttpClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String authorization;

    @Autowired
    public WorkerHttpClient(ObjectMapper mapper, @Value("${agentyard.internal-auth.token}") String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), mapper, token);
    }

    WorkerHttpClient(HttpClient client, ObjectMapper mapper, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("internal authentication token is required");
        }
        this.client = client;
        this.mapper = mapper.rebuild().findAndAddModules().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        this.authorization = "Bearer " + token;
    }

    public <T> T get(String baseUrl, String path, Class<T> type) {
        return exchange(baseUrl, path, null, type, Duration.ofSeconds(30));
    }

    public <T> T post(String baseUrl, String path, Object body, Class<T> type) {
        return exchange(baseUrl, path, body, type, Duration.ofSeconds(30));
    }

    public <T> T post(String baseUrl, String path, Object body, Class<T> type, Duration timeout) {
        return exchange(baseUrl, path, body, type, timeout);
    }

    private <T> T exchange(String baseUrl, String path, Object body, Class<T> type, Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(HttpUrls.join(baseUrl, path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Upstream bodies may contain tool credentials or private task content.
                throw new WorkerHttpException(response.statusCode(), path);
            }
            if (type == Void.class || response.body().isBlank()) {
                return null;
            }
            return mapper.readValue(response.body(), type);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker request interrupted: " + path, error);
        } catch (IOException error) {
            throw new WorkerTransportException(path, error);
        }
    }

    public static final class WorkerTransportException extends IllegalStateException {
        WorkerTransportException(String path, IOException cause) {
            super("worker transport failed: " + path, cause);
        }
    }

    public static final class WorkerHttpException extends IllegalStateException {
        private final int statusCode;

        public WorkerHttpException(int statusCode, String path) {
            super("worker request failed with HTTP " + statusCode + ": " + path);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
