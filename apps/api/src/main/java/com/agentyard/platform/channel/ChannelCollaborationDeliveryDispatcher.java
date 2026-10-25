package com.agentyard.platform.channel;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChannelCollaborationDeliveryDispatcher {
    private final ChannelCollaborationOutbox outbox;
    private final ChannelOutboundFramePublisher publisher;
    public ChannelCollaborationDeliveryDispatcher(ChannelCollaborationOutbox outbox, ChannelOutboundFramePublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${agentyard.channels.delivery-dispatch-ms:500}")
    public void dispatch() {
        outbox.materialize().forEach(publisher::notifyFinalAvailableForSession);
    }
}
