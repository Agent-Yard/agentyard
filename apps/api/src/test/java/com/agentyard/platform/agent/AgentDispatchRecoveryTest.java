package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.persistence.jooq.Tables.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentyard.platform.catalog.CatalogService;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL locking/outboxes; the transport alone is fault-injected at the ACK boundary. */
class AgentDispatchRecoveryTest {
    static EmbeddedPostgresTestDatabase database;
    AgentService agents;ToolRunService runs;AgentWorkflowGateway gateway;
    @BeforeAll static void open() throws Exception {database=new EmbeddedPostgresTestDatabase();}
    @AfterAll static void close() throws Exception {database.close();}
    @BeforeEach void setup() {
        database.reset();var db=database.dsl();var mapper=new ObjectMapper();
        db.execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values ('human','HUMAN','human','Human',now())");
        db.execute("insert into catalog_role_template(id,name,description,owner_type,owner_id) values ('template','Worker','','PERSON','human')");
        db.execute("insert into catalog_role_template_version(id,template_id,version,status,created_at,configuration) values ('template-v1','template','1','PUBLISHED',now(),'{}')");
        db.execute("insert into catalog_playbook(id,name,description,owner_type,owner_id) values ('flow','Flow','','PERSON','human')");
        db.execute("insert into catalog_playbook_version(id,playbook_id,version,status,created_at,definition) values ('flow-v1','flow','1','PUBLISHED',now(),'{}')");
        var catalog=mock(CatalogService.class);var authorization=new AgentAuthorization(catalog);
        agents=new AgentService(db,mapper,authorization,catalog,mock(CollaborationService.class));
        runs=new ToolRunService(agents,new CapabilityAccess(authorization));gateway=mock(AgentWorkflowGateway.class);
        session("session","ACTIVE");activation("origin","session","SUCCEEDED");
    }
    @Test void pausedCompletionPersistsOneBoundedEventAndResumesFromTheInbox() {
        run("run","session",null,"RUNNING",true);
        database.dsl().execute("update agent_session set status='PAUSED' where id='session'");
        var result=new ToolRunProgress(ToolRunStatus.SUCCEEDED,Map.of("largeArtifact","x".repeat(250000)),Map.of(),null);
        runs.progress("run",result);runs.progress("run",result);
        assertEquals(ToolRunStatus.SUCCEEDED,run("run").status());
        assertEquals(1,eventCount());
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        assertEquals(1,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("QUEUED")));
        assertEquals(0,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("RUNNING")));
        database.dsl().execute("update agent_session set status='ACTIVE' where id='session'");
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        assertEquals(1,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("RUNNING")));
    }
    @Test void closedInstanceAndRevokedOrCompletedWorkRetainReceiptsWithoutWaking() {
        session("closed","CLOSED");activation("closed-origin","closed","SUCCEEDED");
        run("closed-run","closed",null,"RUNNING",true);
        for(String id:List.of("cancelled","completed","revoked")) {
            task(id,id.equals("completed")?"COMPLETED":id.equals("cancelled")?"CANCELLED":"ACTIVE");
            run(id,"session",id,"RUNNING",true);
        }
        database.dsl().execute("delete from collaboration_participant where task_id='revoked'");
        for(String id:List.of("closed-run","cancelled","completed","revoked")) {
            runs.progress(id,new ToolRunProgress(ToolRunStatus.SUCCEEDED,Map.of("receipt",id),Map.of(),null));
            assertEquals(ToolRunStatus.CANCELLED,run(id).status());
            assertEquals(id,run(id).result().get("receipt"));
        }
        assertEquals(0,eventCount());
    }
    @Test void lateCompletionAfterRecordedCancellationIsRetainedOnceWithoutResurrection() {
        run("run","session",null,"CANCELLED",true);
        runs.progress("run",new ToolRunProgress(ToolRunStatus.SUCCEEDED,Map.of("receipt","real"),Map.of(),null));
        runs.progress("run",new ToolRunProgress(ToolRunStatus.SUCCEEDED,Map.of("receipt","duplicate"),Map.of(),null));
        runs.progress("run",new ToolRunProgress(ToolRunStatus.CANCELLED,Map.of(),Map.of(),"cancelled"));
        assertEquals(ToolRunStatus.CANCELLED,run("run").status());assertEquals("real",run("run").result().get("receipt"));
        assertNotNull(database.dsl().selectFrom(AGENT_TOOL_RUN).where(AGENT_TOOL_RUN.ID.eq("run")).fetchOne().getCompletionReceivedAt());
        assertEquals(0,eventCount());
        run("failed","session",null,"CANCELLED",true);
        runs.progress("failed",new ToolRunProgress(ToolRunStatus.FAILED,Map.of("receipt","declined"),Map.of(),"provider declined the write"));
        assertTrue(run("failed").failureReason().contains("FAILED"));
        assertTrue(run("failed").failureReason().contains("provider declined the write"));
    }
    @Test void pausesAreDistinctFromDeniedAuthorizationBeforeAnyNodeEffect() {
        task("task","ACTIVE");run("run","session","task","RUNNING",true);
        database.dsl().execute("update agent_session set status='PAUSED' where id='session'");
        assertEquals(423,assertThrows(ResponseStatusException.class,()->runs.runtime("run")).getStatusCode().value());
        database.dsl().execute("update agent_session set status='ACTIVE' where id='session'");
        database.dsl().execute("update collaboration_task set status='PAUSED' where id='task'");
        assertEquals(423,assertThrows(ResponseStatusException.class,()->runs.authorizeNode("run",new ToolRunNodeAuthorization("node","execution"))).getStatusCode().value());
        database.dsl().execute("delete from collaboration_participant where task_id='task'");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->runs.runtime("run")).getStatusCode().value());
    }
    @Test void controlScanReachesBeyondTwoHundredUnchangedWaitsAndQueuedActivations() {
        session("parked","PAUSED");activation("parked-origin","parked","SUCCEEDED");
        for(int i=0;i<200;i++) {
            run("a-"+String.format("%03d",i),"session",null,"WAITING",true);
            activation("a-"+String.format("%03d",i),"parked","QUEUED");
        }
        session("closed","CLOSED");activation("z-target","closed","RUNNING");
        run("z-target","closed",null,"WAITING",true);
        var dispatcher=new AgentDispatcher(agents,gateway);dispatcher.dispatch();dispatcher.dispatch();
        assertEquals(ToolRunStatus.CANCELLED,run("z-target").status());
        assertEquals(ActivationStatus.CANCELLED,agents.store().activation("z-target",false).orElseThrow().status());
        verify(gateway).cancelToolRun("z-target");verify(gateway).cancelActivation("z-target");
    }
    @Test void pausedRowsCannotFillTheStartSignalOrScheduleBatch() {
        session("parked","PAUSED");activation("parked-origin","parked","SUCCEEDED");
        for(int i=0;i<50;i++) {
            String id="a-"+String.format("%03d",i);
            run("queued-"+id,"parked",null,"QUEUED",false);
            run("waiting-"+id,"parked",null,"WAITING",true);signal(id,"waiting-"+id,"parked");schedule(id,"parked");
        }
        run("z-queued","session",null,"QUEUED",false);run("z-waiting","session",null,"WAITING",true);
        signal("z-signal","z-waiting","session");schedule("z-schedule","session");
        agents.store().grant(new Grant("grant","agent-session","schedule.create",ScopeKind.SESSION,"session",false,"human",null,null,null,Instant.now()));
        new AgentDispatcher(agents,gateway).dispatch();
        verify(gateway).startToolRun("z-queued");verify(gateway).resumeToolRun(eq("z-waiting"),any());
        verify(gateway,never()).startToolRun(startsWith("queued-a"));verify(gateway,never()).resumeToolRun(startsWith("waiting-a"),any());
        assertFalse(agents.store().schedule("z-schedule").orElseThrow().active());
        assertEquals(50,database.dsl().fetchCount(AGENT_SCHEDULE,AGENT_SCHEDULE.ACTIVE.isTrue()));
    }
    @Test void startupAndCancellationSerializeAcrossApiProcesses() throws Exception {
        activation("running","session","RUNNING");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).when(gateway).startActivation("running");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->new AgentDispatcher(agents,gateway).dispatch());
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            database.dsl().execute("update agent_session set status='CLOSED' where id='session'");
            var second=pool.submit(()->new AgentDispatcher(agents,gateway).dispatch());
            assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));
            verify(gateway,never()).cancelActivation("running");release.countDown();
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        verify(gateway,times(1)).startActivation("running");verify(gateway,times(1)).cancelActivation("running");
        var stored=database.dsl().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq("running")).fetchOne();
        assertEquals("CANCELLED",stored.getStatus());assertNotNull(stored.getCancellationDeliveredAt());
    }
    @Test void lostStartAndCancelAcknowledgementsRemainRecoverable() {
        activation("running","session","RUNNING");
        doThrow(new IllegalStateException("lost ACK")).doThrow(new WorkflowExecutionAlreadyStarted(io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId("activation:running").build(),"ActivationWorkflow",null)).when(gateway).startActivation("running");
        new AgentDispatcher(agents,gateway).dispatch();
        assertNull(database.dsl().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq("running")).fetchOne().getDispatchedAt());
        new AgentDispatcher(agents,gateway).dispatch();
        assertNotNull(database.dsl().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq("running")).fetchOne().getDispatchedAt());
        database.dsl().execute("update agent_session set status='CLOSED' where id='session'");
        doThrow(new IllegalStateException("lost cancel ACK")).doNothing().when(gateway).cancelActivation("running");
        new AgentDispatcher(agents,gateway).dispatch();
        assertNull(database.dsl().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq("running")).fetchOne().getCancellationDeliveredAt());
        assertEquals(ActivationStatus.CANCELLED,agents.store().activation("running",false).orElseThrow().status());
        new AgentDispatcher(agents,gateway).dispatch();
        assertNotNull(database.dsl().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq("running")).fetchOne().getCancellationDeliveredAt());
        verify(gateway,times(2)).cancelActivation("running");verify(gateway,times(2)).startActivation("running");
    }
    @Test void cancellationAlsoTargetsAStartWhoseDatabaseAckWasLost() {
        activation("running","session","RUNNING");
        doThrow(new IllegalStateException("accepted but ACK lost")).when(gateway).startActivation("running");
        new AgentDispatcher(agents,gateway).dispatch();
        database.dsl().execute("update agent_session set status='CLOSED' where id='session'");
        new AgentDispatcher(agents,gateway).dispatch();
        verify(gateway).cancelActivation("running");
        assertEquals(ActivationStatus.CANCELLED,agents.store().activation("running",false).orElseThrow().status());
    }
    @Test void completedWorkCannotStrandAnActivationThatWasNeverStarted() {
        task("completed","COMPLETED");activation("running","session","RUNNING");
        database.dsl().execute("update collaboration_message set task_id='completed' where id='input-running'");
        new AgentDispatcher(agents,gateway).dispatch();
        assertEquals(ActivationStatus.CANCELLED,agents.store().activation("running",false).orElseThrow().status());
        verify(gateway,never()).startActivation("running");
    }
    @Test void revokingExecutionPermissionCancelsAWaitEvenWhenTheAgentCanStillReadTheTask() {
        task("work","ACTIVE");run("run","session","work","WAITING",true);
        agents.store().tasks().participant("work","agent-session",List.of("observer"));
        new AgentDispatcher(agents,gateway).dispatch();
        assertEquals(ToolRunStatus.CANCELLED,run("run").status());verify(gateway).cancelToolRun("run");
    }
    @Test void concurrentApiDispatchersRespectTheSharedActivationCapacity() throws Exception {
        for(int i=0;i<40;i++) {session("capacity-"+i,"ACTIVE");activation("capacity-"+i,"capacity-"+i,"QUEUED");}
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->new AgentDispatcher(agents,gateway).dispatch());
            var b=pool.submit(()->new AgentDispatcher(agents,gateway).dispatch());a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertEquals(32,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("RUNNING")));
        assertEquals(8,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("QUEUED")));
        verify(gateway,times(32)).startActivation(anyString());
    }

    @Test void lostPauseAckCannotSuppressAResumeThatArrivesBeforeRetry() {
        run("run","session",null,"WAITING",true);
        database.dsl().execute("update agent_session set status='PAUSED' where id='session'");
        doThrow(new IllegalStateException("Temporal accepted pause but ACK was lost")).when(gateway).pauseToolRun("run",true);
        new AgentDispatcher(agents,gateway).dispatch();
        assertEquals("PENDING",database.dsl().selectFrom(AGENT_TOOL_RUN).where(AGENT_TOOL_RUN.ID.eq("run")).fetchOne().getDispatchedControl());
        database.dsl().execute("update agent_session set status='ACTIVE' where id='session'");
        new AgentDispatcher(agents,gateway).dispatch();
        verify(gateway).pauseToolRun("run",false);
        assertEquals("ACTIVE",database.dsl().selectFrom(AGENT_TOOL_RUN).where(AGENT_TOOL_RUN.ID.eq("run")).fetchOne().getDispatchedControl());
    }

    @Test void requestedChangesWakeOnlyTheResponsibleAgentWithoutASubscriptionAndDeduplicateSubscriptions() {
        task("work","ACTIVE");database.dsl().execute("update agent_session set status='PAUSED' where id='session'");
        requestedChanges(1,"agent-session","human");
        new AgentDispatcher(agents,gateway).dispatch();
        assertEquals(1,eventCount());
        var message=agents.store().messages("agent-session",null,null,100).stream().filter(m->m.kind()==MessageKind.EVENT).findFirst().orElseThrow();
        assertEquals("decision-1",message.body().get("decisionId"));assertEquals("work",message.taskId());
        agents.store().grant(new Grant("watch-grant","agent-session","subscription.create",ScopeKind.SESSION,"session",false,"human",null,null,null,Instant.now()));
        agents.store().subscription(new Subscription("watch","session",ScopeKind.TASK,"work",List.of("REQUEST_CHANGES"),true,"human",Instant.now().minusSeconds(1)));
        requestedChanges(2,"agent-session","human");
        new AgentDispatcher(agents,gateway).dispatch();new AgentDispatcher(agents,gateway).dispatch();
        assertEquals(2,eventCount());assertEquals(2,database.dsl().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("QUEUED")));
        verify(gateway,never()).startActivation(anyString());
    }
    @Test void requestedChangesDoNotWakeClosedRevokedOrCurrentlyExecutingRecipients() {
        task("work","ACTIVE");
        requestedChanges(1,"agent-session","agent-session");new AgentDispatcher(agents,gateway).dispatch();
        database.dsl().execute("delete from collaboration_participant where task_id='work'");
        requestedChanges(2,"agent-session","human");new AgentDispatcher(agents,gateway).dispatch();
        agents.store().tasks().participant("work","agent-session",List.of("contributor"));
        database.dsl().execute("update agent_session set status='CLOSED' where id='session'");
        requestedChanges(3,"agent-session","human");new AgentDispatcher(agents,gateway).dispatch();
        assertEquals(0,eventCount());
        assertEquals(0,database.dsl().fetchCount(COLLABORATION_EVENT_OUTBOX,COLLABORATION_EVENT_OUTBOX.DELIVERED_AT.isNull()));
    }
    private void requestedChanges(long revision,String recipient,String executor) {
        agents.store().tasks().event(new Event("work",revision,executor,"REQUEST_CHANGES",Map.of(),java.util.Set.of(),Instant.now()));
        agents.store().tasks().publish("work","REQUEST_CHANGES",executor,Map.of("revision",revision,"notifyParticipantId",recipient,"decisionId","decision-"+revision));
    }

    private long eventCount() {return database.dsl().fetchCount(COLLABORATION_MESSAGE,COLLABORATION_MESSAGE.KIND.eq("EVENT"));}
    private ToolRun run(String id) {return agents.store().toolRun(id,false).orElseThrow();}
    private void session(String id,String status) {
        database.dsl().execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values (?,'AGENT',?,?,now())","agent-"+id,id,id);
        database.dsl().execute("insert into agent_session(id,participant_id,template_version_id,name,status,created_by,reply_to_participant_id,created_at,updated_at) values (?,?,'template-v1',?,?,'human','human',now(),now())",id,"agent-"+id,id,status);
    }
    private void activation(String id,String session,String status) {
        database.dsl().execute("insert into collaboration_message(id,sender_id,recipient_id,kind,body,created_at) values (?,'human',?,'MESSAGE','{}',now())","input-"+id,"agent-"+session);
        database.dsl().execute("insert into agent_activation(id,session_id,input_message_id,status,created_at,updated_at) values (?,?,?,?,now(),now())",id,session,"input-"+id,status);
    }
    private void run(String id,String session,String task,String status,boolean dispatched) {
        var now=Instant.now();String activation=database.dsl().select(AGENT_ACTIVATION.ID).from(AGENT_ACTIVATION).where(AGENT_ACTIVATION.SESSION_ID.eq(session)).limit(1).fetchOne(AGENT_ACTIVATION.ID);
        agents.store().toolRun(new ToolRun(id,session,"agent-"+session,activation,task,"flow-v1",ToolRunStatus.valueOf(status),Map.of(),Map.of(),Map.of(),null,now,now));
        if(dispatched) database.dsl().execute("update agent_tool_run set dispatched_at=now(),dispatched_control='ACTIVE' where id=?",id);
    }
    private void task(String id,String status) {
        var now=Instant.now();agents.store().tasks().saveTask(new Task(id,null,id,"Goal",TaskStatus.valueOf(status),"",1,1,Map.of(),new Policy(defaultRoles(),List.of()),"human","human","human",null,now,now));
        agents.store().tasks().participant(id,"agent-session",List.of("contributor"));
    }
    private void signal(String id,String run,String session) {
        database.dsl().execute("insert into agent_tool_run_signal(id,tool_run_id,waiting_id,participant_id,source,payload,created_at) values (?,?,?,'human','HUMAN','{}',now())",id,run,id);
    }
    private void schedule(String id,String session) {
        agents.store().schedule(new Schedule(id,session,null,Instant.now().minusSeconds(60),"Continue",null,true,"human",Instant.now()));
    }
}
