package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.catalog.CatalogRepository;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.worker.task.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.DateTimeFeature;

/** Real transport and execution integration. No Docker, external identity provider or paid model required. */
@Tag("task-e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskCollaborationE2eTest {
    final ObjectMapper json = new ObjectMapper().rebuild().findAndAddModules()
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String token = UUID.randomUUID().toString();
    EmbeddedPostgresTestDatabase database;
    ServletWebServerApplicationContext api;
    TestWorkflowEnvironment temporal;
    TaskExecutionDispatcher dispatcher;
    TaskModelFixture model;
    Process python;
    String apiUrl, runtimeUrl;
    Path runtimeLog;
    Map<String, String> actors;

    @BeforeAll void start() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
        database.reset();
        api = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TaskE2eServer.class)
            .initializers(context -> context.getBeanFactory().registerSingleton("taskDatabase", database.dsl()))
            .properties("spring.config.name=task-e2e", "server.address=127.0.0.1", "server.port=0",
                "spring.main.banner-mode=off", "agentyard.internal-auth.token=" + token).run();
        apiUrl = "http://127.0.0.1:" + api.getWebServer().getPort();
        model = new TaskModelFixture(json);
        seedPublishedRelease();
        startPython();
        temporal = TestWorkflowEnvironment.newInstance();
        var worker = temporal.newWorker("task-e2e");
        worker.registerWorkflowImplementationTypes(TaskExecutionWorkflowImpl.class);
        worker.registerActivitiesImplementations(new TaskExecutionActivitiesImpl(json, apiUrl + "/api", runtimeUrl, token));
        temporal.start();
        dispatcher = new TaskExecutionDispatcher(api.getBean(CollaborationService.class), temporal.getWorkflowClient(), "task-e2e");
        actors = new HashMap<>();
        for (String user : List.of("sponsor", "partner", "reviewer", "observer")) {
            actors.put(user, publicCall(user, "GET", "/actors/me", null).path("id").asText());
        }
        for (String agent : List.of("researcher", "analyst")) {
            actors.put(agent, publicCall("sponsor", "POST", "/actors/agents", Map.of("agentId", agent)).path("id").asText());
        }
    }

    @AfterAll void stop() throws Exception {
        if (temporal != null) temporal.close();
        if (python != null) {
            python.descendants().forEach(ProcessHandle::destroy);
            python.destroy();
            if (!python.waitFor(5, TimeUnit.SECONDS)) python.destroyForcibly();
        }
        if (model != null) model.close();
        if (api != null) api.close();
        if (database != null) database.close();
    }

    @Test void supplierResearchCombinesExternalEvidenceTwoAgentsHumanDecisionAndIndependentReview() throws Exception {
        String organization = publicCall("sponsor", "POST", "/organizations",
            Map.of("name", "Procurement", "description", "Internal team and invited external experts"))
            .path("organization").path("id").asText();
        String task = createTask("Supplier selection", organization, 1);
        participate(task, "partner", "contributor");
        participate(task, "reviewer", "reviewer");
        participate(task, "observer", "observer");
        participate(task, "researcher", "contributor");
        participate(task, "analyst", "contributor");

        String secret = context("sponsor", task, "Confidential negotiation ceiling", List.of("owner"));
        String evidence = context("partner", task, "Supplier evidence and delivery references", List.of());
        assertEquals(1, publicCall("partner", "GET", "/tasks/" + task + "/context", null).path("items").size());
        assertEquals(404, raw("partner", "GET", "/api/collaboration/tasks/" + task + "/context/" + secret + "/versions", null).statusCode());
        assertEquals(403, commandStatus("observer", task, "PUT_CONTEXT", Map.of("kind", "NOTE", "title", "Unauthorized edit")));
        assertEquals(403, commandStatus("partner", task, "SET_PARTICIPANT", Map.of("actorId", actors.get("partner"), "roles", List.of("owner"))));

        String first = execute(task, "researcher", "NEEDS_APPROVAL: verify the partner evidence", List.of(evidence), null);
        Execution waiting = awaitExecution(task, first, ExecutionStatus.WAITING);
        assertEquals(409, commandStatus("sponsor", task, "RESUME_EXECUTION", Map.of("executionId", first, "waitingId", "stale", "payload", Map.of())));
        assertEquals(403, commandStatus("partner", task, "RESUME_EXECUTION", Map.of("executionId", first, "waitingId", waiting.waiting().get("id"), "payload", Map.of())));
        assertEquals(409, commandStatus("sponsor", task, "TRANSITION", Map.of("status", "IN_REVIEW")));
        command("sponsor", task, "RESUME_EXECUTION", Map.of("executionId", first,
            "waitingId", waiting.waiting().get("id"), "payload", Map.of("decision", "APPROVAL_GRANTED")));
        awaitExecution(task, first, ExecutionStatus.SUCCEEDED);

        var completedResearch = detail("sponsor", task);
        String recommendation = completedResearch.context().stream().filter(e -> e.title().equals("Validated recommendation"))
            .findFirst().orElseThrow().id();
        String second = execute(task, "analyst", "Independently assess the recommendation", List.of(recommendation), null);
        awaitExecution(task, second, ExecutionStatus.SUCCEEDED);
        var assessment = detail("sponsor", task);
        assertEquals(3, assessment.context().stream().filter(e -> e.kind() == ContextKind.ARTIFACT).count());
        assertTrue(assessment.context().stream().filter(e -> e.kind() == ContextKind.ARTIFACT)
            .allMatch(e -> e.source().containsKey("executionId") && e.source().containsKey("entryRevisions")));
        assertNotEquals(TaskStatus.COMPLETED, assessment.task().status());
        assertTrue(model.requests.stream().noneMatch(request -> request.contains("Confidential negotiation ceiling")));

        command("sponsor", task, "TRANSITION", Map.of("status", "IN_REVIEW"));
        assertEquals(409, commandStatus("sponsor", task, "TRANSITION", Map.of("status", "COMPLETED")));
        command("reviewer", task, "REVIEW", Map.of("approved", true, "contentRevision", detail("reviewer", task).task().contentRevision(), "comment", "Evidence and recommendation checked"));
        command("sponsor", task, "TRANSITION", Map.of("status", "COMPLETED"));
        assertEquals(TaskStatus.COMPLETED, detail("partner", task).task().status());
        assertEquals(0, publicCall("partner", "GET", "/organizations", null).size());
        System.out.println("PASS supplier research: external evidence -> Agent contribution -> human decision -> second Agent -> independent review -> completion");
    }

    @Test void deliveryPlanWaitsForItsChildAndPlaybookApprovalAndSurvivesDispatcherRecreation() throws Exception {
        String parent = createTask("Deliver approved integration", null, 0);
        String child = command("sponsor", parent, "CREATE_CHILD", Map.of("title", "Validate rollout", "goal", "Approve staged delivery")).path("objectId").asText();
        participate(child, "researcher", "contributor");
        assertEquals(409, commandStatus("sponsor", parent, "TRANSITION", Map.of("status", "COMPLETED")));
        String execution = execute(child, "researcher", "Approve release", List.of(), "approval");
        Execution waiting = awaitExecution(child, execution, ExecutionStatus.WAITING);
        command("sponsor", child, "RESUME_EXECUTION", Map.of("executionId", execution, "waitingId", waiting.waiting().get("id"), "payload", Map.of("routeKey", "approved", "ticket", "CHANGE-42")));
        dispatcher = new TaskExecutionDispatcher(api.getBean(CollaborationService.class), temporal.getWorkflowClient(), "task-e2e");
        Execution success = awaitExecution(child, execution, ExecutionStatus.SUCCEEDED);
        assertEquals(Map.of("approved", true), success.result());
        command("sponsor", child, "TRANSITION", Map.of("status", "COMPLETED"));
        command("sponsor", parent, "TRANSITION", Map.of("status", "COMPLETED"));
        assertEquals(TaskStatus.COMPLETED, detail("sponsor", parent).task().status());
        System.out.println("PASS delivery: child dependency -> Playbook wait -> durable response dispatch -> approved result -> parent completion");
    }

    @Test void twoSessionsShareTaskEvidenceWithoutSharingPrivateContextOrGrantingMembership() throws Exception {
        String task = createTask("Customer implementation", null, 0);
        participate(task, "partner", "contributor");
        participate(task, "researcher", "owner");
        context("sponsor", task, "Internal contract terms", List.of("owner"));
        String shared = context("partner", task, "Agreed implementation scope", List.of());
        command("sponsor", task, "TRANSITION", Map.of("status", "COMPLETED"));
        long acceptedContent = detail("sponsor", task).task().contentRevision();
        for (String session : List.of("implementation-web", "implementation-followup")) {
            seedSession(session, "partner");
            command("partner", task, "LINK_SESSION", Map.of("sessionId", session));
            var visible = internal("GET", "/api/internal/task-sessions/" + session + "/tasks/" + task + "/context?agentId=researcher", null);
            assertEquals(1, visible.path("items").size());
            assertEquals(shared, visible.path("items").get(0).path("id").asText());
            assertEquals(task, publicCall("partner", "GET", "/sessions/" + session + "/tasks", null).get(0).path("id").asText());
            database.dsl().execute("update session_runtime_session set status = 'ENDED' where id = ?", session);
        }
        assertEquals(TaskStatus.COMPLETED, detail("partner", task).task().status());
        assertEquals(acceptedContent, detail("partner", task).task().contentRevision());
        assertEquals(404, commandStatus("sponsor", task, "LINK_SESSION", Map.of("sessionId", "implementation-web")));
        command("sponsor", task, "TRANSITION", Map.of("status", "ACTIVE"));
        command("sponsor", task, "REMOVE_PARTICIPANT", Map.of("actorId", actors.get("researcher")));
        assertEquals(0, internal("GET", "/api/internal/task-sessions/implementation-followup?agentId=researcher", null).size());
        assertEquals(401, request("Bearer invalid", "GET", apiUrl + "/api/internal/task-sessions/implementation-web?agentId=researcher", null).statusCode());
        System.out.println("PASS continuity: two sessions -> same durable evidence -> intersected human/Agent access -> immediate revocation");
    }

    @Test void incidentExecutionCanBeCancelledAndNeverAcceptLateContributionsOrSuccess() throws Exception {
        String task = createTask("Investigate service incident", null, 0);
        participate(task, "analyst", "contributor");
        String execution = execute(task, "analyst", "NEEDS_APPROVAL: verify incident mitigation", List.of(), null);
        awaitExecution(task, execution, ExecutionStatus.WAITING);
        command("sponsor", task, "CANCEL_EXECUTION", Map.of("executionId", execution));
        awaitExecution(task, execution, ExecutionStatus.CANCELLED);
        assertEquals(409, request("Bearer " + token, "POST", apiUrl + "/api/internal/task-executions/" + execution + "/commands",
            Map.of("requestId", "late", "expectedRevision", detail("sponsor", task).task().revision(), "kind", "PUT_CONTEXT", "data", Map.of("kind", "NOTE", "title", "Late write"))).statusCode());
        internal("POST", "/api/internal/task-executions/" + execution + "/progress", Map.of("status", "SUCCEEDED", "result", Map.of("late", true), "waiting", Map.of()));
        assertEquals(ExecutionStatus.CANCELLED, detail("sponsor", task).executions().getFirst().status());
        assertTrue(detail("sponsor", task).context().stream().noneMatch(e -> e.title().equals("Late write")));
        System.out.println("PASS incident: Agent requests decision -> cancellation -> late contribution rejected -> late success ignored");
    }

    @Test void agentCanDecomposeWorkAndExplicitlyHandAChildToItsSponsor() throws Exception {
        String task = createTask("Explore an implementation plan", null, 0);
        participate(task, "researcher", "contributor");
        String execution = execute(task, "researcher", "DECOMPOSE: propose a follow-up experiment for the sponsor", List.of(), null);
        awaitExecution(task, execution, ExecutionStatus.SUCCEEDED);
        var relation = detail("sponsor", task).relations().getFirst();
        assertEquals(RelationKind.CHILD, relation.kind());
        var child = detail("sponsor", relation.relatedTaskId());
        assertTrue(child.permissions().contains(Permission.MANAGE));
        assertTrue(child.participants().stream().anyMatch(p -> p.actorId().equals(actors.get("researcher"))));
        context("sponsor", child.task().id(), "Experiment verified by sponsor", List.of());
        command("sponsor", child.task().id(), "TRANSITION", Map.of("status", "COMPLETED"));
        command("sponsor", task, "TRANSITION", Map.of("status", "COMPLETED"));
        System.out.println("PASS decomposition: Agent creates child -> explicit sponsor ownership -> human contribution -> parent completion");
    }

    @RepeatedTest(3) void concurrentAgentsReconcileConflictingRevisionsWithoutOverwritingContributions() throws Exception {
        String task = createTask("Parallel evidence assessment", null, 0);
        participate(task, "researcher", "contributor");
        participate(task, "analyst", "contributor");
        String first = execute(task, "researcher", "STALE_ONCE: assess delivery evidence", List.of(), null);
        String second = execute(task, "analyst", "STALE_ONCE: assess financial evidence", List.of(), null);
        awaitExecution(task, first, ExecutionStatus.SUCCEEDED);
        awaitExecution(task, second, ExecutionStatus.SUCCEEDED);
        var artifacts = detail("sponsor", task).context();
        assertEquals(2, artifacts.size());
        assertEquals(Set.of(actors.get("researcher"), actors.get("analyst")),
            artifacts.stream().map(ContextEntry::authorId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(model.requests.stream().anyMatch(request -> request.contains("REVISION_CONFLICT")));
        System.out.println("PASS concurrency: two live Agent executions -> rejected stale writes -> read/reconcile -> two distinct durable contributions");
    }

    private void startPython() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        runtimeUrl = "http://127.0.0.1:" + port;
        Path root = Path.of(System.getProperty("agentyard.repository"));
        runtimeLog = root.resolve("apps/api/build/reports/task-e2e/python-runtime.log");
        Files.createDirectories(runtimeLog.getParent());
        var process = new ProcessBuilder("uv", "run", "--offline", "--package", "agentyard-agent-runtime", "uvicorn",
            "agentyard_agent_runtime.main:app", "--host", "127.0.0.1", "--port", Integer.toString(port), "--lifespan", "off");
        process.directory(root.toFile()).redirectErrorStream(true).redirectOutput(runtimeLog.toFile());
        process.environment().put("UV_CACHE_DIR", root.resolve("apps/api/build/task-e2e/uv-cache").toString());
        process.environment().put("AGENTYARD_API_BASE_URL", apiUrl + "/api");
        process.environment().put("AGENTYARD_INTERNAL_AUTH_TOKEN", token);
        process.environment().put("TASK_E2E_MODEL_KEY", "test-only");
        python = process.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline && python.isAlive()) {
            // Task turns use no session transcript or Redis; this fixture deliberately skips that lifespan.
            try { if (request(null, "GET", runtimeUrl + "/openapi.json", null).statusCode() == 200) return; }
            catch (Exception ignored) { }
            Thread.sleep(100);
        }
        fail("Python runtime failed to start: " + Files.readString(runtimeLog));
    }

    private void seedPublishedRelease() {
        var catalog = api.getBean(CatalogRepository.class);
        var agents = new ArrayList<AssistantReleaseAgentDto>();
        for (String name : List.of("researcher", "analyst")) {
            Map<String, Object> fields = Map.of("agentId", name, "name", name, "role", "investigator", "responsibility", "Assess evidence",
                "executionPolicy", Map.of("modelResourceId", "model", "systemPrompt", "Use evidence and persist your findings", "memoryWindowSize", 10),
                "allowedActions", List.of("REPLY", "SESSION_HUMAN_HANDOFF"), "switchableOwnerAgentIds", List.of(),
                "playbookIds", List.of("approval"), "skillResourceVersionIds", List.of(), "toolResourceVersionIds", List.of());
            agents.add(json.convertValue(fields, AssistantReleaseAgentDto.class));
            catalog.upsertAgent(json.convertValue(Map.of("id", name, "assistantId", "assistant", "name", name, "role", "investigator",
                "executionPolicy", fields.get("executionPolicy"), "allowedActions", fields.get("allowedActions"),
                "switchableOwnerAgentIds", List.of(), "playbookIds", List.of("approval")), AgentDto.class));
        }
        var release = new LinkedHashMap<String, Object>();
        release.put("id", "published-release"); release.put("assistantId", "assistant"); release.put("releaseVersion", "1");
        release.put("status", "PUBLISHED"); release.put("createdAt", Instant.now().toString()); release.put("publishedAt", Instant.now().toString());
        release.put("agents", agents);
        release.put("resources", List.of(Map.of("resourceId", "model", "resourceName", "Deterministic model", "resourceType", "LLM_MODEL",
            "resourceVersionId", "model-v1", "resourceVersion", "1", "boundAgents", List.of("researcher", "analyst"),
            "configuration", Map.of("llmModel", Map.of("providerType", "OPENAI_COMPATIBLE", "modelId", "task-e2e", "baseUrl", model.baseUrl(),
                "apiKeyEnvVar", "TASK_E2E_MODEL_KEY", "privateDeployment", true)))));
        release.put("playbooks", List.of(Map.of("id", "approval", "name", "Deployment approval", "description", "Human gate",
            "inputSchema", "{\"type\":\"object\"}", "resultSchema", "{\"type\":\"object\",\"required\":[\"approved\"]}",
            "allowHumanTask", true, "entryNodeKey", "approve", "nodes", List.of(
                Map.of("nodeKey", "approve", "nodeName", "Approve delivery", "nodeType", "HUMAN_TASK", "config", Map.of(), "layout", Map.of("x", 0, "y", 0)),
                Map.of("nodeKey", "finish", "nodeName", "Approved", "nodeType", "END", "config", Map.of("result", Map.of("approved", true)), "layout", Map.of("x", 1, "y", 0))),
            "edges", List.of(Map.of("edgeKey", "approved", "sourceNodeKey", "approve", "targetNodeKey", "finish", "routeKey", "approved", "defaultEdge", false)))));
        catalog.replaceAssistantReleases("assistant", List.of(json.convertValue(release, AssistantReleaseDto.class)));
    }

    private void seedSession(String id, String customer) {
        database.dsl().execute("""
            insert into session_runtime_session (id, scenario_id, title, customer_id, assistant_id, assistant_name,
              assistant_release_version, status, primary_agent_id, current_owner_agent_id, agent_turn_active,
              session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, created_at, updated_at, entry_scope)
            values (?, 'scenario', 'Follow-up', ?, 'assistant', 'Assistant', '1', 'ACTIVE', 'researcher', 'researcher',
              false, false, false, false, '{}', now(), now(), 'WEB')
            """, id, customer);
    }

    private String createTask(String title, String organization, int approvals) throws Exception {
        var request = new CreateTask(organization, title, "Reach a verified decision with a traceable result", Map.of(),
            new Policy(defaultRoles(), approvals, false, List.of("Evidence is traceable", "Required reviews are complete")));
        return publicCall("sponsor", "POST", "/tasks", request).path("id").asText();
    }
    private void participate(String task, String actor, String role) throws Exception {
        command("sponsor", task, "SET_PARTICIPANT", Map.of("actorId", actors.get(actor), "roles", List.of(role)));
    }
    private String context(String user, String task, String title, List<String> audience) throws Exception {
        return command(user, task, "PUT_CONTEXT", Map.of("kind", "FACT", "title", title, "body", Map.of("text", title), "visibleToRoles", audience)).path("objectId").asText();
    }
    private String execute(String task, String agent, String instruction, List<String> entries, String playbook) throws Exception {
        var data = new LinkedHashMap<String, Object>(Map.of("actorId", actors.get(agent), "releaseId", "published-release", "instruction", instruction, "contextEntryIds", entries));
        if (playbook != null) data.put("playbookId", playbook);
        return command("sponsor", task, "START_EXECUTION", data).path("objectId").asText();
    }
    private TaskDetail detail(String user, String task) throws Exception {
        return json.treeToValue(publicCall(user, "GET", "/tasks/" + task, null), TaskDetail.class);
    }
    private JsonNode command(String user, String task, String kind, Map<String, Object> data) throws Exception {
        return publicCall(user, "POST", "/tasks/" + task + "/commands",
            new Command(UUID.randomUUID().toString(), detail(user, task).task().revision(), CommandKind.valueOf(kind), data));
    }
    private int commandStatus(String user, String task, String kind, Map<String, Object> data) throws Exception {
        return raw(user, "POST", "/api/collaboration/tasks/" + task + "/commands",
            new Command(UUID.randomUUID().toString(), detail(user, task).task().revision(), CommandKind.valueOf(kind), data)).statusCode();
    }
    private Execution awaitExecution(String task, String id, ExecutionStatus status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Execution execution = null;
        while (System.nanoTime() < deadline) {
            dispatcher.dispatch();
            execution = detail("sponsor", task).executions().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            if (execution.status() == status) {
                if (Set.of(ExecutionStatus.SUCCEEDED, ExecutionStatus.CANCELLED).contains(status)) {
                    temporal.getWorkflowClient().newUntypedWorkflowStub(TaskExecutionDispatcher.workflowId(id)).getResult(10, TimeUnit.SECONDS, Void.class);
                }
                return execution;
            }
            if (execution.status() == ExecutionStatus.FAILED) break;
            Thread.sleep(75);
        }
        fail("Expected " + status + " but got " + execution + "\nRuntime log: " + Files.readString(runtimeLog));
        return null;
    }
    private JsonNode publicCall(String user, String method, String path, Object body) throws Exception {
        var response = raw(user, method, "/api/collaboration" + path, body);
        assertEquals(200, response.statusCode(), method + " " + path + ": " + response.body());
        return json.readTree(response.body()).path("data");
    }
    private JsonNode internal(String method, String path, Object body) throws Exception {
        var response = request("Bearer " + token, method, apiUrl + path, body);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private HttpResponse<String> raw(String user, String method, String path, Object body) throws Exception {
        return request("Basic " + Base64.getEncoder().encodeToString((user + ":task-e2e").getBytes(StandardCharsets.UTF_8)), method, apiUrl + path, body);
    }
    private HttpResponse<String> request(String auth, String method, String url, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (auth != null) request.header("Authorization", auth);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
