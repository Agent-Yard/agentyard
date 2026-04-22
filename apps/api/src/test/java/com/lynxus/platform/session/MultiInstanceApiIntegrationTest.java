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
import com.lynxus.platform.auth.AuthModels;
import com.lynxus.platform.auth.AuthProperties;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.stream.Stream;

import static com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeSessionDto;

@Testcontainers(disabledWithoutDocker = true)
class MultiInstanceApiIntegrationTest {
    private static final String INTERNAL_AUTH_TOKEN = "test-internal-token";
    private static final String REDIS_PASSWORD = "lynxus";
    private static final String SESSION_COOKIE_NAME = "LYNXUS_SESSION";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--save", "", "--appendonly", "no", "--requirepass", REDIS_PASSWORD);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static AppInstance apiA;
    private static AppInstance apiB;

    @BeforeAll
    static void startApplications() {
        try {
            Startables.deepStart(Stream.of(postgres, redis)).join();
            awaitInfrastructureReady();
            migratePostgresSchema();
            apiA = startInstance("api-test-a");
            apiB = startInstance("api-test-b");
        } catch (Throwable error) {
            throw attachInfrastructureDiagnostics("startApplications failure", error);
        }
    }

    @AfterAll
    static void stopApplications() {
        close(apiB);
        close(apiA);
        redis.stop();
        postgres.stop();
    }

    @BeforeEach
    void resetState() {
        SharedGatewayState.reset();
        flushRedis();
        clearRuntimeTables();
    }

    @Test
    void shouldShareAuthenticatedSessionAcrossApiInstances() throws Exception {
        AuthenticatedClient client = authenticatedClient();

        HttpResponse<String> response = send(client, apiB.port(), "GET", "/api/auth/session", null, null);

        assertEquals(200, response.statusCode());
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertEquals("user-admin", body.path("data").path("userId").asString());
        assertEquals("PLATFORM_ADMIN", body.path("data").path("currentRole").asString());
    }

    @Test
    void shouldDeduplicateExplicitExternalCallbackAcrossInstancesAndReturnCachedResultAfterCompletion() throws Exception {
        String sessionId = "session-explicit-idempotency";
        String playbookRunId = "playbook-explicit-idempotency";
        seedWaitingSession(sessionId, playbookRunId);
        AuthenticatedClient client = authenticatedClient();

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
        assertEquals("duplicate request is already in progress", OBJECT_MAPPER.readTree(second.body()).path("detail").asString());

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
        assertApiResponseDataEquals(firstResponse.body(), third.body());
        assertEquals(1, SharedGatewayState.externalCallbackInvocations(sessionId, playbookRunId));
    }

    @Test
    void shouldDeriveStableFallbackExternalCallbackIdempotencyKeyAcrossInstances() throws Exception {
        String sessionId = "session-derived-idempotency";
        String playbookRunId = "playbook-derived-idempotency";
        seedWaitingSession(sessionId, playbookRunId);
        AuthenticatedClient client = authenticatedClient();

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
        assertApiResponseDataEquals(first.body(), second.body());
        assertEquals(1, SharedGatewayState.externalCallbackInvocations(sessionId, playbookRunId));
    }

