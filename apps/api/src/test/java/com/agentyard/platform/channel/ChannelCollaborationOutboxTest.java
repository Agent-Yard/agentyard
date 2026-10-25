package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.util.List;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class ChannelCollaborationOutboxTest {
    private static EmbeddedPostgresTestDatabase db;
    @BeforeAll static void start() throws Exception { db=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void stop() throws Exception { db.close(); }
    @BeforeEach void reset() { db.reset(); }

    @Test
    void deliversParticipantMessagesDurablyWithoutMixingPrivateAgentMessages() {
        ChannelCollaborationFixture.reception(db.dsl(),"session-1","human-1","entry-1");
        ChannelCollaborationFixture.reception(db.dsl(),"session-2","human-2","entry-1");
        ChannelCollaborationFixture.verifiedIdentity(db.dsl(),"human-1","profile-1","group-chat");
        ChannelCollaborationFixture.verifiedIdentity(db.dsl(),"human-2","profile-1","group-chat");
        var bindings=new JooqChannelBindingSnapshotRepository(db.dsl());
        bindings.upsert(ChannelCollaborationFixture.snapshot("binding-1","session-1","profile-1","group-chat","human-1","entry-1"));
        bindings.upsert(ChannelCollaborationFixture.snapshot("binding-2","session-2","profile-1","group-chat","human-2","entry-1"));
        message("message-1","agent-session-1","human-1","First answer");
        message("message-2","agent-session-2","human-2","Second answer");
        message("private-message","agent-session-1","agent-session-2","Private internal work");
        db.dsl().execute("insert into agent_activation(id,session_id,input_message_id,reply_message_id,status,created_at,updated_at) values ('activation-1','session-1','private-message','message-1','SUCCEEDED',now(),now())");
        var outbox=new ChannelCollaborationOutbox(db.dsl(),new ObjectMapper());
        assertEquals(List.of("profile-1"),outbox.materialize());
        assertTrue(outbox.materialize().isEmpty());
        var frames=outbox.listFinalFrames("profile-1",0,100);
        assertEquals(2,frames.size());
        assertEquals(List.of("message-1","message-2"),frames.stream().map(f->f.payload().get("sessionMessageId")).toList());
        assertEquals(List.of("session-1","session-2"),frames.stream().map(f->f.sessionId()).toList());
        assertNull(frames.getFirst().sourceMessageId());
        assertEquals("activation-1:reply",frames.getFirst().payload().get("draftMessageId"));
        assertFalse(new ObjectMapper().writeValueAsString(frames).contains("Private internal work"));
        var restarted=new ChannelCollaborationOutbox(db.dsl(),new ObjectMapper());
        assertEquals(frames,restarted.listFinalFrames("profile-1",0,100));
        assertEquals(List.of(frames.getLast()),restarted.listFinalFrames("profile-1",frames.getFirst().finalSequence(),100));
        bindings.markInactive("binding-1",java.time.Instant.now());
        assertEquals(List.of(frames.getLast()),restarted.listFinalFrames("profile-1",0,100));
        bindings.upsert(ChannelCollaborationFixture.snapshot("binding-2","session-2","profile-1","wrong-group","human-2","entry-1"));
        assertTrue(restarted.listFinalFrames("profile-1",0,100).isEmpty());
    }

    private void message(String id,String sender,String recipient,String text) {
        db.dsl().execute("insert into collaboration_message(id,sender_id,recipient_id,kind,body,created_at) values (?,?,?,'MESSAGE',jsonb_build_object('text',?),now())",id,sender,recipient,text);
    }
}
