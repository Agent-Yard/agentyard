package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChannelBindingSnapshotLookupService {
    private static final Logger log = LoggerFactory.getLogger(ChannelBindingSnapshotLookupService.class);

    private final JooqChannelBindingSnapshotRepository repository;
    private final ChannelBindingSnapshotRefreshCoordinator refreshCoordinator;

    public ChannelBindingSnapshotLookupService(
        JooqChannelBindingSnapshotRepository repository,
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator
    ) {
        this.repository = repository;
        this.refreshCoordinator = refreshCoordinator;
    }

    public Optional<ChannelOutboundBindingSnapshot> findActiveBySessionFailClosed(String sessionId) {
        Optional<ChannelOutboundBindingSnapshot> snapshot = repository.findActiveBySessionId(sessionId);
        if (snapshot.isPresent()) {
            return snapshot;
        }
        try {
            refreshCoordinator.refreshBySessionNow(sessionId, "OUTBOUND_LOOKUP_MISS");
        } catch (RuntimeException error) {
            log.warn("channel binding snapshot by-session refresh failed during outbound lookup: sessionId={}", sessionId, error);
            return Optional.empty();
        }
        snapshot = repository.findActiveBySessionId(sessionId);
        if (snapshot.isEmpty()) {
            log.warn("channel outbound lookup failed closed because no ACTIVE binding snapshot exists: sessionId={}", sessionId);
        }
        return snapshot;
    }
}
