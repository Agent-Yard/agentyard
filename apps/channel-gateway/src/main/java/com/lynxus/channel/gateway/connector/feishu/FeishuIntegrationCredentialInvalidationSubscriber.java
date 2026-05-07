package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.contracts.integration.IntegrationAccountInvalidationNotice;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
final class FeishuIntegrationCredentialInvalidationSubscriber {
    private static final String CHANNEL_PROVIDER_SUBJECT_TYPE = "CHANNEL_PROVIDER";
    private static final String FEISHU_SUBJECT_ID = "feishu";

    private final FeishuIntegrationCredentialProvider credentialProvider;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private AutoCloseable subscription;

    FeishuIntegrationCredentialInvalidationSubscriber(
        FeishuIntegrationCredentialProvider credentialProvider,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec
    ) {
        this.credentialProvider = credentialProvider;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
    }

    @PostConstruct
    void subscribe() {
        subscription = pubSubBus.subscribe(keyspace.integrationAccountInvalidationChannel(), this::handlePayload);
    }

    @PreDestroy
    void close() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }

    void handlePayload(String payload) {
        handleNotice(codec.read(payload, IntegrationAccountInvalidationNotice.class));
    }

    void handleNotice(IntegrationAccountInvalidationNotice notice) {
        if (notice == null || !isFeishuChannelProviderAccount(notice)) {
            return;
        }
        credentialProvider.invalidate(notice.accountId());
    }

    private static boolean isFeishuChannelProviderAccount(IntegrationAccountInvalidationNotice notice) {
        return CHANNEL_PROVIDER_SUBJECT_TYPE.equals(notice.subjectType())
            && FEISHU_SUBJECT_ID.equals(notice.subjectId());
    }
}
