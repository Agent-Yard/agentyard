package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAccount;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAccountStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderType;
import java.time.Instant;
import java.util.Map;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelAdminRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new ChannelAdminRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void shouldPersistAccountsBindingsInboundEventsAndOutboundDeliveries() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        ChannelAccount account = new ChannelAccount(
            "channel-account-1",
            ChannelProviderType.FEISHU,
            "飞书客服机器人",
            ChannelAccountStatus.ACTIVE,
            Map.of("appId", "cli_xxx"),
            now,
            now
        );
        ChannelConversationBinding binding = new ChannelConversationBinding(
            "channel-binding-1",
            account.id(),
            "chat-1",
            "ou_user_1",
            "assistant-1",
            "customer-1",
            "session-1",
            ChannelConversationBindingStatus.ACTIVE,
            Map.of("source", "feishu"),
            now,
            now
        );
        ChannelInboundEvent inboundEvent = new ChannelInboundEvent(
            "channel-inbound-event-1",
            account.id(),
            ChannelProviderType.FEISHU,
            "im.message.receive_v1",
            "evt-1",
            "chat-1",
            "msg-1",
            "feishu:event:evt-1",
            Map.of("raw", true),
            Map.of("eventType", "im.message.receive_v1"),
            ChannelInboundEventStatus.RECEIVED,
            now,
            now
        );
        ChannelOutboundDelivery outboundDelivery = new ChannelOutboundDelivery(
            "channel-outbound-delivery-1",
            account.id(),
            ChannelProviderType.FEISHU,
            "session-1",
            "message-1",
            "chat-1",
            Map.of("text", "hello"),
            ChannelOutboundDeliveryStatus.PENDING,
            1,
            null,
            now,
            now
        );

        repository.saveAccount(account);
        repository.saveBinding(binding);
        repository.saveInboundEvent(inboundEvent);
        repository.saveOutboundDelivery(outboundDelivery);

        assertEquals(1, repository.listAccounts().size());
        assertEquals("飞书客服机器人", repository.findAccount(account.id()).orElseThrow().name());
        assertEquals(1, repository.listBindings(account.id()).size());
        assertEquals(1, repository.listInboundEvents(account.id()).size());
        assertEquals(1, repository.listOutboundDeliveries(account.id()).size());
        assertNotNull(repository.findInboundEventByDedupKey("feishu:event:evt-1").orElse(null));
    }

    @Test
    void shouldEnforceInboundEventDedupKeyUniqueness() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        repository.saveAccount(new ChannelAccount(
            "channel-account-1",
            ChannelProviderType.FEISHU,
            "飞书客服机器人",
            ChannelAccountStatus.ACTIVE,
            Map.of("appId", "cli_xxx"),
            now,
            now
        ));
        repository.saveInboundEvent(new ChannelInboundEvent(
            "channel-inbound-event-1",
            "channel-account-1",
            ChannelProviderType.FEISHU,
            "im.message.receive_v1",
            "evt-1",
            "chat-1",
            "msg-1",
            "feishu:event:evt-1",
            Map.of(),
            Map.of(),
            ChannelInboundEventStatus.RECEIVED,
            now,
            now
        ));

        assertThrows(DataAccessException.class, () -> repository.saveInboundEvent(new ChannelInboundEvent(
            "channel-inbound-event-2",
            "channel-account-1",
            ChannelProviderType.FEISHU,
            "im.message.receive_v1",
            "evt-2",
            "chat-1",
            "msg-2",
            "feishu:event:evt-1",
            Map.of(),
            Map.of(),
            ChannelInboundEventStatus.RECEIVED,
            now,
            now
        )));
    }
}
