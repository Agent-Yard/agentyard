package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.jooq.JSONB;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_INBOUND_MESSAGE_DEDUPE;
import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN;
import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN_MESSAGE;

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
            Map.of(),
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
            Map.of(),
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
            Map.of(),
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

    @Test
    void shouldRecordDuplicateInboundTurnMessagesWithoutAuditUniquenessFailure() {
        insertInboundTurn("channel-turn-1");
        insertInboundTurnMessage("channel-turn-1", 0, "msg-1", "RECEIVED", null);
        insertInboundTurnMessage("channel-turn-1", 1, "msg-1", "DUPLICATE", "channel-turn-1");

        assertEquals(2, database.dsl().fetchCount(
            CHANNEL_INBOUND_TURN_MESSAGE,
            CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID.eq("channel-turn-1")
        ));

        database.dsl().insertInto(CHANNEL_INBOUND_MESSAGE_DEDUPE)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID, "channel-profile-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID, "chat-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID, "msg-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_TURN_ID, "channel-turn-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_REQUEST_INDEX, 0)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CREATED_AT, OffsetDateTime.parse("2026-04-23T00:00:00Z"))
            .execute();

        assertThrows(DataAccessException.class, () -> database.dsl().insertInto(CHANNEL_INBOUND_MESSAGE_DEDUPE)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID, "channel-profile-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID, "chat-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID, "msg-1")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_TURN_ID, "channel-turn-2")
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_REQUEST_INDEX, 0)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CREATED_AT, OffsetDateTime.parse("2026-04-23T00:00:01Z"))
            .execute());
    }

    private void insertInboundTurn(String turnId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-23T00:00:00Z");
        database.dsl().insertInto(CHANNEL_INBOUND_TURN)
            .set(CHANNEL_INBOUND_TURN.TURN_ID, turnId)
            .set(CHANNEL_INBOUND_TURN.CHANNEL_PROFILE_ID, "channel-profile-1")
            .set(CHANNEL_INBOUND_TURN.PROVIDER_TYPE, "feishu")
            .set(CHANNEL_INBOUND_TURN.DEDUP_KEY, "dedup-" + turnId)
            .set(CHANNEL_INBOUND_TURN.EXTERNAL_CONVERSATION_ID, "chat-1")
            .set(CHANNEL_INBOUND_TURN.NORMALIZED_PAYLOAD, JSONB.valueOf("{}"))
            .set(CHANNEL_INBOUND_TURN.RAW_PAYLOAD, JSONB.valueOf("{}"))
            .set(CHANNEL_INBOUND_TURN.TRACE_CONTEXT, JSONB.valueOf("{}"))
            .set(CHANNEL_INBOUND_TURN.METADATA, JSONB.valueOf("{}"))
            .set(CHANNEL_INBOUND_TURN.STATUS, "RECEIVED")
            .set(CHANNEL_INBOUND_TURN.CREATED_AT, now)
            .set(CHANNEL_INBOUND_TURN.UPDATED_AT, now)
            .execute();
    }

    private void insertInboundTurnMessage(
        String turnId,
        int requestIndex,
        String externalMessageId,
        String status,
        String duplicateOfTurnId
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-23T00:00:00Z");
        database.dsl().insertInto(CHANNEL_INBOUND_TURN_MESSAGE)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID, turnId)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.CHANNEL_PROFILE_ID, "channel-profile-1")
            .set(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_CONVERSATION_ID, "chat-1")
            .set(CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX, requestIndex)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_MESSAGE_ID, externalMessageId)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.OCCURRED_AT, now)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.ROLE, "USER")
            .set(CHANNEL_INBOUND_TURN_MESSAGE.SENDER, JSONB.valueOf("{\"senderType\":\"CUSTOMER\"}"))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.ATTACHMENTS, JSONB.valueOf("[]"))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.METADATA, JSONB.valueOf("{}"))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.STATUS, status)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.DUPLICATE_OF_TURN_ID, duplicateOfTurnId)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.CREATED_AT, now)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.UPDATED_AT, now)
            .execute();
    }
}
