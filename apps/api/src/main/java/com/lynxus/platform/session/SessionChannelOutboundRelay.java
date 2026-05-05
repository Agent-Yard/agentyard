package com.lynxus.platform.session;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.channel.ChannelBindingSnapshotLookupService;
import com.lynxus.platform.channel.ChannelOutboundFramePublisher;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SessionChannelOutboundRelay {
    private static final Logger log = LoggerFactory.getLogger(SessionChannelOutboundRelay.class);

    private final ChannelBindingSnapshotLookupService bindingLookupService;
    private final ChannelOutboundFramePublisher framePublisher;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private AutoCloseable changeSubscription;

    public SessionChannelOutboundRelay(
        ChannelBindingSnapshotLookupService bindingLookupService,
        ChannelOutboundFramePublisher framePublisher,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec
    ) {
        this.bindingLookupService = bindingLookupService;
        this.framePublisher = framePublisher;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
    }

    @PostConstruct
    void subscribe() {
        changeSubscription = pubSubBus.subscribe(
            keyspace.sseChannelSessionChanged(),
            payload -> relaySessionChange(codec.read(payload, SessionRuntimeChangeNotice.class))
        );
    }

    @PreDestroy
    void close() throws Exception {
        if (changeSubscription != null) {
            changeSubscription.close();
        }
    }

    void relaySessionChange(SessionRuntimeChangeNotice notice) {
        if (notice == null || notice.sessionId() == null || notice.sessionId().isBlank()) {
            return;
        }
        relaySession(notice.sessionId());
    }

    public void relaySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Optional<ChannelOutboundBindingSnapshot> snapshot;
        try {
            snapshot = bindingLookupService.findActiveBySession(sessionId);
        } catch (RuntimeException error) {
            log.warn("failed to resolve channel binding snapshot for final relay notification: sessionId={}", sessionId, error);
            return;
        }
        if (snapshot.isEmpty()) {
            return;
        }
        ChannelOutboundBindingSnapshot binding = snapshot.orElseThrow();
        if (binding.channelProfileId() == null || binding.channelProfileId().isBlank()) {
            return;
        }
        framePublisher.notifyFinalAvailableForSession(binding.channelProfileId());
    }
}
