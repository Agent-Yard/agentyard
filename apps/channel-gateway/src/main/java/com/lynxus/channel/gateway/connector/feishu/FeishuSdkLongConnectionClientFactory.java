package com.lynxus.channel.gateway.connector.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
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
    public FeishuLongConnectionClient create(
        FeishuLongConnectionProfileResolver profileResolver,
        FeishuAppCredential credential
    ) {
        EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                @Override
                public void handle(P2MessageReceiveV1 event) {
                    FeishuLongConnectionProfile profile = profileResolver.resolve();
                    if (profile == null) {
                        log.warn(
                            "skip feishu long connection message because no eligible profile is selected: accountId={}",
                            credential.accountId()
                        );
                        return;
                    }
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
            .build();
        com.lark.oapi.ws.Client client = new com.lark.oapi.ws.Client.Builder(
            credential.appId(),
            credential.appSecret()
        ).eventHandler(eventHandler).build();
        return client::start;
    }
}
