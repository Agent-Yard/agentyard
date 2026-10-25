package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.Set;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.*;

class JooqChannelBindingSnapshotRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;
    private JooqChannelBindingSnapshotRepository repository;
    @BeforeAll static void startDatabase() throws Exception { database=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void stopDatabase() throws Exception { database.close(); }
    @BeforeEach void reset() { database.reset(); repository=new JooqChannelBindingSnapshotRepository(database.dsl()); }

    @Test
    void matchesReceptionParticipantAndEntryInsteadOfAnAssistantOwner() {
        ChannelCollaborationFixture.reception(database.dsl(),"session-1","human-1","entry-1");
        ChannelCollaborationFixture.verifiedIdentity(database.dsl(),"human-1","profile-1","chat-1");
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","chat-1","human-1","entry-1"));
        assertTrue(repository.findActiveBySessionId("session-1").isPresent());
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","wrong-chat","human-1","entry-1"));
        assertTrue(repository.findActiveBySessionId("session-1").isEmpty());
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","chat-1","wrong-human","entry-1"));
        assertTrue(repository.findActiveBySessionId("session-1").isEmpty());
    }

    @Test
    void allowsSeparateParticipantsInOneGroupAndRejectsDuplicateParticipantRoute() {
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","group","human-1","entry-1"));
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-2","session-2","profile-1","group","human-2","entry-1"));
        assertEquals(2,repository.listActiveByProfileId("profile-1").size());
        assertThrows(DataAccessException.class,()->repository.upsert(ChannelCollaborationFixture.snapshot(
            "binding-3","session-3","profile-1","group","human-1","entry-1")));
    }

    @Test
    void reconcilesRemovedRoutesAndPreventsDuplicateActiveSessionRoutes() {
        ChannelCollaborationFixture.reception(database.dsl(),"session-1","human-1","entry-1");
        ChannelCollaborationFixture.reception(database.dsl(),"session-2","human-2","entry-1");
        ChannelCollaborationFixture.verifiedIdentity(database.dsl(),"human-1","profile-1","chat-1");
        ChannelCollaborationFixture.verifiedIdentity(database.dsl(),"human-2","profile-1","chat-2");
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","chat-1","human-1","entry-1"));
        repository.upsert(ChannelCollaborationFixture.snapshot("binding-2","session-2","profile-1","chat-2","human-2","entry-1"));
        assertThrows(DataAccessException.class,()->repository.upsert(ChannelCollaborationFixture.snapshot(
            "binding-3","session-1","profile-2","chat-3","human-3","entry-1")));
        repository.markActiveMissingFromFullRefreshInactive(Set.of("binding-1"),Instant.now());
        assertTrue(repository.findActiveBySessionId("session-1").isPresent());
        assertTrue(repository.findActiveBySessionId("session-2").isEmpty());
    }
}