    @Test
    void shouldSerializeConcurrentSendMessageCallsWithDistributedSessionLock() throws Exception {
        String sessionId = "session-lock-test";
        seedActiveSession(sessionId);
        SharedGatewayState.submitDelayMillis = 250L;
        AuthenticatedClient client = authenticatedClient();

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
        AuthenticatedClient client = authenticatedClient();

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
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        RuntimeException lastError = null;
        Map<String, Object> instanceOverrides = applicationProperties(instanceId);
        while (System.nanoTime() < deadlineNanos) {
            try {
                ConfigurableApplicationContext context = new SpringApplicationBuilder(LynxusApiApplication.class, TestOverrides.class)
                    .initializers(applicationContext -> applicationContext.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("multiInstanceTestOverrides", instanceOverrides)))
                    .run();
                Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
                if (port == null) {
                    context.close();
                    throw new IllegalStateException("local.server.port is not available");
                }
                return new AppInstance(context, port);
            } catch (RuntimeException error) {
                if (!hasCause(error, ConnectException.class)) {
                    throw attachInfrastructureDiagnostics("startInstance non-retryable failure for " + instanceId, error);
                }
                lastError = error;
                sleepUnchecked(Duration.ofMillis(500));
            }
        }
        IllegalStateException failure = new IllegalStateException("application instance did not start after transient infrastructure connection retries");
        if (lastError != null) {
            failure.initCause(lastError);
        }
        throw attachInfrastructureDiagnostics("startInstance exhausted retries", failure);
    }

    private static Map<String, Object> applicationProperties(String instanceId) {
        return Map.ofEntries(
            Map.entry("server.port", 0),
            Map.entry("spring.main.allow-bean-definition-overriding", true),
            Map.entry("spring.docker.compose.enabled", false),
            Map.entry("spring.autoconfigure.exclude", "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"),
            Map.entry("spring.session.store-type", "redis"),
            Map.entry("spring.session.redis.namespace", "lynxus:session:http"),
            Map.entry("spring.data.redis.host", redis.getHost()),
            Map.entry("spring.data.redis.port", redis.getMappedPort(6379)),
            Map.entry("spring.data.redis.password", REDIS_PASSWORD),
            Map.entry("LYNXUS_REDIS_HOST", redis.getHost()),
            Map.entry("LYNXUS_REDIS_PORT", redis.getMappedPort(6379)),
            Map.entry("LYNXUS_REDIS_PASSWORD", REDIS_PASSWORD),
            Map.entry("LYNXUS_SESSION_REDIS_NAMESPACE", "lynxus:session:http"),
            Map.entry("lynxus.shared-state.instance-id", instanceId),
            Map.entry("lynxus.internal-auth.token", INTERNAL_AUTH_TOKEN),
            Map.entry("LYNXUS_INTERNAL_AUTH_TOKEN", INTERNAL_AUTH_TOKEN),
            Map.entry("lynxus.knowledge-service.base-url", "http://127.0.0.1:1"),
            Map.entry("lynxus.temporal.target", "127.0.0.1:7233"),
            Map.entry("lynxus.temporal.namespace", "test"),
            Map.entry("lynxus.temporal.task-queue", "test-queue"),
            Map.entry("logging.level.root", "ERROR")
        );
    }

    private static void awaitInfrastructureReady() {
        awaitPostgresReady();
        awaitRedisReady();
    }

    private static void migratePostgresSchema() {
        try {
            Flyway.configure()
                .cleanDisabled(true)
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        } catch (RuntimeException error) {
            throw attachInfrastructureDiagnostics("migratePostgresSchema failure", error);
        }
    }

    private static void awaitPostgresReady() {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        SQLException lastError = null;
        while (System.nanoTime() < deadlineNanos) {
            try (
                var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("select 1")
            ) {
                if (resultSet.next() && resultSet.getInt(1) == 1) {
                    return;
                }
            } catch (SQLException error) {
                lastError = error;
            }
            sleepUnchecked(Duration.ofMillis(200));
        }
        IllegalStateException failure = new IllegalStateException("postgres test container did not become JDBC-ready");
        if (lastError != null) {
            failure.initCause(lastError);
        }
        throw attachInfrastructureDiagnostics("awaitPostgresReady failure", failure);
    }

    private static void awaitRedisReady() {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        RuntimeException lastError = null;
        while (System.nanoTime() < deadlineNanos) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(redis.getHost(), redis.getMappedPort(6379)), 2_000);
                String ping = execInContainer(redis, "redis-cli", "-a", REDIS_PASSWORD, "ping");
                if (ping.contains("PONG")) {
                    return;
                }
                lastError = new IllegalStateException("redis container responded without PONG: " + ping);
            } catch (IOException | RuntimeException error) {
                lastError = error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
            }
            sleepUnchecked(Duration.ofMillis(200));
        }
        IllegalStateException failure = new IllegalStateException("redis test container did not become TCP-ready");
        if (lastError != null) {
            failure.initCause(lastError);
        }
        throw attachInfrastructureDiagnostics("awaitRedisReady failure", failure);
    }

    private static void sleepUnchecked(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test infrastructure", error);
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        Throwable current = error;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static IllegalStateException attachInfrastructureDiagnostics(String context, Throwable error) {
        String diagnostics = buildInfrastructureDiagnostics(context, error);
        System.err.println(diagnostics);
        IllegalStateException wrapped = new IllegalStateException(diagnostics, error);
        wrapped.setStackTrace(error.getStackTrace());
        return wrapped;
    }

    private static String buildInfrastructureDiagnostics(String context, Throwable error) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "==== MultiInstanceApiIntegrationTest diagnostics: " + context + " ====");
        if (error != null) {
            appendLine(builder, "Failure type: " + error.getClass().getName());
            appendLine(builder, "Failure message: " + error.getMessage());
            appendThrowableChain(builder, error);
        }
        appendPostgresDiagnostics(builder);
        appendRedisDiagnostics(builder);
        appendLine(builder, "==== End diagnostics ====");
        return builder.toString();
    }

    private static void appendThrowableChain(StringBuilder builder, Throwable error) {
        int depth = 0;
        Throwable current = error;
        while (current != null) {
            appendLine(builder, "cause[" + depth + "]=" + current.getClass().getName() + ": " + current.getMessage());
            depth += 1;
            current = current.getCause();
        }
    }

    private static void appendPostgresDiagnostics(StringBuilder builder) {
        appendLine(builder, "-- postgres container --");
        appendLine(builder, "running=" + postgres.isRunning());
        appendLine(builder, "containerId=" + postgres.getContainerId());
        appendLine(builder, "host=" + postgres.getHost());
        appendLine(builder, "mappedPort=" + safePostgresPort());
        appendLine(builder, "jdbcUrl=" + postgres.getJdbcUrl());
        appendLine(builder, "databaseName=" + postgres.getDatabaseName());
        appendLine(builder, "username=" + postgres.getUsername());
        appendLine(builder, "socketProbe=" + probeSocket(postgres.getHost(), safePostgresPort()));
        appendLine(builder, "jdbcProbe=" + probeJdbc());
        appendLine(builder, "pg_isready=" + execInContainer(postgres, "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", postgres.getUsername()));
        appendLine(builder, "psql_select_1=" + execInContainer(
            postgres,
            "psql",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-c",
            "select 1"
        ));
        appendLine(builder, "logs:");
        appendLine(builder, safeLogs(postgres));
    }

    private static void appendRedisDiagnostics(StringBuilder builder) {
        appendLine(builder, "-- redis container --");
        appendLine(builder, "running=" + redis.isRunning());
        appendLine(builder, "containerId=" + redis.getContainerId());
        appendLine(builder, "host=" + redis.getHost());
        appendLine(builder, "mappedPort=" + safeRedisPort());
        appendLine(builder, "socketProbe=" + probeSocket(redis.getHost(), safeRedisPort()));
        appendLine(builder, "ping=" + execInContainer(redis, "redis-cli", "-a", REDIS_PASSWORD, "ping"));
        appendLine(builder, "logs:");
        appendLine(builder, safeLogs(redis));
    }

    private static int safePostgresPort() {
        try {
            return postgres.getMappedPort(5432);
        } catch (RuntimeException error) {
            System.err.println("postgres mapped port unavailable: " + error.getMessage());
            return -1;
        }
    }

    private static int safeRedisPort() {
        try {
            return redis.getMappedPort(6379);
        } catch (RuntimeException error) {
            System.err.println("redis mapped port unavailable: " + error.getMessage());
            return -1;
        }
    }

    private static String probeSocket(String host, int port) {
        if (port <= 0) {
            return "skipped";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return "connected";
        } catch (IOException error) {
            return "failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String probeJdbc() {
        try (
            var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var statement = connection.createStatement();
            var resultSet = statement.executeQuery("select current_database(), current_user, version()")
        ) {
            if (!resultSet.next()) {
                return "connected but no rows";
            }
            return "connected database=" + resultSet.getString(1) + ", user=" + resultSet.getString(2)
                + ", version=" + resultSet.getString(3);
        } catch (SQLException error) {
            return "failed: " + error.getClass().getName() + ": " + error.getMessage();
        }
    }

    private static String execInContainer(GenericContainer<?> container, String... command) {
        try {
            org.testcontainers.containers.Container.ExecResult result = container.execInContainer(command);
            return "exitCode=" + result.getExitCode()
                + ", stdout=" + normalizeExecOutput(result.getStdout())
                + ", stderr=" + normalizeExecOutput(result.getStderr());
        } catch (Exception error) {
            return "failed: " + error.getClass().getName() + ": " + error.getMessage();
        }
    }

    private static String normalizeExecOutput(String output) {
        if (output == null || output.isBlank()) {
            return "<empty>";
        }
        return output.strip().replace('\n', ' ');
    }

    private static String safeLogs(GenericContainer<?> container) {
        try {
            return truncate(container.getLogs(), 8_000);
        } catch (RuntimeException error) {
            return "failed to read logs: " + error.getMessage();
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "<null>";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...<truncated>";
    }

    private static void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    private static void assertApiResponseDataEquals(String expectedBody, String actualBody) throws Exception {
        JsonNode expected = OBJECT_MAPPER.readTree(expectedBody);
        JsonNode actual = OBJECT_MAPPER.readTree(actualBody);
        assertEquals(expected.path("success").asBoolean(), actual.path("success").asBoolean());
        assertEquals(expected.path("data"), actual.path("data"));
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

    private static AuthenticatedClient authenticatedClient() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> login = send(client, apiA.port(), "GET", "/api/auth/dev-bootstrap-login", null, null);
        assertEquals(302, login.statusCode());
        String sessionCookies = login.headers()
            .allValues("Set-Cookie")
            .stream()
            .map(value -> value.split(";", 2)[0])
            .filter(value -> !value.isBlank())
            .reduce((left, right) -> left + "; " + right)
            .orElseThrow(() -> new IllegalStateException("login response did not include cookies"));
        if (!sessionCookies.contains(SESSION_COOKIE_NAME + "=")) {
            throw new IllegalStateException("login response did not include " + SESSION_COOKIE_NAME + " cookie: " + sessionCookies);
        }
        AuthenticatedClient authenticatedClient = new AuthenticatedClient(client, sessionCookies);
        awaitAuthenticatedSession(authenticatedClient, login);
        return authenticatedClient;
    }

    private static void awaitAuthenticatedSession(AuthenticatedClient client, HttpResponse<String> loginResponse) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        SessionProbe lastProbe = null;
        while (System.nanoTime() < deadlineNanos) {
            lastProbe = probeAuthenticatedSession(client);
            if (lastProbe.apiAStatus() == 200 && lastProbe.apiBStatus() == 200) {
                return;
            }
            sleepUnchecked(Duration.ofMillis(100));
        }
        throw new IllegalStateException(buildAuthenticationDiagnostics(loginResponse, client, lastProbe));
    }

    private static SessionProbe probeAuthenticatedSession(AuthenticatedClient client) throws Exception {
        HttpResponse<String> apiASession = send(client, apiA.port(), "GET", "/api/auth/session", null, null);
        HttpResponse<String> apiBSession = send(client, apiB.port(), "GET", "/api/auth/session", null, null);
        return new SessionProbe(
            apiASession.statusCode(),
            truncate(apiASession.body(), 1_000),
            apiBSession.statusCode(),
            truncate(apiBSession.body(), 1_000),
            redisSessionKeys(),
            describeSessionRepository(apiA),
            describeSessionRepository(apiB)
        );
    }

    private static String buildAuthenticationDiagnostics(
        HttpResponse<String> loginResponse,
        AuthenticatedClient client,
        SessionProbe sessionProbe
    ) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "failed to establish authenticated session across API instances");
        appendLine(builder, "loginStatus=" + loginResponse.statusCode());
        appendLine(builder, "loginLocation=" + loginResponse.headers().firstValue("Location").orElse("<missing>"));
        appendLine(builder, "loginSetCookie=" + loginResponse.headers().allValues("Set-Cookie"));
        appendLine(builder, "forwardedCookie=" + client.sessionCookie());
        if (sessionProbe == null) {
            appendLine(builder, "sessionProbe=<none>");
        } else {
            appendLine(builder, "apiAStatus=" + sessionProbe.apiAStatus());
            appendLine(builder, "apiABody=" + sessionProbe.apiABody());
            appendLine(builder, "apiBStatus=" + sessionProbe.apiBStatus());
            appendLine(builder, "apiBBody=" + sessionProbe.apiBBody());
            appendLine(builder, "redisSessionKeys=" + sessionProbe.redisSessionKeys());
            appendLine(builder, "apiASessionRepository=" + sessionProbe.apiASessionRepository());
            appendLine(builder, "apiBSessionRepository=" + sessionProbe.apiBSessionRepository());
        }
        return builder.toString();
    }

    private static String redisSessionKeys() {
        String namespace = "lynxus:session:http";
        return String.valueOf(apiA.bean(StringRedisTemplate.class).keys(namespace + "*"));
    }

    private static String describeSessionRepository(AppInstance instance) {
        String[] beanNames = instance.context().getBeanNamesForType(SessionRepository.class);
        if (beanNames.length == 0) {
            return "<missing>";
        }
        Object bean = instance.context().getBean(beanNames[0]);
        return bean.getClass().getName() + " bean=" + beanNames[0];
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
        AuthenticatedClient client,
        int port,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) throws Exception {
        return client.httpClient().send(request(client, port, method, path, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> sendAsync(
        AuthenticatedClient client,
        int port,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) {
        return client.httpClient().sendAsync(request(client, port, method, path, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> send(
        HttpClient client,
        int port,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) throws Exception {
        return client.send(request(null, port, method, path, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(AuthenticatedClient client, int port, String method, String path, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
        if (client != null) {
            builder.header("Cookie", client.sessionCookie());
        }
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

    private static SseConnection openSseConnection(AuthenticatedClient client, int port, String sessionId, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/session-runtime/sessions/" + sessionId + "/stream"))
            .header("Accept", "text/event-stream")
            .header("Cookie", client.sessionCookie())
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = client.httpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return new SseConnection(response.body());
    }

    record AuthenticatedClient(HttpClient httpClient, String sessionCookie) {
    }

    record SessionProbe(
        int apiAStatus,
        String apiABody,
        int apiBStatus,
        String apiBBody,
        String redisSessionKeys,
        String apiASessionRepository,
        String apiBSessionRepository
    ) {
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
    @EnableRedisHttpSession(redisNamespace = "lynxus:session:http")
    static class TestOverrides {
        @Bean
        @Primary
        AuthProperties authProperties() {
            return new AuthProperties(
                new AuthProperties.Bootstrap("admin"),
                AuthModels.Role.BUSINESS_USER,
                true,
                "/"
            );
        }

        @Bean
        @Primary
        DataSource dataSource() {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean(destroyMethod = "destroy")
        @Primary
        RedisConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
            configuration.setPassword(RedisPassword.of(REDIS_PASSWORD));
            return new LettuceConnectionFactory(configuration);
        }

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
