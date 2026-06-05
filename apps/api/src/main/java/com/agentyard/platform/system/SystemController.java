package com.agentyard.platform.system;

import com.agentyard.platform.shared.ApiResponse;
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.agentyard.platform.shared.redis.RedisSharedStateProperties;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final DSLContext dsl;
    private final StringRedisTemplate redisTemplate;
    private final WorkflowServiceStubs workflowServiceStubs;
    private final RedisSharedStateProperties sharedStateProperties;

    public SystemController(
        DSLContext dsl,
        StringRedisTemplate redisTemplate,
        WorkflowServiceStubs workflowServiceStubs,
        RedisSharedStateProperties sharedStateProperties
    ) {
        this.dsl = dsl;
        this.redisTemplate = redisTemplate;
        this.workflowServiceStubs = workflowServiceStubs;
        this.sharedStateProperties = sharedStateProperties;
    }

    @GetMapping("/health")
    public ApiResponse<?> health() {
        return ApiResponse.ok(Map.of(
            "status", "UP",
            "service", "agentyard-api",
            "instanceId", sharedStateProperties.instanceId(),
            "timestamp", Instant.now()
        ));
    }

    @GetMapping("/health/live")
    public ApiResponse<?> live() {
        return health();
    }

    @GetMapping("/health/ready")
    public ResponseEntity<ApiResponse<?>> ready() {
        Map<String, Object> dependencies = new LinkedHashMap<>();
        boolean ready = true;
        ready &= dependencyStatus("postgres", dependencies, this::checkPostgres);
        ready &= dependencyStatus("redis", dependencies, this::checkRedis);
        ready &= dependencyStatus("temporal", dependencies, this::checkTemporal);
        HttpStatus status = ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.ok(Map.of(
            "status", ready ? "UP" : "DOWN",
            "service", "agentyard-api",
            "instanceId", sharedStateProperties.instanceId(),
            "timestamp", Instant.now(),
            "dependencies", dependencies
        )));
    }

    @GetMapping("/dependencies")
    public ApiResponse<?> dependencies() {
        return ApiResponse.ok(Map.of(
            "postgres", "configured",
            "redis", "configured",
            "temporal", "configured"
        ));
    }

    private boolean dependencyStatus(String name, Map<String, Object> dependencies, Runnable probe) {
        try {
            probe.run();
            dependencies.put(name, "UP");
            return true;
        } catch (RuntimeException error) {
            dependencies.put(name, Map.of("status", "DOWN", "detail", error.getMessage()));
            return false;
        }
    }

    private void checkPostgres() {
        Integer one = dsl.selectOne().fetchOne(0, Integer.class);
        if (one == null || one != 1) {
            throw new IllegalStateException("postgres readiness probe returned unexpected result");
        }
    }

    private void checkRedis() {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            throw new IllegalStateException("redis connection factory is missing");
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                throw new IllegalStateException("redis ping returned " + response);
            }
        }
    }

    private void checkTemporal() {
        HealthCheckResponse response = workflowServiceStubs.healthCheck();
        if (response == null || response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
            throw new IllegalStateException("temporal health status is not SERVING");
        }
    }
}
