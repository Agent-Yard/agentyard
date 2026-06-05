package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import org.jooq.JSONB;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.agentyard.persistence.jooq.Tables.SESSION_RUNTIME_SESSION;

class JooqChannelBindingSnapshotRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqChannelBindingSnapshotRepository repository;

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
        repository = new JooqChannelBindingSnapshotRepository(database.dsl());
    }

    @Test
    void shouldEnforceActiveSessionUniqueness() {
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE));

        assertThrows(DataAccessException.class, () ->
            repository.upsert(snapshot("binding-2", "session-1", "profile-2", "chat-2", "ACTIVE", ChannelProfileStatus.ACTIVE)));
    }

    @Test
    void shouldEnforceActiveProfileConversationUniqueness() {
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE));

        assertThrows(DataAccessException.class, () ->
            repository.upsert(snapshot("binding-2", "session-2", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE)));
    }

    @Test
    void shouldExcludeTombstonesAndProfileInactiveRowsFromActiveLookup() {
        insertChannelSession("session-1", "profile-1", "chat-1", "customer-1", "assistant-1");
        insertChannelSession("session-2", "profile-2", "chat-2", "customer-1", "assistant-1");
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE));
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "DETACHED", ChannelProfileStatus.ACTIVE));
        repository.upsert(snapshot("binding-2", "session-2", "profile-2", "chat-2", "ACTIVE", ChannelProfileStatus.INACTIVE));

        assertTrue(repository.findActiveBySessionId("session-1").isEmpty());
        assertTrue(repository.findActiveBySessionId("session-2").isEmpty());
    }

    @Test
    void shouldMarkMissingActiveSnapshotsInactiveDuringFullReconcile() {
        insertChannelSession("session-1", "profile-1", "chat-1", "customer-1", "assistant-1");
        insertChannelSession("session-2", "profile-1", "chat-2", "customer-1", "assistant-1");
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE));
        repository.upsert(snapshot("binding-2", "session-2", "profile-1", "chat-2", "ACTIVE", ChannelProfileStatus.ACTIVE));

        int updated = repository.markActiveMissingFromFullRefreshInactive(
            Set.of("binding-1"),
            Instant.parse("2026-05-04T00:00:10Z")
        );

        assertEquals(1, updated);
        assertTrue(repository.findActiveBySessionId("session-1").isPresent());
        assertTrue(repository.findActiveBySessionId("session-2").isEmpty());
    }

    @Test
    void activeBySessionRequiresSnapshotToMatchSessionChannelIdentity() {
        insertChannelSession("session-1", "profile-1", "chat-authoritative", "customer-1", "assistant-1");
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-stale", "ACTIVE", ChannelProfileStatus.ACTIVE));

        assertTrue(repository.findActiveBySessionId("session-1").isEmpty());
    }

    private void insertChannelSession(
        String sessionId,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-04T00:00:00Z");
        database.dsl().insertInto(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.ID, sessionId)
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, "scenario-1")
            .set(SESSION_RUNTIME_SESSION.TITLE, "session " + sessionId)
            .set(SESSION_RUNTIME_SESSION.ENTRY_SCOPE, "CHANNEL")
            .set(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID, channelProfileId)
            .set(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID, externalConversationId)
            .set(SESSION_RUNTIME_SESSION.CUSTOMER_ID, customerId)
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_ID, assistantId)
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_NAME, "Assistant")
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_RELEASE_VERSION, "1.0.0")
            .set(SESSION_RUNTIME_SESSION.STATUS, "ACTIVE")
            .set(SESSION_RUNTIME_SESSION.PRIMARY_AGENT_ID, "agent-1")
            .set(SESSION_RUNTIME_SESSION.CURRENT_OWNER_AGENT_ID, "agent-1")
            .set(SESSION_RUNTIME_SESSION.AGENT_TURN_ACTIVE, false)
            .set(SESSION_RUNTIME_SESSION.SESSION_HUMAN_HANDOFF_ACTIVE, false)
            .set(SESSION_RUNTIME_SESSION.PENDING_OWNER_REEVALUATION, false)
            .set(SESSION_RUNTIME_SESSION.DRAINING, false)
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE, JSONB.valueOf("{}"))
            .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, 1L)
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, 0L)
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, now)
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, now)
            .execute();
    }

    private static ChannelOutboundBindingSnapshot snapshot(
        String bindingId,
        String sessionId,
        String channelProfileId,
        String externalConversationId,
        String bindingStatus,
        ChannelProfileStatus profileStatus
    ) {
        Instant now = Instant.parse("2026-05-04T00:00:00Z");
        return new ChannelOutboundBindingSnapshot(
            bindingId,
            sessionId,
            channelProfileId,
            "feishu",
            externalConversationId,
            "user-1",
            "assistant-1",
            "customer-1",
            bindingStatus,
            profileStatus,
            1,
            now,
            now,
            now
        );
    }
}
