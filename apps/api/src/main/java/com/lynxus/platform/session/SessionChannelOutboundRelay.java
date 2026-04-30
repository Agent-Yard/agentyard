package com.lynxus.platform.session;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.channel.ChannelGatewayClient;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SessionChannelOutboundRelay {
    private static final Logger log = LoggerFactory.getLogger(SessionChannelOutboundRelay.class);

    private final SessionRuntimeRepository repository;
    private final ChannelGatewayClient channelGatewayClient;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private AutoCloseable changeSubscription;

    public SessionChannelOutboundRelay(
        SessionRuntimeRepository repository,
        ChannelGatewayClient channelGatewayClient,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec
    ) {
        this.repository = repository;
        this.channelGatewayClient = channelGatewayClient;
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
        ChannelConversationBinding binding;
        try {
            binding = channelGatewayClient.getBindingBySession(sessionId);
        } catch (NoSuchElementException ignored) {
            return;
        } catch (RuntimeException error) {
            log.warn("failed to resolve channel binding for session outbound relay: sessionId={}", sessionId, error);
            return;
        }

        var session = repository.findSession(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (binding.assistantId() != null && !binding.assistantId().equals(session.assistantId())) {
            log.warn(
                "skip channel outbound relay because binding assistant differs from session: sessionId={}, bindingAssistantId={}, sessionAssistantId={}",
                session.id(),
                binding.assistantId(),
                session.assistantId()
            );
            return;
        }

        for (SessionMessage message : repository.listMessages(session.id())) {
            relayMessage(binding, session.assistantId(), message);
        }
    }

    private void relayMessage(ChannelConversationBinding binding, String assistantId, SessionMessage message) {
        if (!isOutboundRole(message.role()) || message.blocks().isEmpty()) {
            return;
        }
        List<Object> blocks = message.blocks();
        for (int index = 0; index < blocks.size(); index += 1) {
            Map<String, Object> block = objectBlock(blocks.get(index));
            if (block.isEmpty()) {
                continue;
            }
            String deliveryMessageId = blocks.size() == 1 ? message.messageId() : message.messageId() + ":b" + index;
            ChannelOutboundDelivery delivery;
            try {
                delivery = channelGatewayClient.deliverOutbound(new ChannelOutboundDeliveryRequest(
                    binding.channelProfileId(),
                    assistantId,
                    binding.externalConversationId(),
                    message.sessionId(),
                    deliveryMessageId,
                    block,
                    randomTraceContext()
                ));
            } catch (RuntimeException error) {
                log.warn(
                    "channel outbound relay request failed: sessionId={}, sessionMessageId={}, channelProfileId={}",
                    message.sessionId(),
                    deliveryMessageId,
                    binding.channelProfileId(),
                    error
                );
                continue;
            }
            if (delivery.status() == ChannelOutboundDeliveryStatus.FAILED) {
                log.warn(
                    "channel outbound relay failed: sessionId={}, sessionMessageId={}, channelProfileId={}, error={}",
                    message.sessionId(),
                    deliveryMessageId,
                    binding.channelProfileId(),
                    delivery.lastError()
                );
            } else {
                log.debug(
                    "relayed session message to channel: sessionId={}, sessionMessageId={}, channelProfileId={}, status={}",
                    message.sessionId(),
                    deliveryMessageId,
                    binding.channelProfileId(),
                    delivery.status()
                );
            }
        }
    }

    private static boolean isOutboundRole(SessionMessageRole role) {
        return role == SessionMessageRole.ASSISTANT || role == SessionMessageRole.HUMAN_OPERATOR;
    }

    private static Map<String, Object> objectBlock(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static NormalizedChannelTraceContext randomTraceContext() {
        return new NormalizedChannelTraceContext(
            "00-" + randomHex(32) + "-" + randomHex(16) + "-01",
            null
        );
    }

    private static String randomHex(int length) {
        String seed = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return seed.substring(0, length);
    }
}
