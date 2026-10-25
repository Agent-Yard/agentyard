package com.agentyard.platform.task;

import static org.junit.jupiter.api.Assertions.*;
import static com.agentyard.platform.task.TaskModelFixture.Step.*;
import com.agentyard.platform.agent.*;
import com.agentyard.platform.catalog.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.contracts.runtime.WorkflowContracts.*;
import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType;
import com.agentyard.worker.agent.*;
import com.agentyard.worker.runtime.*;
import com.agentyard.worker.session.*;
import io.temporal.testing.TestWorkflowEnvironment;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Production Spring HTTP, Jooq transactions, embedded PostgreSQL, real local Redis,
 * real Temporal test server/Worker and Python lifespan with durable transcript.
 * Only identity, model responses and the supplier HTTP service are fixtures.
 * Load local env privately: source scripts/common/env.sh; load_agentyard_env apps/agent-runtime local
 * Run: ./gradlew :apps:api:taskE2eTest (optionally -Pdeepseek=true for paid model verification).
 * Live model configuration uses only AGENTYARD_E2E_MODEL_{ID,BASE_URL,API_KEY_ENV_VAR}.
 */
@Tag("task-e2e") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskCollaborationE2eTest {
    final ObjectMapper json=new ObjectMapper().rebuild().findAndAddModules()
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String token=UUID.randomUUID().toString();
    final Path repository=Path.of(System.getProperty("agentyard.repository"));
    EmbeddedPostgres database; ServletWebServerApplicationContext api; TestWorkflowEnvironment temporal;
    AgentDispatcher dispatcher; TaskModelFixture model; Process python; DSLContext db;
    String apiUrl,runtimeUrl,receptionParticipant,human,sessionId;
    Path runtimeLog;

    @BeforeAll void start() throws Exception {
        database=EmbeddedPostgres.builder().start();
        var source=new DriverManagerDataSource(database.getJdbcUrl("postgres","postgres"),"postgres","postgres");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        runtimeUrl="http://127.0.0.1:"+freePort(); temporal=TestWorkflowEnvironment.newInstance();
        api=(ServletWebServerApplicationContext)new SpringApplicationBuilder(TaskE2eServer.class)
            .initializers(context->context.getBeanFactory().registerSingleton("dataSource",source))
            .properties("spring.config.name=task-e2e","server.address=127.0.0.1","server.port=0",
                "spring.main.banner-mode=off","spring.jooq.sql-dialect=POSTGRES",
                "agentyard.internal-auth.token="+token,"agentyard.agent-runtime.base-url="+runtimeUrl).run("--agentyard.internal-auth.token="+token,"--agentyard.agent-runtime.base-url="+runtimeUrl);
        apiUrl="http://127.0.0.1:"+api.getWebServer().getPort(); db=api.getBean(DSLContext.class);
        db.execute("create database runtime_e2e"); model=new TaskModelFixture(json);model.script=this::scenario;
        seedCatalog(false);startPython();
        var worker=temporal.newWorker("agent-e2e");
        worker.registerWorkflowImplementationTypes(ActivationWorkflowImpl.class,ToolRunWorkflowImpl.class);
        var transport=new WorkerHttpClient(json,token);
        var runtime=new AgentRuntimeGateway.HttpAgentRuntimeGateway(transport,runtimeUrl);
        var control=new ExecutionControlPlane.HttpExecutionControlPlane(transport,apiUrl+"/api");
        worker.registerActivitiesImplementations(new ActivationActivitiesImpl(runtime,control),new ToolRunActivitiesImpl(control),
            new PlaybookNodeActivitiesImpl(new SandboxGateway.HttpSandboxGateway("http://127.0.0.1:8092",json),runtime,control,json));
        temporal.start();dispatcher=new AgentDispatcher(api.getBean(AgentService.class),temporal.getWorkflowClient(),"agent-e2e");
        var reception=call("sponsor","POST","/agent/reception",Map.of("entryId","entry"));
        receptionParticipant=reception.path("session").path("participantId").asText();
        sessionId=reception.path("session").path("id").asText();human=reception.path("humanParticipantId").asText();
    }
    @AfterAll void stop() throws Exception {
        if(db!=null&&python!=null&&python.isAlive()) {
            var evidence=new ArrayList<Map<String,Object>>();
            for(var row:db.fetch("select id,participant_id from agent_session")) {
                try {evidence.add(Map.of("sessionId",row.get(0,String.class),"participantId",row.get(1,String.class),"history",runtimeHistoryComplete(row.get(0,String.class),row.get(1,String.class))));}
                catch(Exception ignored) { /* Preserve other sessions if one is unavailable. */ }
            }
            String content=json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
            Files.writeString(repository.resolve("apps/api/build/task-e2e/transcript-evidence.json"),content);
            String mode=Boolean.getBoolean("agentyard.e2e.deepseek")?"live":"deterministic";
            Files.writeString(repository.resolve("apps/api/build/task-e2e/transcript-evidence-"+mode+"-"+System.currentTimeMillis()+".json"),content);
        }
        if(temporal!=null)temporal.close();stopPython();if(model!=null)model.close();if(api!=null)api.close();if(database!=null)database.close();
    }
    @Test @Order(1) void receptionDelegatesEvidenceAndHumanConfirmsWithoutChangingTheEntry() throws Exception {
        String task=research("Supplier selection");var detail=detail(task);
        assertEquals(human,detail.path("task").path("initiatedBy").asText());
        assertEquals("SUBMITTED",detail.path("task").path("status").asText());assertEquals(1,detail.path("context").size());
        assertEquals("supplier-api-fixture",detail.path("context").get(0).path("body").path("source").asText());
        assertEquals(1,model.supplierRequests.size());assertNotEquals(receptionParticipant,detail.path("context").get(0).path("authorId").asText());
        send("E2E_CONFIRM "+task);await(()->status(task).equals("COMPLETED"),Duration.ofSeconds(35));detail=detail(task);
        assertTrue(detail.path("decisions").toString().contains(human));assertTrue(detail.path("decisions").toString().contains(receptionParticipant));
        assertEquals(sessionId,call("sponsor","POST","/agent/reception",Map.of("entryId","entry")).path("session").path("id").asText());
        assertRuntimeHistory(sessionId,receptionParticipant,10);
        awaitIdle();
        System.out.println("E01/E06 PASS: reception -> Agent creates Task -> delegated Agent -> external evidence -> submit -> human confirmation provenance");
    }
    @Test @Order(2) void anotherWorkUsesSameReceptionAndRuntimeRestartPreservesItsContext() throws Exception {
        int before=model.supplierRequests.size();stopPython();startPython();
        dispatcher=new AgentDispatcher(api.getBean(AgentService.class),temporal.getWorkflowClient(),"agent-e2e");
        String task=research("Delivery comparison");assertEquals(before+1,model.supplierRequests.size());
        assertEquals(sessionId,call("sponsor","POST","/agent/reception",Map.of("entryId","entry")).path("session").path("id").asText());
        assertTrue(db.fetchCount(org.jooq.impl.DSL.table("agent_session"))>=3);assertRuntimeHistory(sessionId,receptionParticipant,20);
        String specialist=detail(task).path("context").get(0).path("authorId").asText();
        String specialistSession=db.fetchOne("select id from agent_session where participant_id=?",specialist).get(0,String.class);
        var history=runtimeHistory(specialistSession,specialist);
        assertFalse(history.toString().contains("Supplier selection"));assertTrue(history.toString().contains("Delivery comparison"));
        System.out.println("E02/E09 PASS: same reception, isolated specialists, actual Python restart and persisted transcript");
    }
    @Test @Order(3) void independentHumansCannotReadAnotherAgentsPrivateSessionOrTasks() throws Exception {
        var observer=call("observer","POST","/agent/reception",Map.of("entryId","entry"));
        assertNotEquals(sessionId,observer.path("session").path("id").asText());
        assertEquals(403,raw("observer","GET","/api/agent/sessions/"+sessionId,null).statusCode());
        assertEquals(403,raw("observer","GET","/api/agent/sessions/"+sessionId+"/history",null).statusCode());
        assertEquals(401,request("Bearer wrong","GET",runtimeUrl+"/sessions/"+sessionId+"/history?participantId="+receptionParticipant,null).statusCode());
        assertEquals(0,call("observer","GET","/collaboration/tasks",null).path("items").size());
        System.out.println("E07 PASS: role reuse does not share identity, private transcript or Task membership");
    }
    @Test @Order(4) void humanClarificationRemainsOrdinaryMessagesBetweenPersistentParticipants() throws Exception {
        send("E2E_CLARIFY Delivery constraint");
        await(()->conversation().contains("What is the delivery deadline?"),Duration.ofSeconds(35));
        String task=findTask("Delivery constraint");assertNotNull(task);assertEquals("OPEN",status(task));
        send("E2E_ANSWER Within fourteen days");
        await(()->status(task).equals("SUBMITTED")&&conversation().contains("Evidence ready "+task),Duration.ofSeconds(40));
        assertEquals(sessionId,call("sponsor","POST","/agent/reception",Map.of("entryId","entry")).path("session").path("id").asText());
        System.out.println("E05 PASS: specialist question -> reception -> human reply -> original specialist continues, no ownership transfer");
    }
    @Test @Order(5) void scheduledActivationAndStaleRevisionRecoverThroughActualTools() throws Exception {
        send("E2E_SCHEDULE");await(()->conversation().contains("Scheduled follow-up delivered"),Duration.ofSeconds(35));
        String task=findTask("Delivery comparison");send("E2E_CONFLICT "+task);
        await(()->detail(task).path("context").toString().contains("Reconciled revision"),Duration.ofSeconds(30));
        assertTrue(model.requests.stream().anyMatch(r->r.contains("HTTP_409")));
        System.out.println("E08/E13 PASS: durable timer wakes the same Session; stale tool write rejected then reread and corrected");
    }
    @Test @Order(100) void realDeepSeekCoordinatesTheSameEvidenceAndHumanAcceptancePath() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("agentyard.e2e.deepseek"), "enable -Pdeepseek=true for live model verification");
        String keyName=System.getenv().getOrDefault("AGENTYARD_E2E_MODEL_API_KEY_ENV_VAR","DEEPSEEK_API_KEY");
        assertFalse(System.getenv().getOrDefault(keyName,"").isBlank(),"Configured live model credential must be present");
        seedCatalog(true);
        var live=call("sponsor","POST","/agent/reception",Map.of("entryId","entry-live"));
        sessionId=live.path("session").path("id").asText();receptionParticipant=live.path("session").path("participantId").asText();
        int before=model.supplierRequests.size();String title="Live supplier assessment "+UUID.randomUUID().toString().substring(0,8);
        send("Please research Northwind Components for a supplier decision. Create a Task titled '"+title+"' on my behalf, using this human message as the source. Discover the authorized specialist role, delegate a new researcher Agent, grant it tool.invoke on supplier-tool, and ask it to call supplier_lookup, publish the actual external evidence as an ARTIFACT and submit the Task. Keep me informed here. Do not confirm completion for me.");
        await(()->findTask(title)!=null&&status(findTask(title)).equals("SUBMITTED"),Duration.ofMinutes(4));
        String task=findTask(title);assertEquals(before+1,model.supplierRequests.size());
        assertTrue(detail(task).path("context").toString().contains("report-supplier-1"));
        assertEquals(human,detail(task).path("task").path("initiatedBy").asText());
        send("I have reviewed the evidence for Task "+task+". I accept it; please confirm that Task on my behalf using this message and its current content revision.");
        await(()->status(task).equals("COMPLETED"),Duration.ofMinutes(2));
        assertRuntimeHistory(sessionId,receptionParticipant,20);
        awaitIdle();String persistentReception=sessionId;
        String nextTitle="Live delivery feasibility "+UUID.randomUUID().toString().substring(0,8);int supplierBefore=model.supplierRequests.size();
        send("Start another independent supplier assessment titled '"+nextTitle+"', for Northwind Components delivery feasibility. The required delivery deadline is missing. Delegate a NEW researcher for this new Task. Instruct it to ask me for the missing deadline through you, using QUESTION messages linked to the Task, and wait for my answer BEFORE calling supplier_lookup. Forward its question to me here; once I answer, forward it to that SAME researcher, which should publish the evidence report and submit the Task. Do not invent a deadline or confirm for me.");
        await(()->findTask(nextTitle)!=null&&hasQuestion(findTask(nextTitle)),Duration.ofMinutes(4));
        String second=findTask(nextTitle);assertEquals(supplierBefore,model.supplierRequests.size());
        var workerIds=db.fetch("select session_id from collaboration_task_session where task_id=?",second).getValues(0,String.class);
        send("For Task "+second+", the delivery deadline is at most 21 days. Please forward this answer to the SAME researcher, continue that work, obtain supplier evidence, publish a report and submit it for me to review.");
        await(()->status(second).equals("SUBMITTED"),Duration.ofMinutes(4));awaitIdle();
        assertEquals(supplierBefore+1,model.supplierRequests.size());assertEquals(workerIds,db.fetch("select session_id from collaboration_task_session where task_id=?",second).getValues(0,String.class));
        long previousRevision=detail(second).path("task").path("contentRevision").asLong();
        send("I request changes for Task "+second+". Record REQUEST_CHANGES as my decision using this message and the current content revision. Ask the SAME researcher to revise the report to explicitly discuss annual capacity 1200 and lead time 14 days against my 21-day deadline. Existing supplier evidence is sufficient; no new supplier lookup is needed. Publish the revised report and submit again, but do not confirm for me.");
        await(()->status(second).equals("SUBMITTED")&&detail(second).path("task").path("contentRevision").asLong()>previousRevision&&detail(second).path("decisions").toString().contains("REQUEST_CHANGES"),Duration.ofMinutes(4));awaitIdle();
        send("I accept the revised report for Task "+second+". Please confirm it using this message and its latest content revision.");
        await(()->status(second).equals("COMPLETED"),Duration.ofMinutes(2));awaitIdle();
        assertEquals(persistentReception,call("sponsor","POST","/agent/reception",Map.of("entryId","entry-live")).path("session").path("id").asText());
        System.out.println("LIVE MODEL E01/E02/E05/E06 PASS: DeepSeek used real tools for two Tasks, separate specialists, missing-deadline question, same-instance continuation, human rework and final confirmation");
    }
    @Test @Order(6) void durablePlaybookWaitsForActualHumanMessageThenCallsAuthorizedTool() throws Exception {
        int before=model.supplierRequests.size();send("E2E_PLAYBOOK");
        await(()->conversation().contains("Approve the supplier lookup?"),Duration.ofSeconds(35));
        assertEquals(before,model.supplierRequests.size());
        assertEquals(1,db.fetchCount(org.jooq.impl.DSL.table("agent_tool_run"),org.jooq.impl.DSL.field("status").eq("WAITING")));
        send("E2E_APPROVE");await(()->conversation().contains("Approved tool run completed"),Duration.ofSeconds(35));
        assertEquals(before+1,model.supplierRequests.size());
        var row=db.fetchOne("select result from agent_tool_run where status='SUCCEEDED'");assertNotNull(row);
        assertTrue(row.get(0).toString().contains("report-supplier-1"));assertTrue(row.get(0).toString().contains(human));
        assertEquals(1,db.fetchCount(org.jooq.impl.DSL.table("agent_tool_run_signal"),org.jooq.impl.DSL.field("delivered_at").isNotNull()));
        System.out.println("E11 PASS: model starts Playbook -> durable human wait -> genuine human source message -> authorized Python tool node -> result wakes same Session");
    }
    @Test @Order(7) void externalContributorAndExistingAgentShareOnlyAuthorizedTaskContent() throws Exception {
        String partner=call("partner","GET","/collaboration/actors/me",null).path("id").asText();
        String task=call("sponsor","POST","/collaboration/tasks",Map.of("title","Shared implementation","goal","Review supplier facts together")).path("id").asText();
        String specialist=report(findTask("Delivery comparison")).path("authorId").asText();
        command(task,"SET_PARTICIPANT",Map.of("actorId",partner,"roles",List.of("contributor")));
        command(task,"SET_PARTICIPANT",Map.of("actorId",specialist,"roles",List.of("contributor")));
        String specialistSession=db.fetchOne("select id from agent_session where participant_id=?",specialist).get(0,String.class);
        command(task,"LINK_SESSION",Map.of("sessionId",specialistSession));
        var privateEntry=command(task,"PUT_CONTEXT",Map.of("kind","NOTE","title","INTERNAL_BUDGET_SECRET","visibleToRoles",List.of("owner"),"body",Map.of("ceiling",9000)));
        command(task,"PUT_CONTEXT",Map.of("kind","ARTIFACT","title","Shared delivery target","body",Map.of("leadDays",14)));
        assertEquals(1,call("partner","GET","/collaboration/tasks/"+task+"/context",null).path("items").size());
        assertEquals(404,raw("partner","GET","/api/collaboration/tasks/"+task+"/context/"+privateEntry.path("objectId").asText()+"/versions",null).statusCode());
        call("sponsor","POST","/agent/messages",Map.of("requestId",UUID.randomUUID().toString(),"recipientId",specialist,"taskId",task,"kind","MESSAGE","body",Map.of("text","E2E_PRIVATE_VIEW")));
        awaitIdle();assertTrue(runtimeHistory(specialistSession,specialist).toString().contains("Shared delivery target"));
        assertFalse(runtimeHistory(specialistSession,specialist).toString().contains("INTERNAL_BUDGET_SECRET"));
        assertEquals(2,call("sponsor","GET","/collaboration/sessions/"+specialistSession+"/tasks",null).size());
        System.out.println("E04/E15 PASS: external business participant and same Agent on related Tasks; content audiences and private Session isolation enforced");
    }
    @Test @Order(8) void stageConfirmationsKeepLongRunningWorkAndReceptionAlive() throws Exception {
        String task=findTask("Delivery constraint");
        for(int stage=1;stage<=2;stage++) {
            send("E2E_STAGE "+task);final int expected=stage;
            await(()->{int count=0;for(var decision:detail(task).path("decisions"))if(decision.path("kind").asText().equals("CONFIRM_STAGE"))count++;return count==expected;},Duration.ofSeconds(25));
            assertNotEquals("COMPLETED",status(task));awaitIdle();
        }
        send("E2E_SCHEDULE");await(()->db.fetchCount(org.jooq.impl.DSL.table("agent_schedule"))>=2,Duration.ofSeconds(25));awaitIdle();
        assertEquals(sessionId,call("sponsor","POST","/agent/reception",Map.of("entryId","entry")).path("session").path("id").asText());
        System.out.println("E16 PASS: two human stage confirmations preserve long-running Task and continuing reception");
    }
    @Test @Order(9) void anotherInstanceReadsActualReportContentByVersionedReferenceAndReviewsIt() throws Exception {
        String task=findTask("Delivery comparison");String report=report(task).path("id").asText();
        String author=report(task).path("authorId").asText();
        send("E2E_REVIEW "+task+" "+report);
        await(()->detail(task).path("context").toString().contains("Independent source review"),Duration.ofSeconds(35));awaitIdle();
        JsonNode review=null;for(var entry:detail(task).path("context"))if(entry.path("title").asText().equals("Independent source review"))review=entry;
        assertNotNull(review);assertNotEquals(author,review.path("authorId").asText());
        assertEquals(14,review.path("body").path("verifiedLeadDays").asInt());
        assertEquals(1,review.path("source").path("entryRevisions").path(report).asInt());
        assertTrue(model.requests.stream().anyMatch(r->r.contains("resource_read")&&r.contains("report-supplier-1")));
        assertEquals("SUBMITTED",status(task));
        assertEquals(detail(task).path("task").path("contentRevision").asLong(),
            db.fetchOne("select content_revision from collaboration_decision where task_id=? and kind='SUBMIT' order by created_at desc limit 1",task).get(0,Long.class));
        System.out.println("E12 PASS: real report body -> Task reference with revision -> independent Agent reads actual content -> publishes source-linked review");
    }
    @Test @Order(10) void progressOnlyModelStopAndDuplicateReceiptConvergeWithoutHumanNudge() throws Exception {
        var progressSeen=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        model.script=t->{
            if(!t.reception()&&t.text().startsWith("E2E_EXECUTE ")&&progressSeen.add(t.messageId()))
                return commentary("E2E_PROGRESS_ONLY: I will start reading and producing the report.");
            // Deliberately echo an already sent result: the runtime/API must suppress it.
            if(t.reception()&&t.trigger.path("kind").asText().equals("RESULT")&&t.used("message_send"))
                return reply("Duplicate acknowledgement that must not be delivered");
            return scenario(t);
        };
        try {
            String task=research("Autonomous continuation");
            var messages=call("sponsor","GET","/agent/reception/"+sessionId,null).path("messages");
            int results=0;
            for(var message:messages) {
                String body=message.path("body").toString();
                assertFalse(body.contains("E2E_PROGRESS_ONLY"));
                assertFalse(body.contains("Duplicate acknowledgement that must not be delivered"));
                if(body.contains("Evidence ready "+task)) results++;
            }
            assertEquals(1,results);
            assertTrue(report(task).path("body").toString().contains("report-supplier-1"));
            assertEquals("SUBMITTED",status(task));
        } finally {model.script=this::scenario;}
    }
    private String research(String title) throws Exception {
        send("E2E_RESEARCH "+title);await(()->findTask(title)!=null&&status(findTask(title)).equals("SUBMITTED"),Duration.ofSeconds(50));
        String task=findTask(title);await(()->call("sponsor","GET","/agent/reception/"+sessionId,null).path("messages").toString().contains("Evidence ready "+task),Duration.ofSeconds(25));awaitIdle();return task;
    }
    private TaskModelFixture.Step scenario(TaskModelFixture.Turn t) {
        if(!t.text().startsWith("E2E_CONFLICT ")) for(var entry:t.toolResults.entrySet()) {
            if(entry.getValue().has("success")&&!entry.getValue().path("success").asBoolean())
                throw new IllegalStateException("Tool "+entry.getKey()+" rejected: "+entry.getValue().path("code").asText()+" "+entry.getValue().path("message").asText());
        }
        if(t.reception()&&(t.text().startsWith("E2E_RESEARCH ")||t.text().startsWith("E2E_CLARIFY "))) {
            boolean clarify=t.text().startsWith("E2E_CLARIFY ");String title=t.text().substring(clarify?12:13);
            if(!t.used("task_list"))return tool("task_list",Map.of("query",title));
            if(!t.used("task_create"))return tool("task_create",Map.of("title",title,"goal","Compare supplier capacity and delivery using external evidence","sourceMessageId",t.messageId()));
            String task=t.result("task_create").path("id").asText();
            if(!t.used("agent_create"))return tool("agent_create",Map.of("templateVersionId","researcher-v1","taskId",task,"instruction",(clarify?"E2E_ASK ":"E2E_EXECUTE ")+title,
                "grants",List.of(Map.of("operation","tool.invoke","scopeKind","RESOURCE","scopeId","supplier-tool","delegable",false))));
            if(!t.used("session_memory"))return tool("session_memory",Map.of("patch",Map.of("latestTaskId",task,"latestWorker",t.result("agent_create").path("participantId").asText())));
            return reply("Research started "+task);
        }
        if(!t.reception()&&t.text().startsWith("E2E_ASK ")) {
            if(!t.used("message_send"))return tool("message_send",Map.of("recipientId",t.sender(),"taskId",t.taskId(),"kind","QUESTION","body",Map.of("text","What is the delivery deadline?")));
            return reply("");
        }
        if(t.reception()&&t.trigger.path("kind").asText().equals("QUESTION")) {
            if(!t.used("message_send"))return tool("message_send",Map.of("recipientId",human,"taskId",t.taskId(),"kind","QUESTION","body",Map.of("text",t.text())));
            return reply("");
        }
        if(t.reception()&&t.text().startsWith("E2E_ANSWER ")) {
            var memory=t.context.path("workMemory");
            if(!t.used("message_send"))return tool("message_send",Map.of("recipientId",memory.path("latestWorker").asText(),"taskId",memory.path("latestTaskId").asText(),"kind","MESSAGE","body",Map.of("text","E2E_EXECUTE Deadline confirmed: "+t.text().substring(11))));
            return reply("Clarification delivered");
        }
        if(t.text().equals("E2E_SCHEDULE")) {
            if(!t.used("schedule_create"))return tool("schedule_create",Map.of("dueAt",Instant.now().plusMillis(150).toString(),"instruction","E2E_TIMER"));
            return reply("Follow-up scheduled");
        }
        if(t.text().equals("E2E_TIMER"))return reply("Scheduled follow-up delivered");
        if(t.text().equals("E2E_PLAYBOOK")) {
            if(!t.used("tool_run_start"))return tool("tool_run_start",Map.of("playbookVersionId","approval-v1","input",Map.of()));
            return reply("Approval flow started");
        }
        if(t.trigger.path("body").has("toolRunId")) {
            if(t.trigger.path("body").path("status").asText().equals("WAITING")) {
                if(!t.used("session_read"))return tool("session_read",Map.of("sessionId",t.context.path("sessionId").asText()));
                if(!t.used("session_memory"))return tool("session_memory",Map.of("patch",Map.of("approvalRunId",t.trigger.path("body").path("toolRunId").asText(),"approvalWaitingId",t.trigger.path("body").path("waitingId").asText())));
                return reply("Approve the supplier lookup?");
            }
            if(t.trigger.path("body").path("status").asText().equals("SUCCEEDED"))return reply("Approved tool run completed");
            throw new IllegalStateException("Playbook failed: "+t.trigger.path("body").path("failureReason").asText());
        }
        if(t.text().equals("E2E_APPROVE")) {
            var memory=t.context.path("workMemory");
            if(!t.used("tool_run_resume"))return tool("tool_run_resume",Map.of("toolRunId",memory.path("approvalRunId").asText(),"waitingId",memory.path("approvalWaitingId").asText(),"sourceMessageId",t.messageId(),"payload",Map.of("approved",true)));
            return reply("Approval recorded");
        }
        if(t.text().startsWith("E2E_CONFLICT ")) {
            String id=t.text().substring(13);
            if(!t.used("task_read")||t.count("task_command")==1&&t.count("task_read")==1)return tool("task_read",Map.of("taskId",id));
            long revision=t.result("task_read").path("detail").path("task").path("revision").asLong();
            if(t.count("task_command")<2)return tool("task_command",Map.of("taskId",id,"expectedRevision",t.count("task_command")==0?revision-1:revision,"kind","PUT_CONTEXT","data",Map.of("kind","NOTE","title","Reconciled revision","body",Map.of("fact","Fresh state read before correction"))));
            return reply("Conflict reconciled");
        }
        if(t.text().equals("E2E_PRIVATE_VIEW")) {
            if(!t.used("task_read"))return tool("task_read",Map.of("taskId",t.taskId()));
            if(t.result("task_read").toString().contains("INTERNAL_BUDGET_SECRET"))throw new IllegalStateException("private Task audience leaked");
            return reply("Shared evidence checked");
        }
        if(t.text().startsWith("E2E_STAGE ")) {
            String id=t.text().substring(10);if(!t.used("task_read"))return tool("task_read",Map.of("taskId",id));var task=t.result("task_read").path("detail").path("task");
            if(!t.used("task_command"))return tool("task_command",Map.of("taskId",id,"expectedRevision",task.path("revision").asLong(),"kind","CONFIRM_STAGE","sourceMessageId",t.messageId(),"data",Map.of("contentRevision",task.path("contentRevision").asLong())));
            return reply("Stage acknowledged; ongoing work remains open");
        }
        if(t.text().startsWith("E2E_REVIEW ")) {
            var parts=t.text().split(" ");
            if(!t.used("agent_create"))return tool("agent_create",Map.of("templateVersionId","researcher-v1","taskId",parts[1],"instruction","E2E_READ_REPORT "+parts[2],"taskRoles",List.of("reviewer")));
            return reply("Independent review assigned");
        }
        if(t.text().startsWith("E2E_READ_REPORT ")) {
            String report=t.text().substring(16);
            if(!t.used("resource_read"))return tool("resource_read",Map.of("reference",Map.of("kind","TASK_CONTENT","taskId",t.taskId(),"entryId",report,"revision",1)));
            if(!t.used("task_read"))return tool("task_read",Map.of("taskId",t.taskId()));
            if(!t.used("task_command"))return tool("task_command",Map.of("taskId",t.taskId(),"expectedRevision",t.result("task_read").path("detail").path("task").path("revision").asLong(),"kind","PUT_CONTEXT","data",Map.of("kind","ARTIFACT","title","Independent source review","body",Map.of("verifiedLeadDays",t.result("resource_read").path("body").path("leadDays").asInt()),"source",Map.of("entryIds",List.of(report)))));
            return reply("Report evidence checked");
        }
        if(!t.reception()&&t.text().startsWith("E2E_EXECUTE ")) {
            if(!t.used("task_read"))return tool("task_read",Map.of("taskId",t.taskId()));
            if(!t.used("supplier_lookup"))return tool("supplier_lookup",Map.of("supplier","Northwind Components"));
            long revision=t.result("task_read").path("detail").path("task").path("revision").asLong();
            if(!t.used("task_command"))return tool("task_command",Map.of("taskId",t.taskId(),"expectedRevision",revision,"kind","PUT_CONTEXT",
                "data",Map.of("kind","ARTIFACT","title","Supplier evidence","body",json.convertValue(t.result("supplier_lookup"),Map.class),"source",Map.of("resourceId","supplier-tool","reportId","report-supplier-1"))));
            if(t.result("task_command").path("revision").asLong()==revision+1)return tool("task_command",Map.of("taskId",t.taskId(),"expectedRevision",revision+1,"kind","SUBMIT","data",Map.of("comment","Evidence collected")));
            return reply("Research finished "+t.taskId());
        }
        if(t.reception()&&t.trigger.path("kind").asText().equals("RESULT")) {
            if(t.text().equals("Report evidence checked")) {
                if(!t.used("task_read"))return tool("task_read",Map.of("taskId",t.taskId()));
                if(!t.used("task_command"))return tool("task_command",Map.of("taskId",t.taskId(),
                    "expectedRevision",t.result("task_read").path("detail").path("task").path("revision").asLong(),
                    "kind","SUBMIT","data",Map.of("comment","Independent review included in current delivery")));
            }
            if(!t.used("message_send"))return tool("message_send",Map.of("recipientId",t.context.path("authorizedContext").path("replyToParticipantId").asText(),"kind","RESULT","taskId",t.taskId(),"body",Map.of("text","Evidence ready "+t.taskId())));
            return reply("");
        }
        if(t.reception()&&t.text().startsWith("E2E_CONFIRM ")) {
            String id=t.text().substring(12);if(!t.used("task_read"))return tool("task_read",Map.of("taskId",id));
            var task=t.result("task_read").path("detail").path("task");
            if(!t.used("task_command"))return tool("task_command",Map.of("taskId",id,"expectedRevision",task.path("revision").asLong(),"kind","CONFIRM","sourceMessageId",t.messageId(),"data",Map.of("contentRevision",task.path("contentRevision").asLong(),"comment","Initiator accepts the evidence")));
            return reply("Confirmed "+id);
        }
        return reply("");
    }
    private void seedCatalog(boolean deepseek) {
        var catalog=api.getBean(CatalogRepository.class);Instant now=Instant.now();
        String modelId=deepseek?"model-live":"model",modelVersion=modelId+"-v1";
        var llm=new LlmModelConfigDto("OPENAI_COMPATIBLE",deepseek?System.getenv().getOrDefault("AGENTYARD_E2E_MODEL_ID","deepseek-chat"):"fixture-model",deepseek?System.getenv().getOrDefault("AGENTYARD_E2E_MODEL_BASE_URL","https://api.deepseek.com/v1"):model.baseUrl(),deepseek?System.getenv().getOrDefault("AGENTYARD_E2E_MODEL_API_KEY_ENV_VAR","DEEPSEEK_API_KEY"):"TASK_E2E_MODEL_KEY",0,4096,false,false,null);
        catalog.upsertResource(new ResourceDto(modelId,"Model",ResourceType.LLM_MODEL,ShareScope.SHARED,"PERSON","sponsor","E2E model","",List.of(),null,null,List.of()));
        catalog.replaceResourceVersions(modelId,List.of(new StoredResourceVersion(modelVersion,modelId,"1.0.0",VersionStatus.PUBLISHED,"","fixture",now,now,new ResourceVersionConfigurationDto(ResourceType.LLM_MODEL,null,llm,null))));
        var toolConfig=json.convertValue(Map.of("operations",List.of(Map.of("name","supplier_lookup","description","Read supplier evidence",
            "inputSchema","{\"type\":\"object\",\"properties\":{\"supplier\":{\"type\":\"string\"}},\"required\":[\"supplier\"],\"additionalProperties\":false}","outputSchema","{\"type\":\"object\"}")),
            "connector",Map.of("connectorType","simple-http","timeoutSeconds",15,"retryPolicy","NONE","config",Map.of("baseUrl",model.serviceUrl()),"operationMappings",Map.of("supplier_lookup",Map.of("method","POST","path","/suppliers/query","requestPlacement","JSON_BODY")))),ToolConfigDto.class);
        catalog.upsertResource(new ResourceDto("supplier-tool","Supplier evidence",ResourceType.TOOL,ShareScope.SHARED,"PERSON","sponsor","Supplier lookup","",List.of(),null,null,List.of()));
        catalog.replaceResourceVersions("supplier-tool",List.of(new StoredResourceVersion("supplier-v1","supplier-tool","1.0.0",VersionStatus.PUBLISHED,"","fixture-tool",now,now,new ResourceVersionConfigurationDto(ResourceType.TOOL,toolConfig,null,null))));
        var nodes=List.of(new PlaybookNodeDto("approval","Human approval",PlaybookNodeType.HUMAN_TASK,"Approve lookup",null,null,null,null,Map.of(),new PlaybookNodeLayoutDto(0,0)),
            new PlaybookNodeDto("lookup","Supplier lookup",PlaybookNodeType.TOOL_TASK,"Fetch authorized facts",null,null,"supplier-tool","supplier_lookup",Map.of("arguments",Map.of("supplier","Northwind Components"),"outputKey","evidence"),new PlaybookNodeLayoutDto(100,0)),
            new PlaybookNodeDto("done","Done",PlaybookNodeType.END,"Result",null,null,null,null,Map.of(),new PlaybookNodeLayoutDto(0,0)));
        var definition=new PlaybookDefinitionDto("Approval before lookup","{\"type\":\"object\"}","{\"type\":\"object\"}",new PlaybookExecutionPolicyDto("PT5M","NONE"),true,false,"approval",nodes,
            List.of(new PlaybookEdgeDto("e1","approval","lookup",null,"",true),new PlaybookEdgeDto("e2","lookup","done",null,"",true)));
        var flow=new PlaybookVersionDto("approval-v1","approval","1.0.0",VersionStatus.PUBLISHED,now,now,definition);
        catalog.upsertPlaybook(new PlaybookDto("approval","Approval flow","Human approval before supplier request","PERSON","sponsor",flow,flow,List.of(flow)));
        catalog.replacePlaybookVersions("approval",List.of(flow));
        for(String role:List.of("reception","researcher")) {
            String roleId=deepseek?role+"-live":role;
            String prompt=role.equals("reception")?"E2E_RECEPTION: Coordinate business work, create Task with human sourceMessageId, delegate research, deliver evidence to human and request confirmation. For specialist results, message_send human and finish with empty response.":"E2E_RESEARCHER: Read delegated Task; call supplier_lookup for Northwind Components; publish facts as Task ARTIFACT, submit and report to creator. Never confirm the human's Task.";
            var config=new RoleConfigurationDto(role,"Supplier research",prompt,modelVersion,null,false,null,100,List.of(),List.of("supplier-v1"),role.equals("reception")?List.of("approval-v1"):List.of());
            var v=new RoleTemplateVersionDto(roleId+"-v1",roleId,"1.0.0",VersionStatus.PUBLISHED,now,now,config);
            catalog.upsertRoleTemplate(new RoleTemplateDto(roleId,role,"E2E role","PERSON","sponsor",v,v,List.of(v)));catalog.replaceRoleTemplateVersions(roleId,List.of(v));
        }
        catalog.upsertEntry(new EntryDto(deepseek?"entry-live":"entry","Research assistant","Continuous reception","PERSON","sponsor",ShareScope.SHARED,deepseek?"reception-live-v1":"reception-v1",true,List.of(deepseek?"researcher-live":"researcher"),List.of(new GrantRequest("task.create",ScopeKind.PLATFORM,"*",true,null),new GrantRequest("tool.invoke",ScopeKind.RESOURCE,"supplier-tool",true,null),new GrantRequest("schedule.create",ScopeKind.SESSION,"*",false,null),new GrantRequest("playbook.run",ScopeKind.RESOURCE,"approval-v1",false,null))));
    }
    private void startPython() throws Exception {
        Files.createDirectories(repository.resolve("apps/api/build/task-e2e"));runtimeLog=repository.resolve("apps/api/build/task-e2e/runtime.log");
        var process=new ProcessBuilder("uv","run","--no-sync","--package","agentyard-agent-runtime","uvicorn","agentyard_agent_runtime.main:app","--host","127.0.0.1","--port",Integer.toString(URI.create(runtimeUrl).getPort()));
        process.directory(repository.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(runtimeLog.toFile()));
        var env=process.environment();env.put("AGENTYARD_INTERNAL_AUTH_TOKEN",token);env.put("AGENTYARD_API_BASE_URL",apiUrl+"/api");env.put("TASK_E2E_MODEL_KEY","fixture-key");
        env.put("AGENTYARD_AGENT_RUNTIME_DATABASE_URL","postgresql+psycopg://postgres:postgres@127.0.0.1:"+database.getPort()+"/runtime_e2e");
        env.put("UV_CACHE_DIR",repository.resolve("apps/api/build/task-e2e/uv-cache").toString());env.put("AGENTYARD_AGENT_RUNTIME_LOG_LEVEL","INFO");python=process.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(System.nanoTime()<deadline) {
            if(!python.isAlive())fail("Runtime exited; inspect apps/api/build/task-e2e/runtime.log");
            try {if(http.send(HttpRequest.newBuilder(URI.create(runtimeUrl+"/healthz")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}catch(Exception ignored) {}
            Thread.sleep(150);
        }
        fail("Runtime readiness failed; inspect apps/api/build/task-e2e/runtime.log");
    }
    private void stopPython() throws Exception {if(python==null)return;python.descendants().forEach(ProcessHandle::destroy);python.destroy();if(!python.waitFor(5,TimeUnit.SECONDS))python.destroyForcibly();python=null;}
    private void send(String text) {call("sponsor","POST","/agent/messages",Map.of("requestId",UUID.randomUUID().toString(),"recipientId",receptionParticipant,"kind","MESSAGE","body",Map.of("text",text)));}
    private String findTask(String title) {for(var t:call("sponsor","GET","/collaboration/tasks",null).path("items"))if(title.equals(t.path("title").asText()))return t.path("id").asText();return null;}
    private JsonNode report(String task) {for(var e:detail(task).path("context"))if(e.path("title").asText().equals("Supplier evidence"))return e;throw new IllegalStateException("supplier artifact missing");}
    private JsonNode detail(String task) {return call("sponsor","GET","/collaboration/tasks/"+task,null);}
    private JsonNode command(String task,String kind,Map<String,Object> data) {return call("sponsor","POST","/collaboration/tasks/"+task+"/commands",Map.of("requestId",UUID.randomUUID().toString(),"expectedRevision",detail(task).path("task").path("revision").asLong(),"kind",kind,"data",data));}
    private String status(String task) {return detail(task).path("task").path("status").asText();}
    private String conversation() {return call("sponsor","GET","/agent/reception/"+sessionId,null).path("messages").toString();}
    private boolean hasQuestion(String task) {for(var m:call("sponsor","GET","/agent/reception/"+sessionId,null).path("messages"))if(m.path("kind").asText().equals("QUESTION")&&m.path("taskId").asText().equals(task)&&m.path("recipientId").asText().equals(human))return true;return false;}
    private void awaitIdle() throws Exception {await(()->db.fetchCount(org.jooq.impl.DSL.table("agent_activation"),org.jooq.impl.DSL.field("status").in("QUEUED","RUNNING"))==0,Duration.ofSeconds(30));}
    private JsonNode runtimeHistory(String session,String participant) throws Exception {var r=request("Bearer "+token,"GET",runtimeUrl+"/sessions/"+session+"/history?participantId="+participant+"&limit=200",null);assertEquals(200,r.statusCode());return json.readTree(r.body());}
    private JsonNode runtimeHistoryComplete(String session,String participant) throws Exception {
        var all=json.createArrayNode();long after=0;
        do {var r=request("Bearer "+token,"GET",runtimeUrl+"/sessions/"+session+"/history?participantId="+participant+"&limit=200&after="+after,null);assertEquals(200,r.statusCode());var page=json.readTree(r.body());
            for(var entry:page.path("entries"))all.add(entry);after=page.path("nextCursor").asLong(0);
        } while(after>0);
        return json.createObjectNode().set("entries",all);
    }
    private void assertRuntimeHistory(String session,String participant,int minimum) throws Exception {var r=runtimeHistory(session,participant);assertTrue(r.path("entries").size()>=minimum,r.toString());}
    private void await(BooleanSupplier predicate,Duration timeout) throws Exception {
        long deadline=System.nanoTime()+timeout.toNanos();while(System.nanoTime()<deadline) {dispatcher.dispatch();if(predicate.getAsBoolean())return;
            assertTrue(TaskE2eServer.HTTP_FAILURES.isEmpty(),TaskE2eServer.HTTP_FAILURES.toString());assertTrue(model.errors.isEmpty(),model.errors.toString());var failed=db.fetch("select id,failure_reason from agent_activation where status='FAILED'");assertTrue(failed.isEmpty(),"Actual Activation failed: "+failed);Thread.sleep(80);}
        fail("Timed out: "+db.fetch("select id,status,failure_reason from agent_activation"));
    }
    private JsonNode call(String user,String method,String path,Object body) {try {var r=raw(user,method,"/api"+path,body);assertTrue(r.statusCode()>=200&&r.statusCode()<300,"HTTP "+r.statusCode()+" "+path+": "+r.body());return json.readTree(r.body()).path("data");}catch(Exception e){throw new IllegalStateException(e);}}
    private HttpResponse<String> raw(String user,String method,String path,Object body) throws Exception {return request("Basic "+Base64.getEncoder().encodeToString((user+":task-e2e").getBytes(StandardCharsets.UTF_8)),method,apiUrl+path,body);}
    private HttpResponse<String> request(String auth,String method,String url,Object body) throws Exception {var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization",auth).header("Content-Type","application/json");return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private static int freePort() throws Exception {try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
}
