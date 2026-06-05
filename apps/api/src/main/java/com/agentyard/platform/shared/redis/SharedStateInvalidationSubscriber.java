package com.agentyard.platform.shared.redis;

import com.agentyard.platform.catalog.CatalogRepository;
import com.agentyard.platform.knowledge.KnowledgeRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SharedStateInvalidationSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharedStateInvalidationSubscriber.class);

    private final RedisInvalidationBus invalidationBus;
    private final CatalogRepository catalogRepository;
    private final KnowledgeRepository knowledgeRepository;
    private AutoCloseable catalogSubscription;
    private AutoCloseable knowledgeSubscription;

    public SharedStateInvalidationSubscriber(
        RedisInvalidationBus invalidationBus,
        CatalogRepository catalogRepository,
        KnowledgeRepository knowledgeRepository
    ) {
        this.invalidationBus = invalidationBus;
        this.catalogRepository = catalogRepository;
        this.knowledgeRepository = knowledgeRepository;
    }

    @PostConstruct
    void subscribe() {
        catalogSubscription = invalidationBus.subscribeCatalogInvalidated(this::handleCatalogInvalidated);
        knowledgeSubscription = invalidationBus.subscribeKnowledgeInvalidated(this::handleKnowledgeInvalidated);
    }

    @PreDestroy
    void close() throws Exception {
        if (catalogSubscription != null) {
            catalogSubscription.close();
        }
        if (knowledgeSubscription != null) {
            knowledgeSubscription.close();
        }
    }

    void handleCatalogInvalidated(RedisInvalidationBus.InvalidationNotice notice) {
        observe("catalog", notice, catalogRepository.revision());
    }

    void handleKnowledgeInvalidated(RedisInvalidationBus.InvalidationNotice notice) {
        observe("knowledge", notice, knowledgeRepository.revision());
    }

    private void observe(String domain, RedisInvalidationBus.InvalidationNotice notice, long localRevision) {
        if (notice == null) {
            return;
        }
        if (localRevision < notice.revision()) {
            LOGGER.warn(
                "shared-state invalidation observed ahead of local revision domain={} localRevision={} remoteRevision={} sourceInstanceId={}",
                domain,
                localRevision,
                notice.revision(),
                notice.sourceInstanceId()
            );
            return;
        }
        LOGGER.debug(
            "shared-state invalidation observed domain={} revision={} sourceInstanceId={}",
            domain,
            notice.revision(),
            notice.sourceInstanceId()
        );
    }
}
