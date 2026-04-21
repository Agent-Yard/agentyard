package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.platform.LynxusApiApplication;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeSessionDto;

@Testcontainers(disabledWithoutDocker = true)
class MultiInstanceApiIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--save", "", "--appendonly", "no", "--requirepass", "lynxus");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static AppInstance apiA;
    private static AppInstance apiB;

    @BeforeAll
    static void startApplications() {
        apiA = startInstance("api-test-a");
        apiB = startInstance("api-test-b");
    }

    @AfterAll
    static void stopApplications() {
        close(apiB);
        close(apiA);
    }

    @BeforeEach
    void resetState() {
        SharedGatewayState.reset();
        flushRedis();
        clearRuntimeTables();
    }

    @Test
    void shouldShareAuthenticatedSessionAcrossApiInstances() throws Exception {
        HttpClient client = authenticatedClient();

        HttpResponse<String> response = send(client, apiB.port(), "GET", "/api/auth/session", null, null);

        assertEquals(200, response.statusCode());
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertEquals("user-admin", body.path("data").path("userId").asText());
        assertEquals("PLATFORM_ADMIN", body.path("data").path("currentRole").asText());
    }

    @Test
    void shouldDeduplicateExplicitExternalCallbackAcrossInstancesAndReturnCachedResultAfterCompletion() throws Exception {
        String sessionId = "session-explicit-idempotency";
        String playbookRunId = "playbook-explicit-idempotency";
        seedWaitingSession(sessionId, playbookRunId);
        HttpClient client = authenticatedClient();

        CountDownLatch started = SharedGatewayState.prepareExternalCallbackBlock(sessionId, playbookRunId);
        CompletableFuture<HttpResponse<String>> first = sendAsync(
            client,
            apiA.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/external-callback",
            """
                {
                  "playbookRunId": "%s",
                  "payload": {
                    "status": "approved"
                  }
                }
                """.formatted(playbookRunId),
            Map.of("Idempotency-Key", "callback-key-1")
        );

        assertTrue(started.await(5, TimeUnit.SECONDS));

        HttpResponse<String> second = send(
            client,
            apiB.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/external-callback",
            """
                {
                  "playbookRunId": "%s",
                  "payload": {
                    "status": "approved"
                  }
                }
                """.formatted(playbookRunId),
            Map.of("Idempotency-Key", "callback-key-1")
        );
        assertEquals(409, second.statusCode());
        assertEquals("duplicate request is already in progress", OBJECT_MAPPER.readTree(second.body()).path("detail").asText());

        SharedGatewayState.releaseExternalCallback();

        HttpResponse<String> firstResponse = first.get(5, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode());

        HttpResponse<String> third = send(
            client,
            apiB.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/external-callback",
            """
                {
                  "playbookRunId": "%s",
                  "payload": {
                    "status": "approved"
                  }
                }
                """.formatted(playbookRunId),
            Map.of("Idempotency-Key", "callback-key-1")
        );

        assertEquals(200, third.statusCode());
        assertEquals(firstResponse.body(), third.body());
        assertEquals(1, SharedGatewayState.externalCallbackInvocations(sessionId, playbookRunId));
    }

    @Test
    void shouldDeriveStableFallbackExternalCallbackIdempotencyKeyAcrossInstances() throws Exception {
        String sessionId = "session-derived-idempotency";
        String playbookRunId = "playbook-derived-idempotency";
        seedWaitingSession(sessionId, playbookRunId);
        HttpClient client = authenticatedClient();

        HttpResponse<String> first = send(
            client,
            apiA.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/external-callback",
            """
                {
                  "playbookRunId": "%s",
                  "payload": {
                    "nested": {
                      "z": 2,
                      "a": 1
                    },
                    "items": [
                      {
                        "b": 2,
                        "a": 1
                      },
                      "done"
                    ]
                  }
                }
                """.formatted(playbookRunId),
            null
        );
        HttpResponse<String> second = send(
            client,
            apiB.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/external-callback",
            """
                {
                  "playbookRunId": "%s",
                  "payload": {
                    "items": [
                      {
                        "a": 1,
                        "b": 2
                      },
                      "done"
                    ],
                    "nested": {
                      "a": 1,
                      "z": 2
                    }
                  }
                }
                """.formatted(playbookRunId),
            null
        );

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals(1, SharedGatewayState.externalCallbackInvocations(sessionId, playbookRunId));
    }

    @Test
    void shouldSerializeConcurrentSendMessageCallsWithDistributedSessionLock() throws Exception {
        String sessionId = "session-lock-test";
        seedActiveSession(sessionId);
        SharedGatewayState.submitDelayMillis = 250L;
        HttpClient client = authenticatedClient();

        CompletableFuture<HttpResponse<String>> first = sendAsync(
            client,
            apiA.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/messages",
            """
                {
                  "customerId": "customer-1",
                  "message": "hello from instance a"
                }
                """,
            null
        );
        CompletableFuture<HttpResponse<String>> second = sendAsync(
            client,
            apiB.port(),
            "POST",
            "/api/session-runtime/sessions/" + sessionId + "/messages",
            """
                {
                  "customerId": "customer-1",
                  "message": "hello from instance b"
                }
                """,
            null
        );

        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(2, SharedGatewayState.submitMessageInvocations(sessionId));
        assertEquals(1, SharedGatewayState.maxConcurrentSubmitCalls());
    }

    @Test
    void shouldBroadcastAndReplaySessionRuntimeEventsAcrossInstances() throws Exception {
        String sessionId = "session-sse-test";
        seedActiveSession(sessionId);
        HttpClient client = authenticatedClient();

        try (SseConnection streamOnB = openSseConnection(client, apiB.port(), sessionId, null)) {
            SseEvent snapshot = streamOnB.readEvent();
            assertEquals("SESSION_SNAPSHOT", snapshot.type());

            publishSyntheticSessionUpdate(apiA, sessionId);

            SseEvent updated = streamOnB.readEvent();
            assertEquals("SESSION_UPDATED", updated.type());

            try (SseConnection replayOnA = openSseConnection(client, apiA.port(), sessionId, snapshot.id())) {
                SseEvent replayed = replayOnA.readEvent();
                assertEquals("SESSION_UPDATED", replayed.type());
                assertEquals(updated.id(), replayed.id());
            }
        }
    }

    private static AppInstance startInstance(String instanceId) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(LynxusApiApplication.class, TestOverrides.class)
            .properties(
                "server.port=0",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.docker.compose.enabled=false",
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "spring.data.redis.host=" + redis.getHost(),
                "spring.data.redis.port=" + redis.getMappedPort(6379),
                "spring.data.redis.password=lynxus",
                "lynxus.shared-state.instance-id=" + instanceId,
                "lynxus.internal-auth.token=test-internal-token",
                "lynxus.auth.dev-bootstrap-enabled=true",
                "lynxus.auth.login-success-path=/",
                "lynxus.knowledge-service.base-url=http://127.0.0.1:1",
                "lynxus.temporal.target=127.0.0.1:7233",
                "lynxus.temporal.namespace=test",
                "lynxus.temporal.task-queue=test-queue",
                "logging.level.root=ERROR"
            )
            .run();
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("local.server.port is not available");
        }
        return new AppInstance(context, port);
    }

    private static void close(AppInstance instance) {
        if (instance != null) {
            instance.context().close();
        }
    }

    private static void clearRuntimeTables() {
        JdbcTemplate jdbcTemplate = apiA.bean(JdbcTemplate.class);
        jdbcTemplate.update("delete from session_runtime_event");
        jdbcTemplate.update("delete from session_runtime_playbook_run");
        jdbcTemplate.update("delete from session_runtime_session");
    }

    private static void flushRedis() {
        try (var connection = apiA.bean(StringRedisTemplate.class).getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    private static HttpClient authenticatedClient() throws Exception {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> login = send(client, apiA.port(), "GET", "/api/auth/dev-bootstrap-login", null, null);
        assertEquals(302, login.statusCode());
        return client;
    }

    private static void seedWaitingSession(String sessionId, String playbookRunId) {
        SessionRuntimeRepository repository = apiA.bean(SessionRuntimeRepository.class);
        Instant now = Instant.now();
        repository.saveSession(new SessionRuntimeSessionDto(
            sessionId,
            "scenario-1",
            "Test Session",
            "customer-1",
            "assistant-1",
            "Assistant",
            "1.0.0",
            "ACTIVE",
            "agent-1",
            "agent-1",
            playbookRunId,
            false,
            false,
            false,
            false,
            Map.of(),
            null,
            now,
            now,
            null,
            0
        ));
        repository.savePlaybookRun(new PlaybookRun(
            playbookRunId,
            sessionId,
            "event-parent",
            "playbook-1",
            "agent-1",
            PlaybookRunStatus.WAITING,
            Map.of(),
            Map.of(),
            null,
            now,
            now,
            "external_interaction:payment"
        ));
    }

    private static void seedActiveSession(String sessionId) {
        SessionRuntimeRepository repository = apiA.bean(SessionRuntimeRepository.class);
        Instant now = Instant.now();
        repository.saveSession(new SessionRuntimeSessionDto(
            sessionId,
            "scenario-1",
            "Test Session",
            "customer-1",
            "assistant-1",
            "Assistant",
            "1.0.0",
            "ACTIVE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            null,
            now,
            now,
            null,
            0
        ));
    }

    private static void publishSyntheticSessionUpdate(AppInstance instance, String sessionId) {
        SessionRuntimeRepository repository = instance.bean(SessionRuntimeRepository.class);
        SessionRuntimeChangeNoticePublisher publisher = instance.bean(SessionRuntimeChangeNoticePublisher.class);
        SessionRuntimeSessionDto current = repository.findSession(sessionId).orElseThrow();
        long sequence = repository.nextEventSequence(sessionId);
        Instant now = Instant.now();
        repository.appendEvent(new SessionEvent(
            "session-event-" + UUID.randomUUID(),
            sessionId,
            sequence,
            SessionEventType.OWNER_REPLY,
            now,
            SessionActorType.AGENT,
            current.currentOwnerAgentId(),
            Map.of("text", "synthetic update"),
            null,
            current.currentOwnerAgentId()
        ));
        repository.saveSession(new SessionRuntimeSessionDto(
            current.id(),
            current.scenarioId(),
            current.title(),
            current.customerId(),
            current.assistantId(),
            current.assistantName(),
            current.assistantReleaseVersion(),
            current.status(),
            current.primaryAgentId(),
            current.currentOwnerAgentId(),
            current.activePlaybookRunId(),
            current.agentTurnActive(),
            current.sessionHumanHandoffActive(),
            current.pendingOwnerReevaluation(),
            current.draining(),
            current.sharedState(),
            current.idleDeadline(),
            current.createdAt(),
            now,
            current.endedAt(),
            sequence
        ));
        publisher.publishSessionChanged(sessionId);
    }

    private static HttpResponse<String> send(
        HttpClient client,
        int port,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) throws Exception {
        return client.send(request(port, method, path, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> sendAsync(
        HttpClient client,
        int port,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) {
        return client.sendAsync(request(port, method, path, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(int port, String method, String path, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return builder.build();
    }

    private static SseConnection openSseConnection(HttpClient client, int port, String sessionId, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/session-runtime/sessions/" + sessionId + "/stream"))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return new SseConnection(response.body());
    }

    record AppInstance(ConfigurableApplicationContext context, int port) {
        <T> T bean(Class<T> type) {
            return context.getBean(type);
        }
    }

    static final class SseConnection implements AutoCloseable {
        private final InputStream body;
        private final BufferedReader reader;

        SseConnection(InputStream body) {
            this.body = body;
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        SseEvent readEvent() throws Exception {
            CompletableFuture<SseEvent> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String id = null;
                    String type = null;
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (type != null) {
                                return new SseEvent(id, type, data.toString());
                            }
                            continue;
                        }
                        if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                            continue;
                        }
                        if (line.startsWith("event:")) {
                            type = line.substring(6).trim();
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                    throw new IllegalStateException("stream closed before next SSE event");
                } catch (IOException error) {
                    throw new IllegalStateException("failed to read SSE event", error);
                }
            });
            try {
                SseEvent event = future.get(5, TimeUnit.SECONDS);
                assertNotNull(event.id());
                assertNotNull(event.type());
                assertTrue(event.data().startsWith("{"));
                return event;
            } catch (TimeoutException error) {
                future.cancel(true);
                throw error;
            }
        }

        @Override
        public void close() throws Exception {
            reader.close();
            body.close();
        }
    }

    record SseEvent(String id, String type, String data) {
    }

    static final class SharedGatewayState {
        private static final ConcurrentMap<String, AtomicInteger> EXTERNAL_CALLBACK_INVOCATIONS = new ConcurrentHashMap<>();
        private static final ConcurrentMap<String, AtomicInteger> SUBMIT_MESSAGE_INVOCATIONS = new ConcurrentHashMap<>();
        private static final AtomicInteger ACTIVE_SUBMIT_CALLS = new AtomicInteger();
        private static final AtomicInteger MAX_CONCURRENT_SUBMIT_CALLS = new AtomicInteger();

        private static volatile CountDownLatch externalCallbackStarted;
        private static volatile CountDownLatch externalCallbackRelease;
        private static volatile String blockingExternalCallbackKey;
        private static volatile long submitDelayMillis;

        static void reset() {
            EXTERNAL_CALLBACK_INVOCATIONS.clear();
            SUBMIT_MESSAGE_INVOCATIONS.clear();
            ACTIVE_SUBMIT_CALLS.set(0);
            MAX_CONCURRENT_SUBMIT_CALLS.set(0);
            externalCallbackStarted = null;
            externalCallbackRelease = null;
            blockingExternalCallbackKey = null;
            submitDelayMillis = 0L;
        }

        static CountDownLatch prepareExternalCallbackBlock(String sessionId, String playbookRunId) {
            blockingExternalCallbackKey = externalCallbackKey(sessionId, playbookRunId);
            externalCallbackStarted = new CountDownLatch(1);
            externalCallbackRelease = new CountDownLatch(1);
            return externalCallbackStarted;
        }

        static void releaseExternalCallback() {
            CountDownLatch release = externalCallbackRelease;
            if (release != null) {
                release.countDown();
            }
        }

        static void recordExternalCallback(String sessionId, String playbookRunId) {
            EXTERNAL_CALLBACK_INVOCATIONS.computeIfAbsent(externalCallbackKey(sessionId, playbookRunId), ignored -> new AtomicInteger())
                .incrementAndGet();
        }

        static int externalCallbackInvocations(String sessionId, String playbookRunId) {
            return EXTERNAL_CALLBACK_INVOCATIONS.getOrDefault(externalCallbackKey(sessionId, playbookRunId), new AtomicInteger()).get();
        }

        static void awaitIfBlocked(String sessionId, String playbookRunId) {
            if (!externalCallbackKey(sessionId, playbookRunId).equals(blockingExternalCallbackKey)) {
                return;
            }
            CountDownLatch started = externalCallbackStarted;
            CountDownLatch release = externalCallbackRelease;
            if (started != null) {
                started.countDown();
            }
            if (release == null) {
                return;
            }
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release external callback");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while blocking external callback", error);
            }
        }

        static void recordSubmitMessage(String sessionId) {
            SUBMIT_MESSAGE_INVOCATIONS.computeIfAbsent(sessionId, ignored -> new AtomicInteger()).incrementAndGet();
        }

        static int submitMessageInvocations(String sessionId) {
            return SUBMIT_MESSAGE_INVOCATIONS.getOrDefault(sessionId, new AtomicInteger()).get();
        }

        static int enterSubmitCriticalSection() {
            int concurrent = ACTIVE_SUBMIT_CALLS.incrementAndGet();
            MAX_CONCURRENT_SUBMIT_CALLS.updateAndGet(current -> Math.max(current, concurrent));
            return concurrent;
        }

        static void leaveSubmitCriticalSection() {
            ACTIVE_SUBMIT_CALLS.decrementAndGet();
        }

        static int maxConcurrentSubmitCalls() {
            return MAX_CONCURRENT_SUBMIT_CALLS.get();
        }

        private static String externalCallbackKey(String sessionId, String playbookRunId) {
            return sessionId + ":" + playbookRunId;
        }
    }

    static final class StubSessionWorkflowGateway implements SessionWorkflowGateway {
        private final SessionRuntimeRepository repository;
        private final SessionRuntimeChangeNoticePublisher changeNoticePublisher;

        StubSessionWorkflowGateway(SessionRuntimeRepository repository, SessionRuntimeChangeNoticePublisher changeNoticePublisher) {
            this.repository = repository;
            this.changeNoticePublisher = changeNoticePublisher;
        }

        @Override
        public void start(SessionStartRequest request) {
            throw new UnsupportedOperationException("session start is not used in multi-instance tests");
        }

        @Override
        public SessionUserMessageUpdateResult submitUserMessage(String workflowId, UserMessage message) {
            SharedGatewayState.recordSubmitMessage(workflowId);
            SharedGatewayState.enterSubmitCriticalSection();
            try {
                maybeDelaySubmit();
                SessionRuntimeSessionDto current = repository.findSession(workflowId).orElseThrow();
                long sequence = repository.nextEventSequence(workflowId);
                Instant now = Instant.now();
                repository.appendEvent(new SessionEvent(
                    message.messageId(),
                    workflowId,
                    sequence,
                    SessionEventType.USER_MESSAGE,
                    now,
                    SessionActorType.USER,
                    message.customerId(),
                    message.payload(),
                    null,
                    current.currentOwnerAgentId()
                ));
                repository.saveSession(new SessionRuntimeSessionDto(
                    current.id(),
                    current.scenarioId(),
                    current.title(),
                    current.customerId(),
                    current.assistantId(),
                    current.assistantName(),
                    current.assistantReleaseVersion(),
                    "ACTIVE",
                    current.primaryAgentId(),
                    current.currentOwnerAgentId(),
                    current.activePlaybookRunId(),
                    false,
                    current.sessionHumanHandoffActive(),
                    current.pendingOwnerReevaluation(),
                    current.draining(),
                    current.sharedState(),
                    current.idleDeadline(),
                    current.createdAt(),
                    now,
                    current.endedAt(),
                    sequence
                ));
                changeNoticePublisher.publishSessionChanged(workflowId);
                return new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.ACCEPTED, workflowId, null);
            } finally {
                SharedGatewayState.leaveSubmitCriticalSection();
            }
        }

        @Override
        public SessionSnapshot currentSnapshot(String workflowId) {
            SessionRuntimeSessionDto current = repository.findSession(workflowId).orElseThrow();
            return new SessionSnapshot(
                current.id(),
                current.assistantId(),
                current.assistantReleaseVersion(),
                current.primaryAgentId(),
                current.currentOwnerAgentId(),
                0,
                current.sharedState(),
                current.activePlaybookRunId(),
                current.agentTurnActive(),
                current.sessionHumanHandoffActive(),
                current.pendingOwnerReevaluation(),
                current.draining(),
                current.idleDeadline()
            );
        }

        @Override
        public boolean isWorkflowOpen(String workflowId) {
            return repository.findSession(workflowId).isPresent();
        }

        @Override
        public void humanResume(String workflowId, HumanResumeSignal signal) {
            throw new UnsupportedOperationException("human resume is not used in multi-instance tests");
        }

        @Override
        public void externalCallback(String workflowId, ExternalCallbackSignal signal) {
            SharedGatewayState.recordExternalCallback(workflowId, signal.playbookRunId());
            SharedGatewayState.awaitIfBlocked(workflowId, signal.playbookRunId());
            SessionRuntimeSessionDto current = repository.findSession(workflowId).orElseThrow();
            PlaybookRun existingRun = repository.listPlaybookRuns(workflowId).stream()
                .filter(run -> run.runId().equals(signal.playbookRunId()))
                .findFirst()
                .orElseThrow();
            long sequence = repository.nextEventSequence(workflowId);
            Instant now = Instant.now();
            repository.appendEvent(new SessionEvent(
                "session-event-" + UUID.randomUUID(),
                workflowId,
                sequence,
                SessionEventType.EXTERNAL_CALLBACK_RECEIVED,
                now,
                SessionActorType.EXTERNAL_SYSTEM,
                "test-external-system",
                signal.payload(),
                signal.playbookRunId(),
                current.currentOwnerAgentId()
            ));
            repository.savePlaybookRun(new PlaybookRun(
                existingRun.runId(),
                existingRun.sessionId(),
                existingRun.parentSessionEventId(),
                existingRun.playbookId(),
                existingRun.ownerAgentId(),
                PlaybookRunStatus.SUCCEEDED,
                existingRun.input(),
                signal.payload(),
                null,
                existingRun.createdAt(),
                now,
                null
            ));
            repository.saveSession(new SessionRuntimeSessionDto(
                current.id(),
                current.scenarioId(),
                current.title(),
                current.customerId(),
                current.assistantId(),
                current.assistantName(),
                current.assistantReleaseVersion(),
                "ACTIVE",
                current.primaryAgentId(),
                current.currentOwnerAgentId(),
                null,
                false,
                current.sessionHumanHandoffActive(),
                false,
                current.draining(),
                current.sharedState(),
                current.idleDeadline(),
                current.createdAt(),
                now,
                current.endedAt(),
                sequence
            ));
            changeNoticePublisher.publishSessionChanged(workflowId);
        }

        @Override
        public void endHumanHandoff(String workflowId) {
            throw new UnsupportedOperationException("handoff end is not used in multi-instance tests");
        }

        @Override
        public void humanOperatorReply(String workflowId, HumanOperatorReplySignal signal) {
            throw new UnsupportedOperationException("human operator reply is not used in multi-instance tests");
        }

        private static void maybeDelaySubmit() {
            if (SharedGatewayState.submitDelayMillis <= 0L) {
                return;
            }
            try {
                Thread.sleep(SharedGatewayState.submitDelayMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while delaying submit message", error);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOverrides {
        @Bean
        @Primary
        SessionWorkflowGateway sessionWorkflowGateway(
            SessionRuntimeRepository repository,
            SessionRuntimeChangeNoticePublisher changeNoticePublisher
        ) {
            return new StubSessionWorkflowGateway(repository, changeNoticePublisher);
        }

        @Bean
        @Primary
        KnowledgeWorkflowGateway knowledgeWorkflowGateway() {
            return new KnowledgeWorkflowGateway() {
                @Override
                public void startImport(String knowledgeBaseId, String importJobId) {
                }

                @Override
                public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
                }
            };
        }
    }
}
