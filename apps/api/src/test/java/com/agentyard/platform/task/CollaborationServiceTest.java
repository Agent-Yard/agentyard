package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.platform.auth.AuthModels.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class CollaborationServiceTest {
    static EmbeddedPostgresTestDatabase database;
    CollaborationService service; TaskCommandService commands;
    String user="alice"; Actor alice,bob; Task task;
    @BeforeAll static void open() throws Exception { database=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void close() throws Exception { database.close(); }
    @BeforeEach void setup() {
        database.reset();user="alice";
        service=new CollaborationService(database.dsl(),new ObjectMapper().rebuild().findAndAddModules().build(),()->new PlatformUser(user,user,user,"",AuthSource.EXTERNAL,null,null,UserStatus.ACTIVE,Instant.now(),Instant.now(),null,List.of(Role.BUSINESS_USER)));
        commands=new TaskCommandService(service);
        alice=service.currentActor();user="bob";bob=service.currentActor();user="alice";
        task=service.createTask(new CreateTask(null,"Research","Explore and validate",Map.of(),null));
    }
    CommandResult send(CommandKind kind,Map<String,Object> data) { return send(task.id(),kind,data); }
    CommandResult send(String taskId,CommandKind kind,Map<String,Object> data) {
        return commands.command(taskId,new Command(UUID.randomUUID().toString(),service.detail(taskId).task().revision(),kind,data));
    }
    void participant(String actor,String role) { send(CommandKind.SET_PARTICIPANT,Map.of("actorId",actor,"roles",List.of(role))); }
    String note(String title,List<String> audience) { return send(CommandKind.PUT_CONTEXT,Map.of("kind","FACT","title",title,"body",Map.of("text",title),"visibleToRoles",audience)).objectId(); }
    @Test void commandsAreAtomicRevisionedAndIdempotent() {
        var command=new Command("one",1,CommandKind.UPDATE,Map.of("goal","New goal"));
        var first=commands.command(task.id(),command);var replay=commands.command(task.id(),command);
        assertFalse(first.duplicate());assertTrue(replay.duplicate());assertEquals(first.revision(),replay.revision());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->commands.command(task.id(),new Command("two",1,CommandKind.UPDATE,Map.of("goal","stale")))).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->commands.command(task.id(),new Command("one",1,CommandKind.UPDATE,Map.of("goal","different"))));
        assertEquals("New goal",service.detail(task.id()).task().goal());assertEquals(2,service.events(task.id(),0,100).size());
    }
    @Test void concurrentContributionsCannotOverwriteOneAnother() throws Exception {
        long revision=service.detail(task.id()).task().revision();CountDownLatch gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var calls=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){String title="contribution "+i;calls.add(executor.submit(()->{gate.await();try{commands.command(task.id(),CommandAuthority.own(alice.id()),new Command(title,revision,CommandKind.PUT_CONTEXT,Map.of("kind","NOTE","title",title,"body",Map.of())));return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}}));}
            gate.countDown();int count=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))count++;
            assertEquals(1,count);assertEquals(1,service.context(task.id(),null,100).items().size());
        }
    }
    @Test void restrictedContextAndHistoryCannotDeclassifyThroughSummaries() {
        participant(bob.id(),"contributor");String secret=note("Sensitive evidence",List.of("owner"));note("Public evidence",List.of());
        user="bob";assertEquals(1,service.context(task.id(),null,100).items().size());
        assertThrows(ResponseStatusException.class,()->service.versions(task.id(),secret));
        assertFalse(service.events(task.id(),0,100).stream().anyMatch(e->secret.equals(e.data().get("objectId"))));
        user="alice";
        assertThrows(ResponseStatusException.class,()->send(CommandKind.PUT_CONTEXT,Map.of("kind","SUMMARY","title","Leak","body",Map.of(),"source",Map.of("entryIds",List.of(secret)))));
        send(CommandKind.PUT_CONTEXT,Map.of("entryId",secret,"entryRevision",1,"title","Revised evidence","body",Map.of("text","revision 2"),"visibleToRoles",List.of("owner")));
        assertEquals(2,service.versions(task.id(),secret).size());
        assertThrows(ResponseStatusException.class,()->send(CommandKind.PUT_CONTEXT,Map.of("entryId",secret,"entryRevision",2,"title","Declassify","body",Map.of(),"visibleToRoles",List.of())));
    }
    @Test void organizationAndHierarchyDoNotImplicitlyShareTaskContext() {
        var org=service.createOrganization(new CreateOrganization(null,"Team","",null));
        service.setMember(org.organization().id(),new SetMember(1,bob.id(),List.of("contributor")));
        var work=service.createTask(new CreateTask(org.organization().id(),"Private task","Goal",Map.of(),null));
        user="bob";assertEquals(1,service.organizations().size());assertThrows(ResponseStatusException.class,()->service.detail(work.id()));
        user="alice";assertThrows(ResponseStatusException.class,()->send(CommandKind.REMOVE_PARTICIPANT,Map.of("actorId",alice.id())));
        participant(bob.id(),"contributor");String child=send(CommandKind.CREATE_CHILD,Map.of("title","Private experiment","goal","Validate")).objectId();
        user="bob";assertThrows(ResponseStatusException.class,()->service.detail(child));
    }
    @Test void finalDecisionBelongsToInitiatorAndHasNoArtificialDependencyGate() {
        participant(bob.id(),"contributor");
        String child=send(CommandKind.CREATE_CHILD,Map.of("title","Open experiment","goal","Validate")).objectId();
        assertThrows(ResponseStatusException.class,()->send(child,CommandKind.LINK_TASK,Map.of("relatedTaskId",task.id(),"kind","DEPENDS_ON")));
        user="bob";send(CommandKind.SUBMIT,Map.of());long revision=service.detail(task.id()).task().contentRevision();
        assertThrows(ResponseStatusException.class,()->send(CommandKind.CONFIRM,Map.of("contentRevision",revision)));
        user="alice";send(CommandKind.CONFIRM,Map.of("contentRevision",revision,"comment","Accept partial delivery; keep experiment open"));
        assertEquals(TaskStatus.COMPLETED,service.detail(task.id()).task().status());
        assertEquals(TaskStatus.OPEN,service.detail(child).task().status());
        assertEquals(alice.id(),service.detail(task.id()).decisions().getLast().decidedBy());
        assertThrows(ResponseStatusException.class,()->note("Late note",List.of()));
        send(CommandKind.TRANSITION,Map.of("status","ACTIVE"));note("Explicitly reopened",List.of());
    }
    @Test void newEvidenceInvalidatesSubmissionAndStageConfirmationKeepsLongWorkOpen() {
        participant(bob.id(),"contributor");send(CommandKind.SUBMIT,Map.of());long old=service.detail(task.id()).task().contentRevision();
        note("New evidence",List.of());assertEquals(TaskStatus.ACTIVE,service.detail(task.id()).task().status());
        send(CommandKind.SUBMIT,Map.of());assertThrows(ResponseStatusException.class,()->send(CommandKind.CONFIRM,Map.of("contentRevision",old)));
        long current=service.detail(task.id()).task().contentRevision();
        send(CommandKind.REQUEST_CHANGES,Map.of("contentRevision",current));
        send(CommandKind.CONFIRM_STAGE,Map.of("contentRevision",current,"comment","Phase one accepted"));
        assertEquals(TaskStatus.ACTIVE,service.detail(task.id()).task().status());
        send(CommandKind.TRANSFER_CONFIRMATION,Map.of("participantId",bob.id()));send(CommandKind.SUBMIT,Map.of());
        assertThrows(ResponseStatusException.class,()->send(CommandKind.CONFIRM,Map.of("contentRevision",current)));
        user="bob";send(CommandKind.CONFIRM,Map.of("contentRevision",current));
        assertEquals(bob.id(),service.detail(task.id()).task().confirmerId());
    }
    @Test void submissionReportsInvalidationAndStageLabelsCannotHidePendingConfirmation() {
        var submitted=send(CommandKind.SUBMIT,Map.of());
        assertEquals(TaskStatus.SUBMITTED,submitted.status());
        var labelled=send(CommandKind.UPDATE,Map.of("stage","Awaiting the initiator's confirmation"));
        assertEquals(TaskStatus.SUBMITTED,labelled.status());
        assertEquals(submitted.contentRevision(),labelled.contentRevision());
        assertFalse(labelled.submissionInvalidated());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->send(CommandKind.TRANSITION,
            Map.of("status","WAITING"))).getStatusCode().value());
        var review=new Command("review-note",service.detail(task.id()).task().revision(),CommandKind.PUT_CONTEXT,
            Map.of("kind","NOTE","title","Independent review","body",Map.of("text","Verified")));
        var changed=commands.command(task.id(),review);
        assertEquals(TaskStatus.ACTIVE,changed.status());
        assertTrue(changed.submissionInvalidated());
        assertEquals(submitted.contentRevision()+1,changed.contentRevision());
        var replay=commands.command(task.id(),review);
        assertTrue(replay.duplicate());assertTrue(replay.submissionInvalidated());
        assertEquals(changed.contentRevision(),replay.contentRevision());
        var resubmitted=send(CommandKind.SUBMIT,Map.of());
        assertEquals(TaskStatus.SUBMITTED,resubmitted.status());
        send(CommandKind.CONFIRM,Map.of("contentRevision",resubmitted.contentRevision()));
        assertEquals(TaskStatus.COMPLETED,service.detail(task.id()).task().status());
    }
    @Test void roleDefinitionsRemainGenericAndObserversCannotEscalate() {
        participant(bob.id(),"observer");user="bob";
        assertThrows(ResponseStatusException.class,()->note("not allowed",List.of()));
        assertThrows(ResponseStatusException.class,()->send(CommandKind.SET_PARTICIPANT,Map.of("actorId",bob.id(),"roles",List.of("owner"))));
        user="alice";
        var custom=new Policy(List.of(new RoleDefinition("principal","Sponsor",Set.of(Permission.VIEW,Permission.MANAGE)),new RoleDefinition("investigator","Researcher",Set.of(Permission.VIEW,Permission.CONTRIBUTE))),List.of());
        var work=service.createTask(new CreateTask(null,"Custom roles","Goal",Map.of(),custom));
        assertEquals(List.of("principal"),service.detail(work.id()).participants().getFirst().roles());
        assertThrows(ResponseStatusException.class,()->send(work.id(),CommandKind.PUT_CONTEXT,Map.of("kind","NOTE","title","note","body",Map.of())));
    }
    @Test void proxyProvenancePreservesBusinessInitiatorWhileAgentChildrenHaveAgentInitiators() {
        var agent=service.store.ensureActor(new Actor("agent",ActorKind.AGENT,"instance","Agent",true,Instant.now()));
        database.dsl().execute("insert into collaboration_message(id,sender_id,recipient_id,kind,body,created_at) values ('instruction',?,?,'MESSAGE','{}',now())",alice.id(),agent.id());
        var authority=new CommandAuthority(agent.id(),alice.id(),"instruction");
        var work=service.createTask(authority,new CreateTask(null,"A user request","Deliver it",Map.of(),null));
        assertEquals(alice.id(),work.initiatedBy());assertEquals(agent.id(),work.createdBy());assertEquals(alice.id(),work.confirmerId());
        var child=commands.command(work.id(),CommandAuthority.own(agent.id()),new Command("child",1,CommandKind.CREATE_CHILD,Map.of("title","Investigation","goal","Produce evidence"))).objectId();
        assertEquals(agent.id(),service.store.task(child,false).orElseThrow().confirmerId());
        assertThrows(ResponseStatusException.class,()->service.createTask(new CommandAuthority(agent.id(),bob.id(),"instruction"),new CreateTask(null,"Forged","Goal",Map.of(),null)));
    }
}
