package com.lynxus.platform.integration;

import com.lynxus.contracts.integration.IntegrationAccountInvalidationNotice;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

interface IntegrationAccountChangeNotifier {
    void accountChanged(StoredIntegrationAccount account, String reason);

    static IntegrationAccountChangeNotifier noop() {
        return (account, reason) -> {
        };
    }
}

@Service
final class RedisIntegrationAccountChangeNotifier implements IntegrationAccountChangeNotifier {
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;

    RedisIntegrationAccountChangeNotifier(
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties
    ) {
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public void accountChanged(StoredIntegrationAccount account, String reason) {
        if (account == null) {
            return;
        }
        IntegrationAccountInvalidationNotice notice = new IntegrationAccountInvalidationNotice(
            account.id(),
            account.subjectType().name(),
            account.subjectId(),
            reason,
            Instant.now(),
            properties.instanceId()
        );
        publishAfterCommit(notice);
    }

    private void publishAfterCommit(IntegrationAccountInvalidationNotice notice) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(notice);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(notice);
            }
        });
    }

    private void publish(IntegrationAccountInvalidationNotice notice) {
        pubSubBus.publish(keyspace.integrationAccountInvalidationChannel(), codec.write(notice));
    }
}
