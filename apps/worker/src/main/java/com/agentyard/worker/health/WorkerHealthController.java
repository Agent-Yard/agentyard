package com.agentyard.worker.health;

import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.agentyard.worker.shared.redis.WorkerSharedStateProperties;

@RestController
public class WorkerHealthController {
    private final StringRedisTemplate redisTemplate;
    private final WorkflowServiceStubs workflowServiceStubs;
    private final WorkerSharedStateProperties sharedStateProperties;

    public WorkerHealthController(
        StringRedisTemplate redisTemplate,
        WorkflowServiceStubs workflowServiceStubs,
        WorkerSharedStateProperties sharedStateProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.workflowServiceStubs = workflowServiceStubs;
        this.sharedStateProperties = sharedStateProperties;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthz() {
        boolean redisReady = checkRedis();
        boolean temporalReady = checkTemporal();
        HttpStatus status = redisReady && temporalReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of(
            "status", redisReady && temporalReady ? "UP" : "DOWN",
            "service", "agentyard-worker",
            "instanceId", sharedStateProperties.instanceId(),
            "timestamp", Instant.now(),
            "dependencies", Map.of(
                "redis", redisReady ? "UP" : "DOWN",
                "temporal", temporalReady ? "UP" : "DOWN"
            )
        ));
    }

    private boolean checkRedis() {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            return false;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            return "PONG".equalsIgnoreCase(response);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean checkTemporal() {
        try {
            HealthCheckResponse response = workflowServiceStubs.healthCheck();
            return response != null && response.getStatus() == HealthCheckResponse.ServingStatus.SERVING;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
