package com.lynxus.platform.channel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

class ChannelBindingSnapshotRefreshCoordinatorTest {
    @Test
    void periodicReconcileHasInitialDelay() throws Exception {
        Scheduled scheduled = ChannelBindingSnapshotRefreshCoordinator.class
            .getDeclaredMethod("reconcilePeriodically")
            .getAnnotation(Scheduled.class);

        assertEquals(
            "${lynxus.channel-outbound.binding-snapshot.reconcile-initial-delay:PT30S}",
            scheduled.initialDelayString()
        );
        assertEquals(
            "${lynxus.channel-outbound.binding-snapshot.reconcile-fixed-delay:PT5M}",
            scheduled.fixedDelayString()
        );
    }

    @Test
    void localStartupFullRefreshIgnoresGatewayUnavailable() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = redisTemplateWithAcquiredLease();
        when(gatewayClient.listBindingSnapshots(null, null, 500))
            .thenThrow(new ResourceAccessException("Connection refused"));
        ChannelBindingSnapshotRefreshCoordinator coordinator = coordinator(repository, gatewayClient, redisTemplate, true);

        assertDoesNotThrow(() -> coordinator.consumeSharedRefresh(fullRefreshRequest("STARTUP_FULL_REFRESH")));

        verify(redisTemplate).delete(eq("lynxus:lock:channel-binding-snapshot-refresh:all"));
    }

    @Test
    void localPeriodicFullRefreshStillPropagatesGatewayUnavailable() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = redisTemplateWithAcquiredLease();
        when(gatewayClient.listBindingSnapshots(null, null, 500))
            .thenThrow(new ResourceAccessException("Connection refused"));
        ChannelBindingSnapshotRefreshCoordinator coordinator = coordinator(repository, gatewayClient, redisTemplate, true);

        assertThrows(
            ResourceAccessException.class,
            () -> coordinator.consumeSharedRefresh(fullRefreshRequest("PERIODIC_RECONCILE"))
        );

        verify(redisTemplate).delete(eq("lynxus:lock:channel-binding-snapshot-refresh:all"));
    }

    @Test
    void nonLocalStartupFullRefreshStillPropagatesGatewayUnavailable() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = redisTemplateWithAcquiredLease();
        when(gatewayClient.listBindingSnapshots(null, null, 500))
            .thenThrow(new ResourceAccessException("Connection refused"));
        ChannelBindingSnapshotRefreshCoordinator coordinator = coordinator(repository, gatewayClient, redisTemplate, false);

        assertThrows(
            ResourceAccessException.class,
            () -> coordinator.consumeSharedRefresh(fullRefreshRequest("STARTUP_FULL_REFRESH"))
        );

        verify(redisTemplate).delete(eq("lynxus:lock:channel-binding-snapshot-refresh:all"));
    }

    @Test
    void refreshBySessionPublishesSharedRequestAndWaitsForLocalConsumer() throws Exception {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        ChannelOutboundBindingSnapshot snapshot = snapshot();
        when(gatewayClient.getBindingSnapshotBySession("session-1")).thenReturn(snapshot);

        RedisKeyspace keyspace = new RedisKeyspace();
        RedisJsonCodec codec = new RedisJsonCodec(new ObjectMapper());
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<ChannelBindingSnapshotRefreshCoordinator> coordinatorRef = new AtomicReference<>();
        doAnswer(invocation -> {
            coordinatorRef.get().submitSharedRefresh(invocation.getArgument(1));
            return null;
        }).when(pubSubBus).publish(eq(keyspace.channelBindingSnapshotRefreshChannel()), any());
        ChannelBindingSnapshotRefreshCoordinator coordinator = new ChannelBindingSnapshotRefreshCoordinator(
            repository,
            gatewayClient,
            pubSubBus,
            keyspace,
            codec,
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            redisTemplate,
            executor,
            Duration.ZERO,
            Duration.ofSeconds(1),
            false
        );
        coordinatorRef.set(coordinator);

        try {
            coordinator.refreshBySessionNow("session-1", "OUTBOUND_LOOKUP_MISS");
        } finally {
            executor.shutdownNow();
        }

        verify(pubSubBus).publish(eq(keyspace.channelBindingSnapshotRefreshChannel()), any());
        verify(gatewayClient).getBindingSnapshotBySession("session-1");
        verify(repository).upsert(snapshot);
    }

    @Test
    void shouldSkipGatewayPullWhenSingleFlightLeaseIsHeld() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
        ChannelBindingSnapshotRefreshCoordinator coordinator = coordinator(repository, gatewayClient, redisTemplate);

        coordinator.consumeSharedRefresh(new ChannelBindingSnapshotRefreshCoordinator.SharedRefreshRequest(
            "session:session-1",
            null,
            "session-1",
            null,
            "TEST",
            null,
            Instant.now(),
            "test"
        ));

        verify(gatewayClient, never()).getBindingSnapshotBySession(any());
        verify(repository, never()).upsert(any());
    }

    @Test
    void shouldRefreshBySessionWhenLeaseIsAcquired() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        ChannelOutboundBindingSnapshot snapshot = snapshot();
        when(gatewayClient.getBindingSnapshotBySession("session-1")).thenReturn(snapshot);
        ChannelBindingSnapshotRefreshCoordinator coordinator = coordinator(repository, gatewayClient, redisTemplate);

        coordinator.consumeSharedRefresh(new ChannelBindingSnapshotRefreshCoordinator.SharedRefreshRequest(
            "session:session-1",
            null,
            "session-1",
            null,
            "TEST",
            null,
            Instant.now(),
            "test"
        ));

        verify(gatewayClient).getBindingSnapshotBySession("session-1");
        verify(repository).upsert(snapshot);
        verify(redisTemplate).delete(eq("lynxus:lock:channel-binding-snapshot-refresh:session:session-1"));
    }

    private static ChannelBindingSnapshotRefreshCoordinator coordinator(
        JooqChannelBindingSnapshotRepository repository,
        ChannelGatewayClient gatewayClient,
        StringRedisTemplate redisTemplate
    ) {
        return coordinator(repository, gatewayClient, redisTemplate, false);
    }

    private static ChannelBindingSnapshotRefreshCoordinator coordinator(
        JooqChannelBindingSnapshotRepository repository,
        ChannelGatewayClient gatewayClient,
        StringRedisTemplate redisTemplate,
        boolean localProfile
    ) {
        return new ChannelBindingSnapshotRefreshCoordinator(
            repository,
            gatewayClient,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            redisTemplate,
            Executors.newSingleThreadExecutor(),
            Duration.ZERO,
            Duration.ofMillis(100),
            localProfile
        );
    }

    private static StringRedisTemplate redisTemplateWithAcquiredLease() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        return redisTemplate;
    }

    private static ChannelBindingSnapshotRefreshCoordinator.SharedRefreshRequest fullRefreshRequest(String reason) {
        return new ChannelBindingSnapshotRefreshCoordinator.SharedRefreshRequest(
            "all",
            null,
            null,
            null,
            reason,
            null,
            Instant.now(),
            "test"
        );
    }

    private static ChannelOutboundBindingSnapshot snapshot() {
        Instant now = Instant.parse("2026-05-04T00:00:00Z");
        return new ChannelOutboundBindingSnapshot(
            "binding-1",
            "session-1",
            "profile-1",
            "feishu",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "ACTIVE",
            ChannelProfileStatus.ACTIVE,
            1,
            now,
            now,
            now
        );
    }
}
