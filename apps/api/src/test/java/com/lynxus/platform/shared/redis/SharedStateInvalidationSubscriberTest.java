package com.lynxus.platform.shared.redis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.platform.catalog.CatalogRepository;
import com.lynxus.platform.knowledge.KnowledgeRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SharedStateInvalidationSubscriberTest {
    @Test
    void shouldSubscribeToCatalogAndKnowledgeInvalidationChannels() {
        RedisInvalidationBus invalidationBus = mock(RedisInvalidationBus.class);
        CatalogRepository catalogRepository = mock(CatalogRepository.class);
        KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
        AtomicReference<Consumer<RedisInvalidationBus.InvalidationNotice>> catalogConsumer = new AtomicReference<>();
        AtomicReference<Consumer<RedisInvalidationBus.InvalidationNotice>> knowledgeConsumer = new AtomicReference<>();
        doAnswer(invocation -> {
            catalogConsumer.set(invocation.getArgument(0));
            return (AutoCloseable) () -> {
            };
        }).when(invalidationBus).subscribeCatalogInvalidated(any());
        doAnswer(invocation -> {
            knowledgeConsumer.set(invocation.getArgument(0));
            return (AutoCloseable) () -> {
            };
        }).when(invalidationBus).subscribeKnowledgeInvalidated(any());
        when(catalogRepository.revision()).thenReturn(5L);
        when(knowledgeRepository.revision()).thenReturn(8L);
        SharedStateInvalidationSubscriber subscriber = new SharedStateInvalidationSubscriber(
            invalidationBus,
            catalogRepository,
            knowledgeRepository
        );

        subscriber.subscribe();
        catalogConsumer.get().accept(new RedisInvalidationBus.InvalidationNotice("catalog", 5L, Instant.now(), "api-b"));
        knowledgeConsumer.get().accept(new RedisInvalidationBus.InvalidationNotice("knowledge", 8L, Instant.now(), "api-c"));

        verify(catalogRepository, times(1)).revision();
        verify(knowledgeRepository, times(1)).revision();
    }

    @Test
    void shouldCloseSubscriptions() throws Exception {
        RedisInvalidationBus invalidationBus = mock(RedisInvalidationBus.class);
        CatalogRepository catalogRepository = mock(CatalogRepository.class);
        KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
        AutoCloseable catalogSubscription = mock(AutoCloseable.class);
        AutoCloseable knowledgeSubscription = mock(AutoCloseable.class);
        when(invalidationBus.subscribeCatalogInvalidated(any())).thenReturn(catalogSubscription);
        when(invalidationBus.subscribeKnowledgeInvalidated(any())).thenReturn(knowledgeSubscription);
        SharedStateInvalidationSubscriber subscriber = new SharedStateInvalidationSubscriber(
            invalidationBus,
            catalogRepository,
            knowledgeRepository
        );

        subscriber.subscribe();
        subscriber.close();

        verify(catalogSubscription).close();
        verify(knowledgeSubscription).close();
    }
}
