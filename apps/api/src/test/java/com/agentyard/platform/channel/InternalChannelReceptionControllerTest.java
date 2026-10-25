package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.channel.ChannelContracts.*;
import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.RuntimeContracts.MessageContent;
import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.platform.agent.AgentAuthorization;
import com.agentyard.platform.agent.AgentService;
import com.agentyard.platform.catalog.CatalogDtos.EntryDto;
import com.agentyard.platform.catalog.CatalogService;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class InternalChannelReceptionControllerTest {
    private static EmbeddedPostgresTestDatabase db;
    private InternalChannelReceptionController controller;
    private AgentService agents;
    private AgentStore store;
    @BeforeAll static void start() throws Exception { db=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void stop() throws Exception { db.close(); }
    @BeforeEach void reset() {
        db.reset();
        ChannelCollaborationFixture.reception(db.dsl(),"fixture","fixture-human","entry");
        var catalog=mock(CatalogService.class);
        when(catalog.getEntry("entry")).thenReturn(new EntryDto("entry","Reception","","PERSON","fixture-human",ShareScope.SHARED,"template-v1",true,List.of(),List.of()));
        when(catalog.resolveRoleTemplateVersion("template-v1")).thenReturn(new AgentProfile("Reception","Reception","","",null,null,false,null,50,List.of(),List.of(),List.of()));
        var mapper=new ObjectMapper();
        agents=new AgentService(db.dsl(),mapper,new AgentAuthorization(catalog),catalog,mock(CollaborationService.class));
        store=new AgentStore(db.dsl(),new JooqJsonbSupport(mapper));
        var gateway=mock(ChannelGatewayClient.class);
        var now=Instant.now();
        when(gateway.getProfile("profile")).thenReturn(new ChannelGatewayProfile("profile","feishu","Reception",ChannelProfileStatus.ACTIVE,true,
            Map.of(),new ChannelEntryBinding("entry"),"account",true,1,now,now));
        controller=new InternalChannelReceptionController(agents,gateway,new InternalRuntimeAuth("test-token"),new ChannelParticipantIdentityRepository(db.dsl()));
    }

    @Test void routesVerifiedHumansToPersistentReceptionAndDeliversCommittedResponse() {
        var request=request("group",List.of(incoming("message-alice","alice"),incoming("message-bob","bob")));
        var first=controller.receive("Bearer test-token",request).data();
        var replay=controller.receive("Bearer test-token",request).data();
        assertEquals(first,replay);
        var alice=first.acceptedMessageAllocations().get(0);
        var bob=first.acceptedMessageAllocations().get(1);
        assertNotEquals(alice.participantId(),bob.participantId());
        assertNotEquals(alice.sessionId(),bob.sessionId());
        var next=controller.receive("Bearer test-token",request("group",List.of(incoming("message-next","alice")))).data();
        assertEquals(alice.sessionId(),next.sessionId());
        var otherGroup=controller.receive("Bearer test-token",request("other-group",List.of(incoming("message-next","alice")))).data();
        assertNotEquals(alice.participantId(),otherGroup.acceptedMessageAllocations().getFirst().participantId());
        assertNotEquals(alice.sessionId(),otherGroup.sessionId());
        var snapshot=new ChannelOutboundBindingSnapshot("binding",alice.sessionId(),"profile","feishu","group","alice","entry",alice.participantId(),
            "ACTIVE",ChannelProfileStatus.ACTIVE,1,Instant.now(),Instant.now(),Instant.now());
        new JooqChannelBindingSnapshotRepository(db.dsl()).upsert(snapshot);
        store.transaction(s->{s.claimActivations(10);return null;});
        var activation=store.activations(alice.sessionId(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
        assertEquals(alice.messageId(),activation.inputMessageId());
        var outcome=new ActivationOutcome(true,new MessageContent(List.of(Map.of("type","TEXT","text","已完成调查")),Map.of()),Map.of("case","investigated"),null,List.of(),null,null, "COMPLETED", false);
        agents.complete(activation.id(),outcome);
        agents.complete(activation.id(),outcome);
        var outbox=new ChannelCollaborationOutbox(db.dsl(),new ObjectMapper());
        outbox.materialize();
        var frames=outbox.listFinalFrames("profile",0,100);
        assertEquals(1,frames.size());
        assertEquals(alice.sessionId(),frames.getFirst().sessionId());
        assertEquals(activation.id()+":reply",frames.getFirst().payload().get("draftMessageId"));
        assertEquals("investigated",store.session(alice.sessionId(),false).orElseThrow().workMemory().get("case"));

        // An authorized specialist can contact this human directly without pretending to be the receptionist.
        db.dsl().execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values ('specialist','AGENT','specialist-session','Researcher',now())");
        var now=Instant.now();
        store.saveSession(new Session("specialist-session","specialist","template-v1","Researcher",SessionStatus.ACTIVE,alice.participantId(),alice.participantId(),Map.of(),1,now,now));
        var question=agents.sendAs("specialist",new SendMessage("clarification",alice.participantId(),null,null,MessageKind.QUESTION,Map.of("text","请确认调查范围")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->agents.sendAs("specialist",new SendMessage("unauthorized",bob.participantId(),null,null,MessageKind.QUESTION,Map.of("text","Private question"))));
        outbox.materialize();
        var specialistFrames=outbox.listFinalFrames("profile",frames.getFirst().finalSequence(),100);
        assertEquals(1,specialistFrames.size());
        assertEquals(question.id(),specialistFrames.getFirst().payload().get("sessionMessageId"));
        assertEquals("specialist",specialistFrames.getFirst().payload().get("senderParticipantId"));
        assertEquals(alice.sessionId(),specialistFrames.getFirst().sessionId());
        assertFalse(specialistFrames.getFirst().payload().containsKey("draftMessageId"));
    }

    @Test void rejectsUnverifiedCallsAndMismatchedEntryBeforeCreatingIdentities() {
        var request=request("group",List.of(incoming("message","alice")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.receive(null,request));
        assertThrows(IllegalArgumentException.class,()->controller.receive("Bearer test-token",new ChannelReceptionRequest("profile","group","dedup","wrong-entry",request.messages(),Map.of())));
        assertEquals(0,db.dsl().fetchCount(org.jooq.impl.DSL.table("channel_participant_identity")));
    }

    private static ChannelReceptionRequest request(String conversation,List<ChannelReceptionMessage> messages) {
        return new ChannelReceptionRequest("profile",conversation,"delivery","entry",messages,Map.of());
    }
    private static ChannelReceptionMessage incoming(String id,String human) {
        return new ChannelReceptionMessage(id,human,human,new MessageContent(List.of(Map.of("type","TEXT","text","请协助调查")),Map.of()));
    }
}
