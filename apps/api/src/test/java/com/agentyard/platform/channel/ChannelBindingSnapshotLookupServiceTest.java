package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import com.agentyard.shared.redis.RedisPubSubBus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelBindingSnapshotLookupServiceTest {
    @Test
    void hotPathMissDoesNotRefresh() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        when(repository.findActiveBySessionId("web-session-1")).thenReturn(Optional.empty());
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);

        assertTrue(lookupService.findActiveBySession("web-session-1").isEmpty());

        verify(refreshCoordinator, never()).refreshBySessionNow("web-session-1", "OUTBOUND_LOOKUP_MISS");
    }

    @Test
    void negativeCacheAvoidsRepeatedRepositoryLookup() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        when(repository.findActiveBySessionId("web-session-1")).thenReturn(Optional.empty());
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);

        lookupService.findActiveBySession("web-session-1");
        lookupService.findActiveBySession("web-session-1");

        verify(repository).findActiveBySessionId("web-session-1");
        verify(refreshCoordinator, never()).refreshBySessionNow("web-session-1", "OUTBOUND_LOOKUP_MISS");
    }

    @Test
    void positiveCacheAvoidsRepeatedRepositoryLookup() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        when(repository.findActiveBySessionId("session-1")).thenReturn(Optional.of(snapshot()));
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);

        assertTrue(lookupService.findActiveBySession("session-1").isPresent());
        assertTrue(lookupService.findActiveBySession("session-1").isPresent());

        verify(repository).findActiveBySessionId("session-1");
        verify(refreshCoordinator, never()).refreshBySessionNow("session-1", "OUTBOUND_LOOKUP_MISS");
    }

    @Test
    void invalidationEvictsNegativeCacheAndAllowsNewBindingLookup() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);
        when(repository.findActiveBySessionId("session-1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(snapshot()));

        assertTrue(lookupService.findActiveBySession("session-1").isEmpty());
        lookupService.invalidate(new ChannelBindingSnapshotRefreshCoordinator.SnapshotInvalidationNotice(
            "session:session-1",
            Instant.parse("2026-05-05T00:00:00Z"),
            "instance-b"
        ));

        assertTrue(lookupService.findActiveBySession("session-1").isPresent());
        verify(repository, times(2)).findActiveBySessionId("session-1");
    }

    @Test
    void bindingInvalidationClearsNegativeCache() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);
        when(repository.findActiveBySessionId("session-1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(snapshot()));

        assertTrue(lookupService.findActiveBySession("session-1").isEmpty());
        lookupService.invalidate(new ChannelBindingSnapshotRefreshCoordinator.SnapshotInvalidationNotice(
            "binding:binding-1",
            Instant.parse("2026-05-05T00:00:00Z"),
            "instance-b"
        ));

        assertTrue(lookupService.findActiveBySession("session-1").isPresent());
        verify(repository, times(2)).findActiveBySessionId("session-1");
    }

    @Test
    void failClosedStillRefreshesOnceAndReturnsEmptyWhenStillMissing() {
        JooqChannelBindingSnapshotRepository repository = mock(JooqChannelBindingSnapshotRepository.class);
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator = mock(ChannelBindingSnapshotRefreshCoordinator.class);
        when(repository.findActiveBySessionId("session-1")).thenReturn(Optional.empty());
        ChannelBindingSnapshotLookupService lookupService = lookupService(repository, refreshCoordinator);

        assertTrue(lookupService.findActiveBySessionFailClosed("session-1").isEmpty());

        verify(refreshCoordinator).refreshBySessionNow("session-1", "OUTBOUND_LOOKUP_MISS");
        verify(repository, times(2)).findActiveBySessionId("session-1");
    }

    private static ChannelBindingSnapshotLookupService lookupService(
        JooqChannelBindingSnapshotRepository repository,
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator
    ) {
        return new ChannelBindingSnapshotLookupService(
            repository,
            refreshCoordinator,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper())
        );
    }

    private static ChannelOutboundBindingSnapshot snapshot() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelOutboundBindingSnapshot(
            "binding-1",
            "session-1",
            "channel-profile-1",
            "provider.acme",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "ACTIVE",
            ChannelProfileStatus.ACTIVE,
            1L,
            now,
            now,
            now
        );
    }
}
