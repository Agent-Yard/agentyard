package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.agentyard.extension.sdk.protocol.AgentYardExtensionHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChannelOutboundExtensionController {
    private final ChannelOutboundExtensionSubscriptionService subscriptionService;
    private final ChannelOutboundExtensionStreamService streamService;
    private final ChannelOutboundExtensionAckService ackService;

    public ChannelOutboundExtensionController(
        ChannelOutboundExtensionSubscriptionService subscriptionService,
        ChannelOutboundExtensionStreamService streamService,
        ChannelOutboundExtensionAckService ackService
    ) {
        this.subscriptionService = subscriptionService;
        this.streamService = streamService;
        this.ackService = ackService;
    }

    @GetMapping(value = "/extension/channel/outbound-frame-subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChannelOutboundExtensionSubscriptionService.ChannelOutboundFrameSubscriptionList subscriptions(
        @RequestHeader(name = AgentYardExtensionHeaders.REGISTRATION_ID) String registrationId,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_TYPE) String descriptorType,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_ID) String descriptorId
    ) {
        return subscriptionService.list(headers(registrationId, descriptorType, descriptorId));
    }

    @GetMapping(value = "/extension/channel/outbound-frames/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @RequestParam("channelProfileId") String channelProfileId,
        @RequestHeader(name = AgentYardExtensionHeaders.REGISTRATION_ID) String registrationId,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_TYPE) String descriptorType,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_ID) String descriptorId,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
        @RequestHeader(name = "X-AgentYard-Last-Acked-Final-Sequence", required = false) Long diagnosticLastAckedFinalSequence
    ) {
        return streamService.open(
            channelProfileId,
            headers(registrationId, descriptorType, descriptorId),
            lastEventId,
            diagnosticLastAckedFinalSequence
        );
    }

    @PostMapping(
        value = "/extension/channel/outbound-frames/ack",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ChannelOutboundExtensionAckService.ChannelOutboundFrameAckResponse ack(
        @RequestHeader(name = AgentYardExtensionHeaders.REGISTRATION_ID) String registrationId,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_TYPE) String descriptorType,
        @RequestHeader(name = AgentYardExtensionHeaders.DESCRIPTOR_ID) String descriptorId,
        @RequestBody ChannelOutboundFrameAck ack
    ) {
        return ackService.ack(headers(registrationId, descriptorType, descriptorId), ack);
    }

    private static ChannelOutboundExtensionHeaders headers(String registrationId, String descriptorType, String descriptorId) {
        return new ChannelOutboundExtensionHeaders(registrationId, descriptorType, descriptorId);
    }
}
