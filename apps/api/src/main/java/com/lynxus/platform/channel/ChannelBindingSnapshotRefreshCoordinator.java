package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshotPage;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshotRefreshRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ChannelBindingSnapshotRefreshCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChannelBindingSnapshotRefreshCoordinator.class);
    private static final int PAGE_LIMIT = 500;
    private static final Duration REFRESH_LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration DEBOUNCE = Duration.ofMillis(1500);
    private static final Duration LOCAL_REFRESH_WAIT = Duration.ofSeconds(10);
    private static final String ALL_KEY = "all";

    private final JooqChannelBindingSnapshotRepository repository;
    private final ChannelGatewayClient channelGatewayClient;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties redisProperties;
    private final StringRedisTemplate redisTemplate;
    private final ExecutorService refreshExecutor;
    private final Duration debounce;
    private final Duration localRefreshWait;
    private final ConcurrentMap<String, CopyOnWriteArrayList<CompletableFuture<Void>>> localRefreshWaiters = new ConcurrentHashMap<>();
    private AutoCloseable subscription;

    @Autowired
    public ChannelBindingSnapshotRefreshCoordinator(
        JooqChannelBindingSnapshotRepository repository,
        ChannelGatewayClient channelGatewayClient,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties redisProperties,
        StringRedisTemplate redisTemplate
    ) {
        this(
            repository,
            channelGatewayClient,
            pubSubBus,
            keyspace,
            codec,
            redisProperties,
            redisTemplate,
            Executors.newCachedThreadPool(),
            DEBOUNCE,
            LOCAL_REFRESH_WAIT
        );
    }

    ChannelBindingSnapshotRefreshCoordinator(
        JooqChannelBindingSnapshotRepository repository,
        ChannelGatewayClient channelGatewayClient,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties redisProperties,
        StringRedisTemplate redisTemplate,
        ExecutorService refreshExecutor,
        Duration debounce,
        Duration localRefreshWait
    ) {
        this.repository = repository;
        this.channelGatewayClient = channelGatewayClient;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.redisProperties = redisProperties;
        this.redisTemplate = redisTemplate;
        this.refreshExecutor = refreshExecutor;
        this.debounce = debounce == null ? DEBOUNCE : debounce;
        this.localRefreshWait = localRefreshWait == null ? LOCAL_REFRESH_WAIT : localRefreshWait;
    }

    @PostConstruct
    void subscribe() {
        subscription = pubSubBus.subscribe(keyspace.channelBindingSnapshotRefreshChannel(), this::submitSharedRefresh);
    }

    @PreDestroy
    void close() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
        refreshExecutor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    void refreshOnStartup() {
        requestFullRefresh("STARTUP_FULL_REFRESH");
    }

    @Scheduled(fixedDelayString = "${lynxus.channel-outbound.binding-snapshot.reconcile-fixed-delay:PT5M}")
    void reconcilePeriodically() {
        requestFullRefresh("PERIODIC_RECONCILE");
    }

    public void requestRefresh(ChannelOutboundBindingSnapshotRefreshRequest request) {
        SharedRefreshRequest shared = SharedRefreshRequest.from(request, redisProperties.instanceId());
        pubSubBus.publish(keyspace.channelBindingSnapshotRefreshChannel(), codec.write(shared));
    }

    public void requestProfileRefresh(String channelProfileId, String reason) {
        requestRefresh(new ChannelOutboundBindingSnapshotRefreshRequest(
            channelProfileId,
            null,
            null,
            reason,
            null
        ));
    }

    public void requestSessionRefresh(String sessionId, String reason) {
        requestRefresh(new ChannelOutboundBindingSnapshotRefreshRequest(
            null,
            null,
            sessionId,
            reason,
            null
        ));
    }

    void requestFullRefresh(String reason) {
        pubSubBus.publish(keyspace.channelBindingSnapshotRefreshChannel(), codec.write(new SharedRefreshRequest(
            ALL_KEY,
            null,
            null,
            null,
            reason,
            null,
            Instant.now(),
            redisProperties.instanceId()
        )));
    }

    void submitSharedRefresh(String payload) {
        SharedRefreshRequest request = codec.read(payload, SharedRefreshRequest.class);
        refreshExecutor.execute(() -> consumeSharedRefresh(request));
    }

    void consumeSharedRefresh(SharedRefreshRequest request) {
        if (request == null || request.key() == null || request.key().isBlank()) {
            return;
        }
        Throwable failure = null;
        sleepDebounce();
        String leaseKey = keyspace.channelBindingSnapshotRefreshLock(request.key());
        String owner = redisProperties.instanceId() + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(leaseKey, owner, REFRESH_LEASE_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("skip channel binding snapshot refresh; lease held: key={}", request.key());
            completeLocalRefreshWaiter(request.key(), null);
            return;
        }
        try {
            refreshAuthoritativeRows(request);
            publishInvalidation(request.key());
        } catch (RuntimeException error) {
            failure = error;
            log.warn("channel binding snapshot refresh failed: key={}, reason={}", request.key(), request.reason(), error);
            throw error;
        } finally {
            redisTemplate.delete(leaseKey);
            completeLocalRefreshWaiter(request.key(), failure);
        }
    }

    public void refreshBySessionNow(String sessionId, String reason) {
        SharedRefreshRequest request = new SharedRefreshRequest(
            normalizeKey(null, sessionId, null),
            null,
            sessionId,
            null,
            reason,
            null,
            Instant.now(),
            redisProperties.instanceId()
        );
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        CopyOnWriteArrayList<CompletableFuture<Void>> waiters = localRefreshWaiters.computeIfAbsent(
            request.key(),
            ignored -> new CopyOnWriteArrayList<>()
        );
        waiters.add(waiter);
        try {
            pubSubBus.publish(keyspace.channelBindingSnapshotRefreshChannel(), codec.write(request));
            awaitLocalRefresh(request.key(), waiter);
        } finally {
            waiters.remove(waiter);
            if (waiters.isEmpty()) {
                localRefreshWaiters.remove(request.key(), waiters);
            }
        }
    }

    private void refreshAuthoritativeRows(SharedRefreshRequest request) {
        if (ALL_KEY.equals(request.key())) {
            refreshAll();
            return;
        }
        if (hasText(request.sessionId())) {
            refreshSession(request.sessionId());
            return;
        }
        if (hasText(request.channelProfileId())) {
            refreshProfile(request.channelProfileId());
            return;
        }
        refreshAll();
    }

    private void refreshAll() {
        Set<String> activeBindingIds = new LinkedHashSet<>();
        String cursor = null;
        do {
            ChannelOutboundBindingSnapshotPage page = channelGatewayClient.listBindingSnapshots(null, cursor, PAGE_LIMIT);
            List<ChannelOutboundBindingSnapshot> items = page == null ? List.of() : page.items();
            repository.upsertAll(items);
            items.stream()
                .filter(ChannelBindingSnapshotRefreshCoordinator::isActiveSnapshot)
                .map(ChannelOutboundBindingSnapshot::bindingId)
                .forEach(activeBindingIds::add);
            cursor = page == null ? null : page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        repository.markActiveMissingFromFullRefreshInactive(activeBindingIds, Instant.now());
    }

    private void refreshProfile(String channelProfileId) {
        List<ChannelOutboundBindingSnapshot> snapshots = channelGatewayClient.listBindingSnapshotsByProfile(channelProfileId);
        repository.upsertAll(snapshots);
        Set<String> activeBindingIds = new LinkedHashSet<>();
        snapshots.stream()
            .filter(ChannelBindingSnapshotRefreshCoordinator::isActiveSnapshot)
            .map(ChannelOutboundBindingSnapshot::bindingId)
            .forEach(activeBindingIds::add);
        repository.markProfileActiveMissingInactive(channelProfileId, activeBindingIds, Instant.now());
    }

    private void refreshSession(String sessionId) {
        try {
            repository.upsert(channelGatewayClient.getBindingSnapshotBySession(sessionId));
        } catch (NoSuchElementException ignored) {
            log.info("gateway returned no channel binding snapshot for session refresh: sessionId={}", sessionId);
        }
    }

    private void publishInvalidation(String key) {
        pubSubBus.publish(keyspace.channelBindingSnapshotInvalidationChannel(), codec.write(new SnapshotInvalidationNotice(
            key,
            Instant.now(),
            redisProperties.instanceId()
        )));
    }

    private void completeLocalRefreshWaiter(String key, Throwable error) {
        List<CompletableFuture<Void>> waiters = localRefreshWaiters.get(key);
        if (waiters == null || waiters.isEmpty()) {
            return;
        }
        for (CompletableFuture<Void> waiter : waiters) {
            if (error == null) {
                waiter.complete(null);
            } else {
                waiter.completeExceptionally(error);
            }
        }
    }

    private void awaitLocalRefresh(String key, CompletableFuture<Void> waiter) {
        try {
            waiter.get(localRefreshWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new IllegalStateException("timed out waiting for local channel binding snapshot refresh: key=" + key, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for local channel binding snapshot refresh: key=" + key, error);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("channel binding snapshot refresh failed: key=" + key, cause);
        }
    }

    private void sleepDebounce() {
        try {
            Thread.sleep(debounce.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while debouncing channel binding snapshot refresh", error);
        }
    }

    private static boolean isActiveSnapshot(ChannelOutboundBindingSnapshot snapshot) {
        return snapshot != null
            && "ACTIVE".equals(snapshot.bindingStatus())
            && snapshot.profileStatus() == ChannelProfileStatus.ACTIVE
            && hasText(snapshot.sessionId());
    }

    private static String normalizeKey(String bindingId, String sessionId, String channelProfileId) {
        if (hasText(bindingId)) {
            return "binding:" + bindingId.trim();
        }
        if (hasText(sessionId)) {
            return "session:" + sessionId.trim();
        }
        if (hasText(channelProfileId)) {
            return "profile:" + channelProfileId.trim();
        }
        return ALL_KEY;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record SharedRefreshRequest(
        String key,
        String bindingId,
        String sessionId,
        String channelProfileId,
        String reason,
        Instant bindingUpdatedAt,
        Instant requestedAt,
        String sourceInstanceId
    ) {
        static SharedRefreshRequest from(ChannelOutboundBindingSnapshotRefreshRequest request, String sourceInstanceId) {
            return new SharedRefreshRequest(
                normalizeKey(request.bindingId(), request.sessionId(), request.channelProfileId()),
                request.bindingId(),
                request.sessionId(),
                request.channelProfileId(),
                request.reason(),
                request.bindingUpdatedAt(),
                Instant.now(),
                sourceInstanceId
            );
        }
    }

    public record SnapshotInvalidationNotice(
        String key,
        Instant occurredAt,
        String sourceInstanceId
    ) {
    }
}
