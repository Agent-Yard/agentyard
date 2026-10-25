package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentyard.contracts.channel.ChannelContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.platform.agent.AgentService;
import com.agentyard.platform.agent.AgentAuthorization;
import com.agentyard.platform.catalog.CatalogService;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AgentChannelActivityRelayTest {
    private static EmbeddedPostgresTestDatabase db;
    private ChannelOutboundFramePublisher publisher;
    private AgentChannelActivityRelay relay;
    private AgentService service;
    @BeforeAll static void start() throws Exception { db=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void stop() throws Exception { db.close(); }
    @BeforeEach void reset() {
        db.reset();
        ChannelCollaborationFixture.reception(db.dsl(),"session","human","entry");
        ChannelCollaborationFixture.verifiedIdentity(db.dsl(),"human","profile","chat");
        var bindings=new JooqChannelBindingSnapshotRepository(db.dsl());
        bindings.upsert(ChannelCollaborationFixture.snapshot("binding","session","profile","chat","human","entry"));
        db.dsl().execute("insert into collaboration_message(id,sender_id,recipient_id,kind,body,created_at) values ('input','human','agent-session','MESSAGE','{}',now())");
        db.dsl().execute("insert into agent_activation(id,session_id,input_message_id,status,created_at,updated_at) values ('activation','session','input','RUNNING',now(),now())");
        publisher=mock(ChannelOutboundFramePublisher.class);
        var catalog=mock(CatalogService.class);
        service=new AgentService(db.dsl(),new ObjectMapper(),new AgentAuthorization(catalog),catalog,mock(CollaborationService.class));
        relay=new AgentChannelActivityRelay(service,bindings,publisher);
    }

    @Test void onlyRelaysPublicDraftAndLifecycleUsingPersistedIdentity() {
        assertTrue(relay.accept("activation",frame(1,"TURN_STARTED","OPERATOR",Map.of("private","hidden"))));
        assertFalse(relay.accept("activation",frame(2,"ACTION_TOOL_STARTED","OPERATOR",Map.of("credentials","hidden"))));
        assertFalse(relay.accept("activation",frame(3,"REPLY_BLOCK_DELTA","INTERNAL",Map.of("delta","hidden"))));
        assertTrue(relay.accept("activation",frame(4,"REPLY_BLOCK_DELTA","CUSTOMER",Map.of("replyMessageId","spoofed","blockId","text","delta","Hello"))));
        assertTrue(relay.accept("activation",frame(5,"FINAL_OUTCOME","INTERNAL",Map.of("outcome",Map.of("workMemory","hidden")))));
        var frames=ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(publisher,times(3)).publishTransient(frames.capture());
        assertEquals(List.of(ChannelOutboundFrameKind.TYPING_START,ChannelOutboundFrameKind.DRAFT_UPDATE,ChannelOutboundFrameKind.TYPING_STOP),frames.getAllValues().stream().map(ChannelOutboundFrame::kind).toList());
        assertEquals("activation:reply",frames.getAllValues().get(1).payload().get("messageId"));
        assertEquals("input",frames.getValue().sourceMessageId());
        assertFalse(new ObjectMapper().writeValueAsString(frames.getAllValues()).contains("hidden"));
    }

    @Test void rejectsForgedIdentityAndLateFramesAndPrivateSessions() {
        var f=frame(1,"TURN_STARTED","OPERATOR",Map.of());
        assertThrows(IllegalArgumentException.class,()->relay.accept("activation",new AgentChannelActivityRelay.RuntimeFrame(
            f.protocol(),f.frameId(),f.streamId(),"other-session",f.activationId(),f.participantId(),f.seq(),f.kind(),f.visibility(),f.occurredAt(),f.payload())));
        db.dsl().execute("update agent_activation set status='CANCELLED' where id='activation'");
        assertFalse(relay.accept("activation",f));
        db.dsl().execute("update agent_activation set status='RUNNING' where id='activation'");
        db.dsl().execute("delete from agent_reception_binding");
        assertFalse(relay.accept("activation",f));
        verifyNoInteractions(publisher);
    }

    @Test void resultDraftUsesTheFinalReplyRouteAndRequiresTheRecipientToSeeItsTask() {
        var now=Instant.now();
        service.store().tasks().saveTask(new Task("private-work",null,"Private work","Goal",TaskStatus.ACTIVE,"",1,1,Map.of(),
            new Policy(defaultRoles(),List.of()),"human","human","human",null,now,now));
        service.store().tasks().participant("private-work","agent-session",List.of("contributor"));
        db.dsl().execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values ('specialist','AGENT','specialist-session','Specialist',now())");
        db.dsl().execute("update agent_session set reply_to_participant_id='human' where id='session'");
        db.dsl().execute("update collaboration_message set sender_id='specialist',kind='RESULT',task_id='private-work' where id='input'");
        var draft=frame(1,"REPLY_BLOCK_DELTA","CUSTOMER",Map.of("blockId","text","delta","Task result"));
        assertFalse(relay.accept("activation",draft));verifyNoInteractions(publisher);
        service.store().tasks().participant("private-work","human",List.of("observer"));
        assertTrue(relay.accept("activation",draft));verify(publisher).publishTransient(any());
        db.dsl().execute("update collaboration_message set kind='QUESTION' where id='input'");
        assertFalse(relay.accept("activation",draft));
    }

    @Test void authenticatesBeforeReadingActivation() {
        var controller=new InternalAgentChannelStreamController(new InternalRuntimeAuth("test-token"),relay);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.stream(null,"activation",frame(1,"TURN_STARTED","OPERATOR",Map.of())));
        verifyNoInteractions(publisher);
    }

    private static AgentChannelActivityRelay.RuntimeFrame frame(long seq,String kind,String visibility,Map<String,Object> payload) {
        return new AgentChannelActivityRelay.RuntimeFrame("agentyard.activation-stream.v1","stream:"+seq,"stream","session","activation","agent-session",seq,kind,visibility,Instant.now(),payload);
    }
}
