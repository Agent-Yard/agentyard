package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.persistence.jooq.Tables.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.agentyard.contracts.runtime.WorkflowContracts.*;
import com.agentyard.platform.auth.AuthModels.*;
import com.agentyard.platform.catalog.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.knowledge.*;
import com.agentyard.platform.task.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Production services, SQL constraints and Spring/Jooq transactions; no mocked business outcomes. */
class AgentIntegrationTest {
    static EmbeddedPostgresTestDatabase database;
    DSLContext db;ObjectMapper mapper;DataSourceTransactionManager transactions;
    AgentService agents;AgentToolService tools;ToolRunService runs;CollaborationService collaboration;TaskCommandService commands;CatalogService catalog;
    Actor human;Reception reception;RoleTemplateDto template;EntryDto entry;
    String username="alice";
    @BeforeAll static void open() throws Exception {database=new EmbeddedPostgresTestDatabase();}
    @AfterAll static void close() throws Exception {database.close();}
    @BeforeEach void setup() {
        database.reset();username="alice";mapper=new ObjectMapper().rebuild().findAndAddModules().build();
        transactions=new DataSourceTransactionManager(database.dataSource());
        var config=new DefaultConfiguration().set(SQLDialect.POSTGRES)
            .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(database.dataSource())))
            .set(new SpringTransactionProvider(transactions));
        db=DSL.using(config);
        catalog=new CatalogService(new JooqCatalogRepository(db,mapper),new KnowledgeServiceClient("http://unused.invalid","test"),mock(KnowledgeWorkflowGateway.class));
        collaboration=new CollaborationService(db,mapper,()->new PlatformUser(username,username,username,"",AuthSource.EXTERNAL,null,null,
            UserStatus.ACTIVE,Instant.now(),Instant.now(),null,List.of(Role.BUSINESS_USER)));
        commands=new TaskCommandService(collaboration);human=collaboration.currentActor();
        var authorization=new AgentAuthorization(catalog);agents=new AgentService(db,mapper,authorization,catalog,collaboration);
        var capabilities=new CapabilityAccess(authorization);runs=new ToolRunService(agents,capabilities);
        tools=new AgentToolService(agents,commands,runs,capabilities,transactions);
        var model=catalog.createResource(new CreateResourceRequest("Test model",ResourceType.LLM_MODEL,ShareScope.PRIVATE,"PERSON","alice","","",List.of(),
            new CreateResourceVersionRequest("Test configuration only",VersionStatus.PUBLISHED,new ResourceVersionConfigurationDto(ResourceType.LLM_MODEL,null,
                new LlmModelConfigDto("OPENAI_COMPATIBLE","fixture-model","http://unused.invalid/v1","FIXTURE_MODEL_KEY",0.2,4096),null))));
        template=catalog.createRoleTemplate(new CreateRoleTemplateRequest("Reception","","PERSON","alice",new CreateRoleTemplateVersionRequest(VersionStatus.PUBLISHED,
            new RoleConfigurationDto("reception","Coordinate work","Preserve explicit decisions",model.effectiveVersion().id(),null,false,null,16,List.of(),List.of(),List.of()))));
        entry=catalog.createEntry(new EntryRequest("Welcome","","PERSON","alice",ShareScope.SHARED,template.effectiveVersion().id(),true,List.of(template.id()),
            List.of(new GrantRequest("task.create",ScopeKind.PLATFORM,"*",true,null),new GrantRequest("schedule.create",ScopeKind.SESSION,"*",true,null),
                new GrantRequest("subscription.create",ScopeKind.SESSION,"*",true,null))));
        reception=agents.receptionFor(human.id(),entry.id());
    }
    Session session() {return agents.store().session(reception.session().id(),false).orElseThrow();}
    Activation activate(String text) {
        agents.sendAs(human.id(),new SendMessage(UUID.randomUUID().toString(),session().participantId(),null,null,MessageKind.MESSAGE,Map.of("text",text)));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        return agents.store().activations(session().id(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
    }
    Task createTask(Activation activation) {
        var result=tools.invoke(activation.id(),new ToolInvocation("create-work","task_create",Map.of("title","Research project","goal","Find evidence", "sourceMessageId",activation.inputMessageId())));
        assertTrue(result.success(),result.message());return mapper.convertValue(result.result(),Task.class);
    }
    Session child(Task task,List<GrantRequest> grants) {
        return agents.createAs(session().participantId(),new CreateSession(UUID.randomUUID().toString(),template.effectiveVersion().id(),"Researcher",task==null?null:task.id(),"Investigate",grants,List.of()));
    }
    @Test void explicitResultAndAutomaticCompletionHaveOneDurableDelivery() {
        var activation=activate("Write a result");
        var first=tools.invoke(activation.id(),new ToolInvocation("result-one","message_send",Map.of(
            "recipientId",human.id(),"kind","RESULT","body",Map.of("text","First result"))));
        var repeated=tools.invoke(activation.id(),new ToolInvocation("result-two","message_send",Map.of(
            "recipientId",human.id(),"kind","RESULT","body",Map.of("text","Rephrased receipt"))));
        assertTrue(first.success());assertTrue(repeated.success());
        assertEquals(mapper.convertValue(first.result(),Message.class).id(),mapper.convertValue(repeated.result(),Message.class).id());
        var content=new com.agentyard.contracts.runtime.RuntimeContracts.MessageContent(List.of(Map.of("type","TEXT","text","Automatic echo")),Map.of());
        agents.complete(activation.id(),new ActivationOutcome(true,content,Map.of(),null,List.of(),null,null,"COMPLETED",false));
        var delivered=agents.receptionDetail(session().id(),null).messages().stream().filter(m->m.senderId().equals(session().participantId())).toList();
        assertEquals(1,delivered.size());assertEquals("First result",delivered.getFirst().body().get("text"));
    }

    @Test void sharedWorkExposesExecutionStatusWithoutPrivateSessionHistory() {
        var activation=activate("Delegate");var task=createTask(activation);var colleague=child(task,List.of());
        assertEquals("QUEUED",agents.executionStatus(human.id(),colleague.id(),task.id()).execution());
        assertFalse(agents.executionStatus(human.id(),colleague.id(),task.id()).historyReadable());
        assertThrows(ResponseStatusException.class,()->agents.detail(human.id(),colleague.id()));
        assertThrows(ResponseStatusException.class,()->agents.executionStatus(human.id(),colleague.id(),null));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var running=agents.store().activations(colleague.id(),10).getFirst();
        assertEquals("RUNNING",agents.executionStatus(human.id(),colleague.id(),task.id()).execution());
        agents.complete(running.id(),new ActivationOutcome(true,null,Map.of(),null,List.of(),null,null,"WAITING",false));
        var waiting=agents.executionStatus(human.id(),colleague.id(),task.id());
        assertEquals(SessionStatus.ACTIVE,waiting.lifecycle());assertEquals("WAITING",waiting.execution());
        var continued=tools.invoke(activation.id(),new ToolInvocation("continue-without-task","message_send",Map.of(
            "recipientId",colleague.participantId(),"kind","DELEGATION","body",Map.of("text","Continue the existing work"))));
        assertTrue(continued.success());
        assertEquals("QUEUED",agents.executionStatus(human.id(),colleague.id(),task.id()).execution());
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        assertEquals("RUNNING",agents.executionStatus(human.id(),colleague.id(),task.id()).execution());
        username="outsider";
        assertThrows(ResponseStatusException.class,()->agents.executionStatus(collaboration.currentActor().id(),colleague.id(),task.id()));
    }
    @Test void receptionIsPersistentUnderConcurrentAllocationAndForeignUsersCannotReadIt() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Reception>>();for(int i=0;i<16;i++)futures.add(pool.submit(()->agents.receptionFor(human.id(),entry.id())));
            for(var future:futures)assertEquals(session().id(),future.get(10,TimeUnit.SECONDS).session().id());
        }
        assertEquals(1,db.fetchCount(AGENT_SESSION));
        agents.memory(session().id(),Map.of("privateWorkingNote","do not publish"));
        assertTrue(agents.receptionDetail(session().id(),null).session().workMemory().isEmpty());
        assertThrows(ResponseStatusException.class,()->agents.detail(session().id()));
        username="bob";assertThrows(ResponseStatusException.class,()->agents.receptionDetail(session().id(),null));
        assertNotEquals(session().id(),agents.receptionFor(collaboration.currentActor().id(),entry.id()).session().id());
    }
    @Test void messagesAreIdempotentAndOnlyOneActivationOwnsEachContext() throws Exception {
        var request=new SendMessage("same-message",session().participantId(),null,null,MessageKind.MESSAGE,Map.of("text","Help"));
        var first=agents.sendAs(human.id(),request);assertEquals(first.id(),agents.sendAs(human.id(),request).id());
        assertThrows(ResponseStatusException.class,()->agents.sendAs(human.id(),new SendMessage("same-message",session().participantId(),null,null,MessageKind.MESSAGE,Map.of("text","different"))));
        agents.sendAs(human.id(),new SendMessage("second",session().participantId(),null,null,MessageKind.MESSAGE,Map.of("text","And this")));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->agents.store().transaction(s->{s.claimActivations(10);return null;}));
            var b=pool.submit(()->agents.store().transaction(s->{s.claimActivations(10);return null;}));a.get();b.get();
        }
        var activations=agents.store().activations(session().id(),10);
        assertEquals(1,activations.stream().filter(a->a.status()==ActivationStatus.RUNNING).count());
        assertEquals(1,activations.stream().filter(a->a.status()==ActivationStatus.QUEUED).count());
        String current=activations.stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow().id();
        agents.complete(current,new ActivationOutcome(true,null,Map.of("taskReference","123"),null,List.of(),null,null, "COMPLETED", false));
        agents.complete(current,new ActivationOutcome(true,null,Map.of("taskReference","wrong"),null,List.of(),null,null, "COMPLETED", false));
        assertEquals("123",session().workMemory().get("taskReference"));
        agents.store().transaction(s->{s.claimActivations(10);return null;});
        assertEquals(1,agents.store().activations(session().id(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).count());
    }
    @Test void toolEffectsAndReceiptsCommitAtomicallyAcrossServices() {
        var activation=activate("Organize this research");
        db.execute("create function reject_receipt() returns trigger language plpgsql as $$ begin raise exception 'simulated crash at receipt'; end $$");
        db.execute("create trigger reject_receipt before insert on collaboration_operation_receipt for each row execute function reject_receipt()");
        assertThrows(RuntimeException.class,()->createTask(activation));
        assertEquals(0,db.fetchCount(COLLABORATION_TASK));assertEquals(0,db.fetchCount(COLLABORATION_PARTICIPANT));assertEquals(0,db.fetchCount(COLLABORATION_EVENT_OUTBOX));
        db.execute("drop trigger reject_receipt on collaboration_operation_receipt");
        var first=createTask(activation);var retry=createTask(activation);
        assertEquals(first.id(),retry.id());assertEquals(1,db.fetchCount(COLLABORATION_TASK));
        assertEquals(human.id(),first.initiatedBy());assertEquals(human.id(),first.confirmerId());assertEquals(session().participantId(),first.createdBy());
    }
    @Test void failedDelegationLeavesNoPartialInstanceAndParentRevocationReachesChildren() {
        var activation=activate("Delegate");var task=createTask(activation);
        var invalid=new ToolInvocation("bad-agent","agent_create",Map.of("templateVersionId",template.effectiveVersion().id(),"taskId",task.id(),"instruction","Investigate",
            "grants",List.of(new GrantRequest("ungranted.write",ScopeKind.RESOURCE,"sensitive",false,null))));
        assertFalse(tools.invoke(activation.id(),invalid).success());assertEquals(1,db.fetchCount(AGENT_SESSION));assertEquals(2,db.fetchCount(COLLABORATION_ACTOR));
        var child=child(task,List.of(new GrantRequest("task.create",ScopeKind.PLATFORM,"*",false,null)));
        assertNotEquals(session().participantId(),child.participantId());assertEquals(child.id(),agents.store().tasks().actor(child.participantId()).orElseThrow().subjectId());
        agents.authorization().require(agents.store(),child.participantId(),"task.create",ScopeKind.PLATFORM,"*",false);
        var parent=agents.store().grants(session().participantId()).stream().filter(g->g.operation().equals("task.create")).findFirst().orElseThrow();
        agents.revoke(human.id(),parent.id());
        assertThrows(ResponseStatusException.class,()->agents.authorization().require(agents.store(),child.participantId(),"task.create",ScopeKind.PLATFORM,"*",false));
        assertThrows(ResponseStatusException.class,()->agents.detail(session().participantId(),child.id()));
    }
    @Test void sameTemplateSupportsDifferentTaskRolesAndCrossSessionSharedContext() {
        var activation=activate("Review independently");var task=createTask(activation);
        var researcher=child(task,List.of());
        var reviewer=agents.createAs(session().participantId(),new CreateSession("reviewer",template.effectiveVersion().id(),"Reviewer",task.id(),"Review evidence",List.of(),List.of("reviewer")));
        long revision=collaboration.detail(task.id()).task().revision();
        commands.command(task.id(),CommandAuthority.own(researcher.participantId()),new Command("evidence",revision,CommandKind.PUT_CONTEXT,
            Map.of("kind","FACT","title","Evidence","body",Map.of("text","Verified observation"))));
        assertEquals(1,collaboration.context(task.id(),reviewer.participantId(),null,100).items().size());
        assertTrue(agents.authorization().taskPermissions(agents.store(),reviewer.participantId(),task.id()).contains(Permission.DECIDE));
        assertFalse(agents.authorization().taskPermissions(agents.store(),researcher.participantId(),task.id()).contains(Permission.DECIDE));
        assertThrows(ResponseStatusException.class,()->agents.detail(reviewer.participantId(),researcher.id()));
    }
    @Test void proxyConfirmationRequiresCurrentHumanInputAndCannotUseAnOldInstruction() {
        var activation=activate("Start work");var task=createTask(activation);
        commands.command(task.id(),CommandAuthority.own(session().participantId()),new Command("submit",1,CommandKind.SUBMIT,Map.of()));
        assertFalse(tools.invoke(activation.id(),new ToolInvocation("forged","task_command",Map.of("taskId",task.id(),"expectedRevision",2,"kind","CONFIRM",
            "data",Map.of("contentRevision",1)))).success());
        agents.complete(activation.id(),new ActivationOutcome(true,null,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        var confirmation=activate("I accept the submitted result");
        assertFalse(tools.invoke(confirmation.id(),new ToolInvocation("stale","task_command",Map.of("taskId",task.id(),"expectedRevision",2,"kind","CONFIRM",
            "sourceMessageId",activation.inputMessageId(),"data",Map.of("contentRevision",1)))).success());
        var accepted=tools.invoke(confirmation.id(),new ToolInvocation("accept","task_command",Map.of("taskId",task.id(),"expectedRevision",2,"kind","CONFIRM",
            "sourceMessageId",confirmation.inputMessageId(),"data",Map.of("contentRevision",1))));
        assertTrue(accepted.success(),accepted.message());
        var detail=collaboration.detail(task.id());assertEquals(TaskStatus.COMPLETED,detail.task().status());
        assertEquals(human.id(),detail.decisions().getLast().decidedBy());assertEquals(session().participantId(),detail.decisions().getLast().executedBy());
    }
    @Test void durableSchedulesDoNotDuplicateAfterDispatcherRestartAndPauseParksNewInput() {
        var activation=activate("Check later");
        var scheduled=tools.invoke(activation.id(),new ToolInvocation("timer","schedule_create",Map.of("dueAt",Instant.now().minusSeconds(1).toString(),"instruction","Check progress")));
        assertTrue(scheduled.success(),scheduled.message());
        agents.complete(activation.id(),new ActivationOutcome(true,null,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        var unavailable=mock(io.temporal.client.WorkflowClient.class);
        new AgentDispatcher(agents,unavailable,"test").dispatch();new AgentDispatcher(agents,unavailable,"test").dispatch();
        assertEquals(2,db.fetchCount(COLLABORATION_MESSAGE));assertFalse(agents.store().schedules(session().id()).getFirst().active());
        assertEquals(1,agents.store().pendingActivations().size());
        agents.control(session().id(),"pause",SessionStatus.PAUSED);new AgentDispatcher(agents,unavailable,"test").dispatch();
        assertTrue(agents.store().pendingActivations().isEmpty());
        agents.sendAs(human.id(),new SendMessage("queued",session().participantId(),null,null,MessageKind.MESSAGE,Map.of("text","Wait until resumed")));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        assertEquals(1,db.fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("QUEUED")));
        agents.control(session().id(),"resume",SessionStatus.ACTIVE);agents.store().transaction(s->{s.claimActivations(50);return null;});
        assertEquals(1,db.fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("RUNNING")));
    }
    @Test void directHumanRepliesReachTheActualSpecialistThroughTheUnifiedInbox() {
        var activation=activate("Investigate");var task=createTask(activation);var specialist=child(task,List.of());
        var question=agents.sendAs(specialist.participantId(),new SendMessage("clarify",human.id(),task.id(),null,MessageKind.QUESTION,Map.of("text","Which budget applies?")));
        var inbox=agents.receptionDetail(session().id(),null);
        assertTrue(inbox.messages().stream().anyMatch(message->message.id().equals(question.id())));
        assertTrue(inbox.participants().stream().anyMatch(actor->actor.id().equals(specialist.participantId())));
        var response=agents.sendAs(human.id(),new SendMessage("budget",specialist.participantId(),task.id(),question.id(),MessageKind.MESSAGE,Map.of("text","The budget is 50000")));
        assertEquals(2,agents.store().activations(specialist.id(),10).size());
        assertEquals(question.id(),response.replyToMessageId());
        assertEquals(session().id(),agents.receptionFor(human.id(),entry.id()).session().id());
    }
    @Test void taskPausePreservesQueuedInputsAndRelatedMultiTaskSessionsRemainUsable() {
        var activation=activate("Two related investigations");var first=createTask(activation);var specialist=child(first,List.of());
        var second=collaboration.createTask(new CreateTask(null,"Related evidence","Explore",Map.of(),null));
        commands.command(second.id(),new Command("member",1,CommandKind.SET_PARTICIPANT,Map.of("actorId",specialist.participantId(),"roles",List.of("contributor"))));
        commands.command(second.id(),new Command("link",2,CommandKind.LINK_SESSION,Map.of("sessionId",specialist.id())));
        commands.command(first.id(),new Command("pause",2,CommandKind.TRANSITION,Map.of("status","PAUSED")));
        agents.sendAs(human.id(),new SendMessage("related",specialist.participantId(),second.id(),null,MessageKind.MESSAGE,Map.of("text","Continue this related part")));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var selected=agents.store().activations(specialist.id(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
        assertEquals(second.id(),agents.store().message(selected.inputMessageId()).orElseThrow().taskId());
        assertEquals(1,agents.store().activations(specialist.id(),10).stream().filter(a->a.status()==ActivationStatus.QUEUED).count());
        assertEquals(2,collaboration.sessionTasks(specialist.id()).size());
    }
    @Test void subscriptionsFilterRestrictedChangesAndRevokedAudiences() {
        var activation=activate("Observe shared work");var task=createTask(activation);
        var specialist=child(task,List.of(new GrantRequest("subscription.create",ScopeKind.SESSION,"*",false,null)));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var observer=agents.store().activations(specialist.id(),10).getFirst();
        var subscribed=tools.invoke(observer.id(),new ToolInvocation("watch","subscription_create",Map.of("scopeKind","TASK","scopeId",task.id(),"eventKinds",List.of("PUT_CONTEXT"))));
        assertTrue(subscribed.success(),subscribed.message());
        commands.command(task.id(),new Command("secret",2,CommandKind.PUT_CONTEXT,Map.of("kind","FACT","title","Private evidence","body",Map.of("text","private"),"visibleToRoles",List.of("owner"))));
        commands.command(task.id(),new Command("shared",3,CommandKind.PUT_CONTEXT,Map.of("kind","FACT","title","Shared evidence","body",Map.of("text","public"))));
        var unavailable=mock(io.temporal.client.WorkflowClient.class);new AgentDispatcher(agents,unavailable,"test").dispatch();
        assertEquals(1,db.fetchCount(COLLABORATION_MESSAGE,COLLABORATION_MESSAGE.RECIPIENT_ID.eq(specialist.participantId()).and(COLLABORATION_MESSAGE.KIND.eq("EVENT"))));
        commands.command(task.id(),new Command("remove",4,CommandKind.REMOVE_PARTICIPANT,Map.of("actorId",specialist.participantId())));
        commands.command(task.id(),new Command("later",5,CommandKind.PUT_CONTEXT,Map.of("kind","FACT","title","Later evidence","body",Map.of("text","public"))));
        new AgentDispatcher(agents,unavailable,"test").dispatch();
        assertEquals(1,db.fetchCount(COLLABORATION_MESSAGE,COLLABORATION_MESSAGE.RECIPIENT_ID.eq(specialist.participantId()).and(COLLABORATION_MESSAGE.KIND.eq("EVENT"))));
    }
    @Test void configurationUpdatesGrantNewCapabilitiesWithoutReplacingThePersistentInstance() {
        var updatedGrants=new ArrayList<>(entry.capabilityGrants());updatedGrants.add(new GrantRequest("resource.read",ScopeKind.RESOURCE,"watched-resource",false,null));
        catalog.updateEntry(entry.id(),new EntryRequest(entry.name(),entry.description(),entry.ownerType(),entry.ownerId(),entry.shareScope(),entry.receptionTemplateVersionId(),true,entry.allowedTemplateIds(),updatedGrants));
        var same=agents.receptionFor(human.id(),entry.id());assertEquals(session().id(),same.session().id());
        var activation=activate("Watch a resource");
        assertTrue(tools.invoke(activation.id(),new ToolInvocation("watch","subscription_create",Map.of("scopeKind","RESOURCE","scopeId","watched-resource","eventKinds",List.of("RESOURCE_PUBLISHED")))).success());
        db.execute("insert into platform_event(id,event_type,aggregate_type,aggregate_id,payload,occurred_at) values ('resource-published','RESOURCE_PUBLISHED','RESOURCE','watched-resource','{\"secret\":\"not part of event input\"}',now())");
        var unavailable=mock(io.temporal.client.WorkflowClient.class);new AgentDispatcher(agents,unavailable,"test").dispatch();new AgentDispatcher(agents,unavailable,"test").dispatch();
        var events=agents.store().messages(session().participantId(),null,null,100).stream().filter(message->message.kind()==MessageKind.EVENT).toList();
        assertEquals(1,events.size());assertFalse(events.getFirst().body().toString().contains("secret"));
    }
    @Test void cancelledActivationRetainsItsActualLateReceiptAndUsageWithoutReopeningWork() {
        var activation=activate("Research");
        agents.control(session().id(),"cancel",SessionStatus.CLOSED);
        new AgentDispatcher(agents,mock(io.temporal.client.WorkflowClient.class),"test").dispatch();
        assertEquals(ActivationStatus.CANCELLED,agents.store().activation(activation.id(),false).orElseThrow().status());
        int messages=db.fetchCount(COLLABORATION_MESSAGE);
        var usage=new com.agentyard.contracts.runtime.RuntimeContracts.LlmUsageEntry(
            com.agentyard.contracts.runtime.RuntimeContracts.LlmUsageSourceType.AGENT_MODEL,1,0,"OPENAI_COMPATIBLE","model","version","fixture",true,20,5,25,Map.of(),Instant.now());
        var outcome=new ActivationOutcome(true,null,Map.of("lateResult","persisted receipt"),null,List.of(usage),null,null, "COMPLETED", false);
        agents.complete(activation.id(),outcome);
        agents.complete(activation.id(),new ActivationOutcome(false,null,Map.of(),"different duplicate",List.of(),null,null, null, false));
        var receipt=db.selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq(activation.id())).fetchOne();
        assertEquals("CANCELLED",receipt.getStatus());assertNotNull(receipt.getCompletionReceivedAt());
        assertTrue(receipt.getOutcome().data().contains("persisted receipt"));
        assertEquals(1,db.fetchCount(LLM_USAGE_RECORD));assertEquals(messages,db.fetchCount(COLLABORATION_MESSAGE));
        assertTrue(session().workMemory().isEmpty());assertEquals(SessionStatus.CLOSED,session().status());
    }
    @Test void executionFailureIsReportedDurablyWithoutAutomaticallyRepeatingTheFailedWork() {
        var activation=activate("Investigate");var task=createTask(activation);var specialist=child(task,List.of());
        agents.complete(activation.id(),new ActivationOutcome(true,null,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var childActivation=agents.store().activations(specialist.id(),10).getFirst();
        var failure=new ActivationOutcome(false,null,Map.of(),"Private provider diagnostics",List.of(),null,null, null, false);
        agents.complete(childActivation.id(),failure);agents.complete(childActivation.id(),failure);
        var notifications=agents.store().messages(session().participantId(),null,null,100).stream()
            .filter(message->"ACTIVATION_FAILED".equals(message.body().get("eventKind"))).toList();
        assertEquals(1,notifications.size());assertEquals(childActivation.id(),notifications.getFirst().body().get("activationId"));
        assertFalse(notifications.getFirst().body().toString().contains("Private provider diagnostics"));
        assertEquals(1,agents.store().activations(specialist.id(),100).size());
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var parentActivation=agents.store().activations(session().id(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
        agents.complete(parentActivation.id(),failure);
        assertTrue(agents.receptionDetail(session().id(),null).messages().stream()
            .anyMatch(message->message.recipientId().equals(human.id())&&"ACTIVATION_FAILED".equals(message.body().get("eventKind"))));
        assertTrue(agents.store().pendingActivations().isEmpty());
    }
    @Test void automaticContextUsesReferencesAndFullTaskContentRemainsReadable() {
        var activation=activate("Investigate");var task=createTask(activation);var specialist=child(task,List.of());
        long revision=2;
        for(int i=0;i<25;i++) commands.command(task.id(),new Command("entry-"+i,revision++,CommandKind.PUT_CONTEXT,
            Map.of("kind","FACT","title","Evidence "+i,"body",Map.of("text","long evidence body "+i))));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var childActivation=agents.store().activations(specialist.id(),10).getFirst();
        var context=agents.runtime(childActivation.id()).context();
        assertFalse(mapper.writeValueAsString(context).contains("long evidence body"));
        var current=(Map<?,?>)context.get("currentTask");
        var references=(List<?>)current.get("contextReferences");assertEquals(20,references.size());assertNotNull(current.get("nextContextCursor"));
        var read=tools.invoke(childActivation.id(),new ToolInvocation("read-evidence","resource_read",Map.of("reference",references.getFirst())));
        assertTrue(read.success(),read.message());assertTrue(mapper.writeValueAsString(read.result()).contains("long evidence body"));
    }
    @Test void resultsReturnToTheResponsibleRecipientWithoutAnAgentAcknowledgementLoop() {
        var activation=activate("Delegate research");var task=createTask(activation);var specialist=child(task,List.of());
        agents.complete(activation.id(),new ActivationOutcome(true,null,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var childActivation=agents.store().activations(specialist.id(),10).getFirst();
        var content=new com.agentyard.contracts.runtime.RuntimeContracts.MessageContent(List.of(Map.of("type","TEXT","text","Research result")),Map.of());
        agents.complete(childActivation.id(),new ActivationOutcome(true,content,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var parentActivation=agents.store().activations(session().id(),10).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
        agents.complete(parentActivation.id(),new ActivationOutcome(true,content,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        assertEquals(1,agents.store().activations(specialist.id(),100).size());
        assertTrue(agents.receptionDetail(session().id(),null).messages().stream().anyMatch(m->m.senderId().equals(session().participantId())&&m.recipientId().equals(human.id())&&m.kind()==MessageKind.RESULT));
        // Even an explicitly sent courtesy RESULT from the parent must not automatically bounce back.
        agents.sendAs(session().participantId(),new SendMessage("courtesy",specialist.participantId(),task.id(),null,MessageKind.RESULT,Map.of("text","Thanks")));
        agents.store().transaction(s->{s.claimActivations(50);return null;});
        var courtesy=agents.store().activations(specialist.id(),100).stream().filter(a->a.status()==ActivationStatus.RUNNING).findFirst().orElseThrow();
        agents.complete(courtesy.id(),new ActivationOutcome(true,content,Map.of(),null,List.of(),null,null, "COMPLETED", false));
        assertEquals(2,agents.store().activations(session().id(),100).size());
        assertTrue(agents.store().pendingActivations().isEmpty());
    }
}
