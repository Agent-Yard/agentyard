package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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
    CollaborationService service; TaskCommandService commands; TaskExecutionService executions; TaskRuntimeResolver runtime;
    String user="alice"; Actor alice,bob; Task task;
    @BeforeAll static void open() throws Exception { database=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void close() throws Exception { database.close(); }
    @BeforeEach void setup() {
        database.reset();runtime=mock(TaskRuntimeResolver.class);
        service=new CollaborationService(database.dsl(),new ObjectMapper().rebuild().findAndAddModules().build(),()->new PlatformUser(user,user,user,"",AuthSource.EXTERNAL,null,null,UserStatus.ACTIVE,Instant.now(),Instant.now(),null,List.of(Role.BUSINESS_USER)),runtime);
        commands=new TaskCommandService(service);executions=new TaskExecutionService(service,commands);
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
        long revision=service.detail(task.id()).task().revision(); CountDownLatch gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var calls=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){String title="contribution "+i;calls.add(executor.submit(()->{gate.await();try{commands.command(task.id(),alice.id(),new Command(title,revision,CommandKind.PUT_CONTEXT,Map.of("kind","NOTE","title",title,"body",Map.of())));return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}}));}
            gate.countDown();int count=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))count++;
            assertEquals(1,count);assertEquals(1,service.context(task.id(),null,100).items().size());
        }
    }
    @Test void restrictedContextAndItsHistoryDoNotLeakOrDeclassifyThroughSummaries() {
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
    @Test void noImplicitOrganizationOrParentTaskAccessAndLastManagerIsProtected() {
        var org=service.createOrganization(new CreateOrganization(null,"Team","",null));
        service.setMember(org.organization().id(),new SetMember(1,bob.id(),List.of("contributor")));
        var work=service.createTask(new CreateTask(org.organization().id(),"Private task","Goal",Map.of(),null));
        user="bob";assertEquals(1,service.organizations().size());assertThrows(ResponseStatusException.class,()->service.detail(work.id()));
        user="alice";assertThrows(ResponseStatusException.class,()->send(CommandKind.REMOVE_PARTICIPANT,Map.of("actorId",alice.id())));
        assertEquals(1,service.detail(task.id()).participants().size());
    }
    @Test void graphRejectsMixedCyclesAndCompletionWithOpenDependencies() {
        String child=send(CommandKind.CREATE_CHILD,Map.of("title","Experiment","goal","Validate hypothesis")).objectId();
        assertThrows(ResponseStatusException.class,()->send(child,CommandKind.LINK_TASK,Map.of("relatedTaskId",task.id(),"kind","DEPENDS_ON")));
        assertThrows(ResponseStatusException.class,()->send(CommandKind.TRANSITION,Map.of("status","COMPLETED")));
        send(child,CommandKind.TRANSITION,Map.of("status","COMPLETED"));send(CommandKind.TRANSITION,Map.of("status","COMPLETED"));
        assertEquals(TaskStatus.COMPLETED,service.detail(task.id()).task().status());
    }
    @Test void reviewGatesUseCurrentContentAndCurrentReviewerRoles() {
        participant(bob.id(),"reviewer");
        send(CommandKind.UPDATE,Map.of("policy",new Policy(defaultRoles(),1,false,List.of("Evidence verified"))));
        send(CommandKind.TRANSITION,Map.of("status","IN_REVIEW"));
        long content=service.detail(task.id()).task().contentRevision();
        assertThrows(ResponseStatusException.class,()->send(CommandKind.REVIEW,Map.of("approved",true,"contentRevision",content)));
        user="bob";send(CommandKind.REVIEW,Map.of("approved",true,"contentRevision",content));user="alice";
        note("New evidence",List.of());assertEquals(TaskStatus.ACTIVE,service.detail(task.id()).task().status());
        send(CommandKind.TRANSITION,Map.of("status","IN_REVIEW"));
        assertThrows(ResponseStatusException.class,()->send(CommandKind.TRANSITION,Map.of("status","COMPLETED")));
        user="bob";send(CommandKind.REVIEW,Map.of("approved",true,"contentRevision",service.detail(task.id()).task().contentRevision()));user="alice";
        send(CommandKind.TRANSITION,Map.of("status","COMPLETED"));
    }
    @Test void observersCannotContributeOrReceiveManagementViaPayload() {
        participant(bob.id(),"observer");user="bob";
        assertThrows(ResponseStatusException.class,()->note("not allowed",List.of()));
        assertThrows(ResponseStatusException.class,()->send(CommandKind.SET_PARTICIPANT,Map.of("actorId",bob.id(),"roles",List.of("owner"))));
    }
    @Test void roleDefinitionsAreGenericAndStillEnforcePermissions() {
        var custom=new Policy(List.of(new RoleDefinition("principal","Sponsor",Set.of(Permission.VIEW,Permission.MANAGE)),new RoleDefinition("investigator","Researcher",Set.of(Permission.VIEW,Permission.CONTRIBUTE))),0,false,List.of());
        var work=service.createTask(new CreateTask(null,"Custom roles","Goal",Map.of(),custom));
        assertEquals(List.of("principal"),service.detail(work.id()).participants().getFirst().roles());
        assertThrows(ResponseStatusException.class,()->send(work.id(),CommandKind.PUT_CONTEXT,Map.of("kind","NOTE","title","note","body",Map.of())));
    }
    @Test void agentExecutionUsesSelectedSnapshotAndRevocationStopsFurtherWork() {
        when(runtime.agentName("researcher")).thenReturn("Researcher");
        when(runtime.resolve("release","researcher",null)).thenReturn(new RuntimeSnapshot("assistant","1",null,null));
        var agent=service.registerAgent(new CreateAgentActor("researcher",null));participant(agent.id(),"contributor");
        String evidence=note("Original",List.of());
        String execution=send(CommandKind.START_EXECUTION,Map.of("actorId",agent.id(),"releaseId","release","instruction","Investigate","contextEntryIds",List.of(evidence))).objectId();
        send(CommandKind.PUT_CONTEXT,Map.of("entryId",evidence,"entryRevision",1,"title","Changed","body",Map.of("text","changed")));
        assertEquals("Original",executions.input(execution).context().context().getFirst().title());
        assertEquals(List.of(execution),service.store.pendingExecutions());
        executions.progress(execution,new ExecutionProgress(ExecutionStatus.WAITING,Map.of(),Map.of("id","wait-1"),null));
        assertThrows(ResponseStatusException.class,()->send(CommandKind.RESUME_EXECUTION,Map.of("executionId",execution,"waitingId","wrong","payload",Map.of())));
        send(CommandKind.RESUME_EXECUTION,Map.of("executionId",execution,"waitingId","wait-1","payload",Map.of("answer","continue")));
        assertEquals(1,service.store.pendingSignals().size());
        send(CommandKind.REMOVE_PARTICIPANT,Map.of("actorId",agent.id()));assertThrows(ResponseStatusException.class,()->executions.input(execution));
        executions.progress(execution,new ExecutionProgress(ExecutionStatus.FAILED,Map.of(),Map.of(),"permission revoked"));
        assertEquals(ExecutionStatus.FAILED,service.store.execution(execution).orElseThrow().status());
    }
    @Test void completedTasksRequireExplicitReopeningAndCancelledExecutionsStayCancelled() {
        send(CommandKind.TRANSITION,Map.of("status","COMPLETED"));assertThrows(ResponseStatusException.class,()->note("Late note",List.of()));
        send(CommandKind.TRANSITION,Map.of("status","ACTIVE"));note("New cycle",List.of());
        assertEquals(TaskStatus.ACTIVE,service.detail(task.id()).task().status());
    }
    @Test void crossSessionReadsIntersectHumanAndAgentScopesAndLinkingNeverGrantsAccess() {
        when(runtime.agentName("researcher")).thenReturn("Researcher");var agent=service.registerAgent(new CreateAgentActor("researcher",null));
        participant(bob.id(),"contributor");participant(agent.id(),"owner");note("Private to owners",List.of("owner"));note("Shared result",List.of());
        database.dsl().execute("""
            insert into session_runtime_session (id, scenario_id, title, customer_id, assistant_id, assistant_name,
              assistant_release_version, status, primary_agent_id, current_owner_agent_id, agent_turn_active,
              session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, created_at, updated_at, entry_scope)
            values ('session-one','scenario','Conversation','bob','assistant','Assistant','1','ACTIVE','researcher','researcher',
              false,false,false,false,'{}',now(),now(),'WEB')
            """);
        assertThrows(ResponseStatusException.class,()->send(CommandKind.LINK_SESSION,Map.of("sessionId","session-one")));
        user="bob";send(CommandKind.LINK_SESSION,Map.of("sessionId","session-one"));
        var bridge=new TaskSessionBridge(service);assertEquals(1,bridge.tasks("session-one","researcher").size());
        var shared=bridge.context("session-one","researcher",task.id(),null).items();assertEquals(1,shared.size());assertEquals("Shared result",shared.getFirst().title());
        assertThrows(ResponseStatusException.class,()->bridge.tasks("session-one","imposter"));
        user="alice";send(CommandKind.REMOVE_PARTICIPANT,Map.of("actorId",agent.id()));assertTrue(bridge.tasks("session-one","researcher").isEmpty());
    }
    @Test void cancellationCannotBeOverwrittenByLateRuntimeCompletion() {
        when(runtime.agentName("researcher")).thenReturn("Researcher");when(runtime.resolve("release","researcher",null)).thenReturn(new RuntimeSnapshot("assistant","1",null,null));
        var agent=service.registerAgent(new CreateAgentActor("researcher",null));participant(agent.id(),"contributor");
        String execution=send(CommandKind.START_EXECUTION,Map.of("actorId",agent.id(),"releaseId","release","instruction","Investigate")).objectId();
        send(CommandKind.CANCEL_EXECUTION,Map.of("executionId",execution));
        executions.progress(execution,new ExecutionProgress(ExecutionStatus.SUCCEEDED,Map.of("answer","too late"),Map.of(),null));
        assertEquals(ExecutionStatus.CANCELLED,service.store.execution(execution).orElseThrow().status());
        assertEquals(Map.of(),service.store.execution(execution).orElseThrow().result());
    }

    @Test void executionChildDelegationCannotExposeRestrictedInputToAnotherParticipant() {
        when(runtime.agentName("researcher")).thenReturn("Researcher");
        when(runtime.resolve("release","researcher",null)).thenReturn(new RuntimeSnapshot("assistant","1",null,null));
        var agent=service.registerAgent(new CreateAgentActor("researcher",null));
        participant(agent.id(),"owner");participant(bob.id(),"contributor");
        String source=note("Private investigation",List.of("owner"));
        String execution=send(CommandKind.START_EXECUTION,Map.of("actorId",agent.id(),"releaseId","release","instruction","Decompose investigation","contextEntryIds",List.of(source))).objectId();
        long revision=service.detail(task.id()).task().revision();
        var denied=new Command("denied-child",revision,CommandKind.CREATE_CHILD,Map.of("title","Private follow-up","goal","Investigate","participants",List.of(new Participant(bob.id(),List.of("owner")))));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->executions.command(execution,denied)).getStatusCode().value());
        assertTrue(service.detail(task.id()).relations().isEmpty());
        var granted=new Command("handover-child",revision,CommandKind.CREATE_CHILD,Map.of("title","Private follow-up","goal","Investigate","participants",List.of(new Participant(alice.id(),List.of("owner")))));
        String child=executions.command(execution,granted).objectId();
        assertTrue(service.detail(child).permissions().contains(Permission.MANAGE));
        assertEquals(2,service.detail(child).participants().size());
    }
}
