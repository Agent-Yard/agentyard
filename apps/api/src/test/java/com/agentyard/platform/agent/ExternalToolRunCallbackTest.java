package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.runtime.RuntimeContracts.*;
import static com.agentyard.persistence.jooq.Tables.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.agentyard.contracts.agent.ToolRunCallbacks.*;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.platform.catalog.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.extension.ExtensionDefinitionService;
import com.agentyard.platform.integration.*;
import com.agentyard.platform.integration.IntegrationDtos.*;
import com.agentyard.platform.knowledge.*;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Real SQL, published definitions, account availability, callback audit and durable signals. */
class ExternalToolRunCallbackTest {
    static EmbeddedPostgresTestDatabase db;
    AgentService agents; ToolRunService runs; ExternalToolRunCallbackService callbacks; InternalToolRunCallbackController controller;
    IntegrationAccountRepository accounts;
    final Source source=new Source("TOOL_CONNECTOR","verification","account");
    @BeforeAll static void open() throws Exception { db=new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void close() throws Exception { db.close(); }
    @BeforeEach void setup() {
        db.reset();var sql=db.dsl();var mapper=new ObjectMapper();
        sql.execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values ('human','HUMAN','human','Human',now()),('agent','AGENT','session','Worker',now())");
        sql.execute("insert into catalog_role_template(id,name,description,owner_type,owner_id) values ('template','Worker','','PERSON','human')");
        sql.execute("insert into catalog_role_template_version(id,template_id,version,status,created_at,configuration) values ('template-v1','template','1','PUBLISHED',now(),'{}')");
        sql.execute("insert into agent_session(id,participant_id,template_version_id,name,status,created_by,reply_to_participant_id,created_at,updated_at) values ('session','agent','template-v1','Worker','ACTIVE','human','human',now(),now())");
        sql.execute("insert into collaboration_message(id,sender_id,recipient_id,kind,body,created_at) values ('input','human','agent','MESSAGE','{}',now())");
        sql.execute("insert into agent_activation(id,session_id,input_message_id,status,created_at,updated_at) values ('activation','session','input','SUCCEEDED',now(),now())");
        var catalog=new CatalogService(new JooqCatalogRepository(sql,mapper),new KnowledgeServiceClient("http://unused.invalid","test"),mock(KnowledgeWorkflowGateway.class));
        var flow=catalog.createPlaybook(new CreatePlaybookRequest("Verification","","PERSON","human",new CreatePlaybookVersionRequest(VersionStatus.PUBLISHED,
            new PlaybookDefinitionDto("","{}","{}",null,true,true,"verify",List.of(
                new PlaybookNodeDto("verify","Verify",PlaybookNodeType.EXTERNAL_INTERACTION,"Await provider result",null,null,null,null,
                    Map.of("externalSource",Map.of("subjectType","TOOL_CONNECTOR","descriptorId","verification","accountId","account")),new PlaybookNodeLayoutDto(0,0)),
                new PlaybookNodeDto("end","End",PlaybookNodeType.END,"",null,null,null,null,Map.of(),new PlaybookNodeLayoutDto(100,0))),
                List.of(new PlaybookEdgeDto("next","verify","end",null,"",true))))));
        var authorization=new AgentAuthorization(catalog);
        agents=new AgentService(sql,mapper,authorization,catalog,mock(CollaborationService.class));
        runs=new ToolRunService(agents,new CapabilityAccess(authorization));
        accounts=new IntegrationAccountRepository(sql,mapper);
        account("account","verification",IntegrationAccountStatus.ENABLED);
        var accountService=new IntegrationAccountService(accounts,new IntegrationCredentialCrypto(mapper,"fixture-encryption-key"),
            new ExtensionDefinitionService(null,null,"test-token"),(baseUrl,path,request,traceId,requestId)->{throw new AssertionError("no credentials should be accessed");});
        callbacks=new ExternalToolRunCallbackService(agents,runs,accountService);
        controller=new InternalToolRunCallbackController(new InternalRuntimeAuth("test-token"),callbacks);
        var now=Instant.now();
        agents.store().toolRun(new ToolRun("run","session","agent","activation",null,flow.effectiveVersion().id(),ToolRunStatus.WAITING,Map.of(),Map.of(),
            Map.of("waitingId","run:verify:1","nodeKey","verify","source","EXTERNAL_SYSTEM","type","EXTERNAL_INTERACTION"),null,now,now));
    }

    @Test void persistsOneSignalAndAuditableSourceAcrossDuplicateDeliveryAndCompletion() {
        var request=callback("event-1",Map.of("verified",true));
        var first=controller.callback("Bearer test-token","run",request);
        assertEquals(first,controller.callback("Bearer test-token","run",request));
        assertEquals(1,db.dsl().fetchCount(AGENT_TOOL_RUN_SIGNAL));
        assertEquals(PlaybookResumeSource.EXTERNAL_SYSTEM,first.source());
        var audit=agents.store().message(first.sourceMessageId()).orElseThrow();
        assertEquals("event-1",audit.body().get("externalEventId"));
        assertEquals("account",((Map<?,?>)audit.body().get("externalSource")).get("accountId"));
        assertEquals("agent",audit.senderId());
        assertEquals(1,db.dsl().fetchCount(AGENT_ACTIVATION));
        runs.acknowledge("run",first.signalId());
        assertNotNull(db.dsl().selectFrom(AGENT_TOOL_RUN_SIGNAL).fetchOne().getDeliveredAt());
        runs.progress("run",new ToolRunProgress(ToolRunStatus.SUCCEEDED,Map.of("verified",true),Map.of(),null));
        assertEquals(first,controller.callback("Bearer test-token","run",request));
        assertEquals(1,db.dsl().fetchCount(AGENT_TOOL_RUN_SIGNAL));
    }

    @Test void rejectsStaleWaitWrongSourceUnauthorizedAccountAndCancelledWork() {
        assertThrows(ResponseStatusException.class,()->controller.callback(null,"run",callback("event",Map.of())));
        assertThrows(IllegalArgumentException.class,()->callbacks.receive("run",new Callback("stale","event",source,Map.of())));
        account("other-account","verification",IntegrationAccountStatus.ENABLED);
        assertThrows(ResponseStatusException.class,()->callbacks.receive("run",new Callback("run:verify:1","event",new Source("TOOL_CONNECTOR","verification","other-account"),Map.of())));
        assertThrows(com.agentyard.platform.shared.ApiProblemException.class,()->callbacks.receive("run",new Callback("run:verify:1","event",new Source("TOOL_CONNECTOR","other-system","account"),Map.of())));
        account("account","verification",IntegrationAccountStatus.DISABLED);
        assertThrows(com.agentyard.platform.shared.ApiProblemException.class,()->callbacks.receive("run",callback("event",Map.of())));
        account("account","verification",IntegrationAccountStatus.ENABLED);
        db.dsl().execute("update agent_tool_run set status='CANCELLED' where id='run'");
        assertThrows(ResponseStatusException.class,()->callbacks.receive("run",callback("event",Map.of())));
        assertEquals(0,db.dsl().fetchCount(AGENT_TOOL_RUN_SIGNAL));
        assertEquals(1,db.dsl().fetchCount(COLLABORATION_MESSAGE));
    }

    @Test void rejectsConflictingRepeatAndAgentHumanResumeCannotForgeExternalCallback() {
        var accepted=callbacks.receive("run",callback("event",Map.of("result","one")));
        assertThrows(IllegalArgumentException.class,()->callbacks.receive("run",callback("event",Map.of("result","two"))));
        assertThrows(IllegalArgumentException.class,()->callbacks.receive("run",callback("other-event",Map.of("result","one"))));
        assertThrows(ResponseStatusException.class,()->runs.resume("agent","human","input","run","run:verify:1",Map.of()));
        assertEquals(1,db.dsl().fetchCount(AGENT_TOOL_RUN_SIGNAL));
        assertEquals(2,db.dsl().fetchCount(COLLABORATION_MESSAGE));
        assertEquals(accepted.signalId(),db.dsl().selectFrom(AGENT_TOOL_RUN_SIGNAL).fetchOne().getId());
        db.dsl().execute("update agent_tool_run set status='CANCELLED' where id='run'");
        assertThrows(ResponseStatusException.class,()->callbacks.receive("run",callback("event",Map.of("result","one"))));
    }

    @Test void buffersRealExternalResultWhileSessionPausedAndRejectsExternalResultForHumanWait() {
        db.dsl().execute("update agent_session set status='PAUSED' where id='session'");
        assertNotNull(callbacks.receive("run",callback("event",Map.of("verified",true))).signalId());
        db.dsl().deleteFrom(AGENT_TOOL_RUN_SIGNAL).execute();
        db.dsl().execute("update agent_tool_run set waiting=jsonb_set(waiting,'{source}','\"HUMAN\"') where id='run'");
        assertThrows(ResponseStatusException.class,()->callbacks.receive("run",callback("event",Map.of())));
    }

    @Test void revokedExecutorCannotReceiveFreshExternalPayloadBeforeControlReconciliation() {
        var now=Instant.now();
        agents.store().tasks().saveTask(new com.agentyard.contracts.task.TaskContracts.Task("work",null,"Work","Goal",
            com.agentyard.contracts.task.TaskContracts.TaskStatus.ACTIVE,"",1,1,Map.of(),
            new com.agentyard.contracts.task.TaskContracts.Policy(com.agentyard.contracts.task.TaskContracts.defaultRoles(),List.of()),
            "human","human","human",null,now,now));
        agents.store().tasks().participant("work","agent",List.of("observer"));
        db.dsl().execute("update agent_tool_run set task_id='work' where id='run'");
        assertThrows(ResponseStatusException.class,()->callbacks.receive("run",callback("event",Map.of("privatePayload","result"))));
        assertEquals(0,db.dsl().fetchCount(AGENT_TOOL_RUN_SIGNAL));
        assertEquals(1,db.dsl().fetchCount(COLLABORATION_MESSAGE));
    }

    private Callback callback(String event,Map<String,Object> payload) {return new Callback("run:verify:1",event,source,payload);}
    private void account(String id,String descriptor,IntegrationAccountStatus status) {
        accounts.saveAccount(new StoredIntegrationAccount(id,IntegrationAccountSubjectType.TOOL_CONNECTOR,descriptor,id,status,
            Map.of(),"vault://test-reference",null,null,IntegrationAccountCredentialStatus.ACTIVE,Map.of(),Instant.now(),Instant.now()));
    }
}
