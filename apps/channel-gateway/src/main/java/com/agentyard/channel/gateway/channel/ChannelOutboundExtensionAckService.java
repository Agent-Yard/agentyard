package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.channel.ChannelOutboundExtensionAccessService.AuthorizedExtensionConsumer;
import com.agentyard.channel.gateway.shared.ConflictException;
import com.agentyard.channel.gateway.shared.UnprocessableEntityException;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ChannelOutboundExtensionAckService {
    private final ChannelAdminRepository repository;
    private final ChannelOutboundExtensionAccessService accessService;
    private final ChannelOutboundForwardedPendingStore pendingStore;
    private final ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor;

    public ChannelOutboundExtensionAckService(
        ChannelAdminRepository repository,
        ChannelOutboundExtensionAccessService accessService,
        ChannelOutboundForwardedPendingStore pendingStore,
        ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor
    ) {
        this.repository = repository;
        this.accessService = accessService;
        this.pendingStore = pendingStore;
        this.upstreamRelaySupervisor = upstreamRelaySupervisor;
    }

    public ChannelOutboundFrameAckResponse ack(ChannelOutboundExtensionHeaders headers, ChannelOutboundFrameAck ack) {
        if (ack == null) {
            throw new IllegalArgumentException("channel outbound frame ACK request is required");
        }
        AuthorizedExtensionConsumer authorized = accessService.requireConsumer(ack.channelProfileId(), headers);
        if (!authorized.profile().providerType().equals(ack.providerType())) {
            throw new IllegalArgumentException("ACK providerType must match extension descriptor id");
        }
        ChannelOutboundFrameCheckpoint checkpoint = repository.findOutboundFinalCheckpoint(authorized.consumer()).orElse(null);
        if (checkpoint != null
            && checkpoint.lastAckedFinalSequence() != null
            && ack.finalSequence() <= checkpoint.lastAckedFinalSequence()) {
            return new ChannelOutboundFrameAckResponse(true, true, checkpoint.lastAckedFinalSequence());
        }

        ChannelOutboundForwardedPendingStore.AckPendingResult pendingResult = pendingStore.ackForwarded(
            authorized.consumer(),
            ack
        );
        switch (pendingResult) {
            case ACKED -> {
                boolean advanced = repository.advanceOutboundFinalCheckpoint(
                    authorized.consumer(),
                    ack.finalSequence(),
                    ack.frameId(),
                    ack.sessionId(),
                    ack.sessionMessageId(),
                    Instant.now()
                );
                upstreamRelaySupervisor.reconcileSubscriptions();
                return new ChannelOutboundFrameAckResponse(true, !advanced, ack.finalSequence());
            }
            case MARKER_MISMATCH -> throw new UnprocessableEntityException("ACK does not match forwarded FINAL_DELIVERY marker");
            case NON_HEAD -> throw new ConflictException("ACK is not the head of the pending FINAL_DELIVERY queue");
            case MARKER_MISSING -> throw new ConflictException("ACK does not match an active forwarded FINAL_DELIVERY marker");
            default -> throw new IllegalStateException("unsupported ACK pending result: " + pendingResult);
        }
    }

    public record ChannelOutboundFrameAckResponse(
        boolean accepted,
        boolean duplicate,
        Long lastAckedFinalSequence
    ) {
    }
}
