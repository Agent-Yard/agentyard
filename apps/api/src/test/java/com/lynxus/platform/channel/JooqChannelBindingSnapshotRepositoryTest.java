package com.lynxus.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.Set;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "ACTIVE", ChannelProfileStatus.ACTIVE));
        repository.upsert(snapshot("binding-1", "session-1", "profile-1", "chat-1", "DETACHED", ChannelProfileStatus.ACTIVE));
        repository.upsert(snapshot("binding-2", "session-2", "profile-2", "chat-2", "ACTIVE", ChannelProfileStatus.INACTIVE));

        assertTrue(repository.findActiveBySessionId("session-1").isEmpty());
        assertTrue(repository.findActiveBySessionId("session-2").isEmpty());
    }

    @Test
    void shouldMarkMissingActiveSnapshotsInactiveDuringFullReconcile() {
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
