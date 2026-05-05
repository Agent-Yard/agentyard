package com.lynxus.platform.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.platform.channel.ChannelBindingSnapshotLookupService;
import com.lynxus.platform.channel.ChannelOutboundFramePublisher;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionChannelOutboundRelayTest {
    @Test
    void notifiesFramePublisherWhenBoundSessionChanges() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        SessionChannelOutboundRelay relay = relay(lookupService, framePublisher);
        when(lookupService.findActiveBySessionFailClosed("session-1")).thenReturn(Optional.of(snapshot()));

        relay.relaySession("session-1");

        verify(framePublisher).notifyFinalAvailableForSession("channel-profile-1");
    }

    @Test
    void snapshotMissFailsClosed() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        SessionChannelOutboundRelay relay = relay(lookupService, framePublisher);
        when(lookupService.findActiveBySessionFailClosed("session-1")).thenReturn(Optional.empty());

        relay.relaySession("session-1");

        verify(framePublisher, never()).notifyFinalAvailableForSession(any());
    }

    private static SessionChannelOutboundRelay relay(
        ChannelBindingSnapshotLookupService lookupService,
        ChannelOutboundFramePublisher framePublisher
    ) {
        return new SessionChannelOutboundRelay(
            lookupService,
            framePublisher,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper())
        );
    }

    private static ChannelOutboundBindingSnapshot snapshot() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
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
