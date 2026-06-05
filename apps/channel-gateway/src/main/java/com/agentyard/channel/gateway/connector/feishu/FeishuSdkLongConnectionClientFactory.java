package com.agentyard.channel.gateway.connector.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class FeishuSdkLongConnectionClientFactory implements FeishuLongConnectionClientFactory {
    private static final Logger log = LoggerFactory.getLogger(FeishuSdkLongConnectionClientFactory.class);

    private final FeishuInboundEventService inboundEventService;
    private final ObjectMapper objectMapper;

    FeishuSdkLongConnectionClientFactory(
        FeishuInboundEventService inboundEventService,
        ObjectMapper objectMapper
    ) {
        this.inboundEventService = inboundEventService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(
        Supplier<FeishuLongConnectionProfile> profileResolver,
        FeishuAppCredential credential
    ) {
        EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                @Override
                public void handle(P2MessageReceiveV1 event) {
                    FeishuLongConnectionProfile profile = profileResolver.get();
                    if (profile == null) {
                        log.warn(
                            "skip feishu long connection message because no eligible profile is selected: accountId={}",
                            credential.accountId()
                        );
                        return;
                    }
                    logReceivedMessage(credential, profile, event);
                    try {
                        FeishuSdkEventMapper.toTextMessage(
                            profile.channelProfileId(),
                            credential.appId(),
                            event,
                            objectMapper
                        ).ifPresent(inboundEventService::ingestTextMessage);
                    } catch (RuntimeException error) {
                        log.warn("failed to ingest feishu long connection message: channelProfileId={}", profile.channelProfileId(), error);
                    }
                }
            })
            .onP2MessageReactionCreatedV1(new ImService.P2MessageReactionCreatedV1Handler() {
                @Override
                public void handle(P2MessageReactionCreatedV1 event) {
                    log.info("get message reaction: {}", event.getEvent());
                }
            })
            .build();
        com.lark.oapi.ws.Client client = new com.lark.oapi.ws.Client.Builder(
            credential.appId(),
            credential.appSecret()
        ).eventHandler(eventHandler).build();
        client.start();
    }

    private static void logReceivedMessage(
        FeishuAppCredential credential,
        FeishuLongConnectionProfile profile,
        P2MessageReceiveV1 event
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }
        EventMessage message = event == null || event.getEvent() == null ? null : event.getEvent().getMessage();
        log.debug(
            "received feishu long connection message: accountId={}, channelProfileId={}, requestId={}, tenantKey={}, messageId={}, chatId={}, chatType={}, messageType={}",
            credential.accountId(),
            profile.channelProfileId(),
            event == null ? null : event.getRequestId(),
            event == null ? null : event.getTenantKey(),
            message == null ? null : message.getMessageId(),
            message == null ? null : message.getChatId(),
            message == null ? null : message.getChatType(),
            message == null ? null : message.getMessageType()
        );
    }
}
