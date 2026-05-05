package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
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
    void shouldPersistProfilesBindingsInboundEventsAndOutboundFinalCheckpoints() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        ChannelGatewayProfile profile = new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            new ChannelAssistantBinding("assistant-1", null),
            "integration-account-1",
            true,
            1,
            now,
            now
        );
        ChannelConversationBinding binding = new ChannelConversationBinding(
            "channel-binding-1",
            profile.id(),
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
            profile.id(),
            "feishu",
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
        ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            "feishu",
            ChannelOutboundConsumerKind.GATEWAY_NATIVE,
            "native:feishu",
            null
        );

        repository.createProfile(profile, "vault://opaque-ref");
        repository.saveBinding(binding);
        repository.saveInboundEvent(inboundEvent);
        repository.ensureOutboundFinalCheckpoint(consumer, now);
        repository.advanceOutboundFinalCheckpoint(consumer, 42, "frame-42", "session-1", "message-1", now);

        assertEquals(1, repository.listProfiles().size());
        ChannelGatewayProfile persisted = repository.findProfile(profile.id()).orElseThrow();
        assertEquals("飞书客服机器人", persisted.displayName());
        assertEquals("integration-account-1", persisted.accountId());
        assertTrue(persisted.hasExternalSecretRef());
        assertEquals(1, persisted.revision());
        assertEquals(1, repository.listBindings(profile.id()).size());
        assertEquals(1, repository.listInboundEvents(profile.id()).size());
        assertEquals(1, repository.listOutboundFinalCheckpoints(profile.id()).size());
        assertEquals(42, repository.findOutboundFinalCheckpoint(consumer).orElseThrow().lastAckedFinalSequence());
        assertNotNull(repository.findInboundEventByDedupKey("feishu:event:evt-1").orElse(null));
    }

    @Test
    void shouldAdvanceOutboundFinalCheckpointMonotonically() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            "channel-profile-1",
            "feishu",
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "registration-1",
            "registration-1"
        );
        repository.createProfile(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            false,
            1,
            now,
            now
        ), null);

        assertTrue(repository.advanceOutboundFinalCheckpoint(consumer, 100, "frame-100", "session-1", "message-100", now));
        assertEquals(100, repository.findOutboundFinalCheckpoint(consumer).orElseThrow().lastAckedFinalSequence());
        assertEquals(false, repository.advanceOutboundFinalCheckpoint(
            consumer,
            99,
            "frame-99",
            "session-1",
            "message-99",
            now.plusSeconds(1)
        ));
        assertEquals(100, repository.findOutboundFinalCheckpoint(consumer).orElseThrow().lastAckedFinalSequence());
        assertEquals(false, repository.advanceOutboundFinalCheckpoint(
            consumer,
            100,
            "frame-100-duplicate",
            "session-1",
            "message-100",
            now.plusSeconds(2)
        ));
        assertEquals("frame-100", repository.findOutboundFinalCheckpoint(consumer).orElseThrow().lastAckedFinalFrameId());
        assertTrue(repository.advanceOutboundFinalCheckpoint(consumer, 101, "frame-101", "session-1", "message-101", now.plusSeconds(3)));
        assertEquals(101, repository.findOutboundFinalCheckpoint(consumer).orElseThrow().lastAckedFinalSequence());
    }

    @Test
    void shouldEnforceInboundEventDedupKeyUniqueness() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            false,
            1,
            now,
            now
        ), null);
        repository.saveInboundEvent(new ChannelInboundEvent(
            "channel-inbound-event-1",
            "channel-profile-1",
            "feishu",
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
            "channel-profile-1",
            "feishu",
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

    @Test
    void shouldEnforceActiveSessionBindingUniqueness() {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "Feishu",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            null,
            null,
            false,
            1,
            now,
            now
        ), null);
        repository.saveBinding(new ChannelConversationBinding(
            "channel-binding-1",
            "channel-profile-1",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "session-1",
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            now,
            now
        ));

        assertThrows(DataAccessException.class, () -> repository.saveBinding(new ChannelConversationBinding(
            "channel-binding-2",
            "channel-profile-1",
            "chat-2",
            "user-2",
            "assistant-1",
            "customer-2",
            "session-1",
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            now,
            now
        )));
    }
}
