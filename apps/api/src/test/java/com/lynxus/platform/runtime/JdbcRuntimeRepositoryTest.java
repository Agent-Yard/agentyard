package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventSource;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionType;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRuntimeRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcRuntimeRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
            .cleanDisabled(false)
            .dataSource(dataSource)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcRuntimeRepository(jdbcTemplate, mapper());
    }

    @Test
    void shouldUpsertWorkflowMessagesByWorkflowAndMessageKey() {
        ProjectionPlanDto firstPlan = projectionPlan(
            List.of(assistantTextMessage("msg-1", "turn-1", "第一版回复")),
            List.of(),
            List.of()
        );
        ProjectionPlanDto secondPlan = projectionPlan(
            List.of(assistantTextMessage("msg-1", "turn-1", "第二版回复")),
            List.of(),
            List.of()
        );

        repository.persistProjection(firstPlan);
        repository.persistProjection(secondPlan);

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from conversation_message where workflow_instance_id = ? and message_key = ?",
            Integer.class,
            "wf-1",
            "turn-1"
        );
        String content = jdbcTemplate.queryForObject(
            "select content from conversation_message where workflow_instance_id = ? and message_key = ?",
            String.class,
            "wf-1",
            "turn-1"
        );

        assertEquals(1, count);
        assertEquals("第二版回复", content);
    }

    @Test
    void shouldUpsertExternalInteractionProjectionByWorkflowAndSourceMessageKey() {
        ProjectionPlanDto plan = projectionPlan(
            List.of(externalInteractionConversationMessage("msg-ext", "turn-ext")),
            List.of(externalInteractionTask("interaction-1", "turn-ext", "msg-ext")),
            List.of(externalInteractionEvent("event-1", "interaction-1", "create:wf-1:turn-ext"))
        );

        repository.persistProjection(plan);
        repository.persistProjection(plan);

        Integer taskCount = jdbcTemplate.queryForObject(
            "select count(*) from external_interaction_task where workflow_instance_id = ? and source_message_key = ?",
            Integer.class,
            "wf-1",
            "turn-ext"
        );
        Integer eventCount = jdbcTemplate.queryForObject(
            "select count(*) from external_interaction_event where interaction_task_id = ? and dedupe_key = ?",
            Integer.class,
            "interaction-1",
            "create:wf-1:turn-ext"
        );

        assertEquals(1, taskCount);
        assertEquals(1, eventCount);
    }

    @Test
    void shouldRollbackEntireProjectionWhenInteractionTaskInsertFails() {
        ProjectionPlanDto invalidPlan = projectionPlan(
            List.of(assistantTextMessage("msg-1", "turn-1", "回复内容")),
            List.of(new ExternalInteractionTaskDto(
                "interaction-1",
                ExternalInteractionType.OAUTH_REDIRECT,
                ExternalInteractionStatus.AWAITING_USER_ACTION,
                "session-1",
                "task-1",
                "wf-1",
                null,
                "msg-1",
                "完成授权",
                "请完成授权后继续。",
                "oauth-demo",
                "provider-ref-1",
                "https://example.com/oauth",
                "token-1",
                "/console/runtime",
                null,
                null,
                ExternalInteractionEventSource.SYSTEM_CREATE,
                null,
                FIXED_TIME,
                FIXED_TIME,
                List.of()
            )),
            List.of()
        );

        assertThrows(DataAccessException.class, () -> repository.persistProjection(invalidPlan));

        assertEquals(0, count("task_instance"));
        assertEquals(0, count("workflow_instance"));
        assertEquals(0, count("conversation_session"));
        assertEquals(0, count("conversation_message"));
        assertEquals(0, count("external_interaction_task"));
    }

    private static final Instant FIXED_TIME = Instant.parse("2026-04-02T00:00:00Z");

    private ProjectionPlanDto projectionPlan(
        List<ConversationMessageDto> messages,
        List<ExternalInteractionTaskDto> interactionTasks,
        List<ExternalInteractionEventDto> interactionEvents
    ) {
        return new ProjectionPlanDto(
            baseTask(),
            baseWorkflow(messageKeys(messages)),
            baseSession(),
            messages,
            interactionTasks,
            interactionEvents,
            null
        );
    }

    private TaskInstanceDto baseTask() {
        return new TaskInstanceDto(
            "task-1",
            "scenario-1",
            "assistant-1",
            "助手A",
            "release-v1",
            "用户问题",
            "customer-1",
            TaskStatus.WAITING_RESUME,
            FIXED_TIME,
            "wf-1"
        );
    }

    private WorkflowInstanceDto baseWorkflow(List<String> emittedMessageKeys) {
        return new WorkflowInstanceDto(
            "wf-1",
            "task-1",
            "assistant-1",
            "助手A",
            "release-v1",
            FIXED_TIME,
            FIXED_TIME,
            WorkflowStatus.WAITING_RESUME,
            "流程摘要",
            "agent-node",
            true,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            emittedMessageKeys,
            List.of(),
            SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private ConversationSessionDto baseSession() {
        return new ConversationSessionDto(
            "session-1",
            "scenario-1",
            "测试会话",
            "customer-1",
            "assistant-1",
            "助手A",
            "release-v1",
            FIXED_TIME,
            FIXED_TIME,
            List.of(),
            "task-1",
            "wf-1",
            null,
            null,
            null,
            List.of(),
            SharedSessionState.empty()
        );
    }

    private static ConversationMessageDto assistantTextMessage(String id, String messageKey, String text) {
        return new ConversationMessageDto(
            id,
            messageKey,
            "session-1",
            "ASSISTANT",
            "ASSISTANT",
            "assistant-1",
            "助手A",
            ConversationPayloadType.TEXT,
            Map.of("text", text),
            text,
            FIXED_TIME,
            "task-1",
            "wf-1"
        );
    }

    private static ConversationMessageDto externalInteractionConversationMessage(String id, String messageKey) {
        Map<String, Object> spec = Map.ofEntries(
            Map.entry("interactionType", "OAUTH_REDIRECT"),
            Map.entry("title", "完成授权"),
            Map.entry("instruction", "请完成授权后继续。"),
            Map.entry("provider", "oauth-demo"),
            Map.entry("providerReference", "provider-ref-1"),
            Map.entry("launchUrl", "https://example.com/oauth"),
            Map.entry("returnPath", "/console/runtime"),
            Map.entry("expiresAt", ""),
            Map.entry("primaryActionLabel", "去授权"),
            Map.entry("secondaryActions", List.of()),
            Map.entry("displayHints", Map.of())
        );
        Map<String, Object> projection = Map.of(
            "interactionTaskId", "interaction-1",
            "status", "AWAITING_USER_ACTION",
            "primaryAction", "",
            "secondaryActions", List.of(),
            "displayHints", Map.of()
        );
        return new ConversationMessageDto(
            id,
            messageKey,
            "session-1",
            "ASSISTANT",
            "ASSISTANT",
            "assistant-1",
            "助手A",
            ConversationPayloadType.EXTERNAL_INTERACTION,
            Map.of("spec", spec, "projection", projection),
            "完成授权 [AWAITING_USER_ACTION]",
            FIXED_TIME,
            "task-1",
            "wf-1"
        );
    }

    private static ExternalInteractionTaskDto externalInteractionTask(String id, String sourceMessageKey, String messageId) {
        return new ExternalInteractionTaskDto(
            id,
            ExternalInteractionType.OAUTH_REDIRECT,
            ExternalInteractionStatus.AWAITING_USER_ACTION,
            "session-1",
            "task-1",
            "wf-1",
            sourceMessageKey,
            messageId,
            "完成授权",
            "请完成授权后继续。",
            "oauth-demo",
            "provider-ref-1",
            "https://example.com/oauth",
            "token-1",
            "/console/runtime",
            null,
            null,
            ExternalInteractionEventSource.SYSTEM_CREATE,
            null,
            FIXED_TIME,
            FIXED_TIME,
            List.of()
        );
    }

    private static ExternalInteractionEventDto externalInteractionEvent(String id, String interactionTaskId, String dedupeKey) {
        return new ExternalInteractionEventDto(
            id,
            interactionTaskId,
            ExternalInteractionEventType.CREATED,
            ExternalInteractionEventSource.SYSTEM_CREATE,
            dedupeKey,
            Map.of("status", "AWAITING_USER_ACTION"),
            null,
            FIXED_TIME
        );
    }

    private static List<String> messageKeys(List<ConversationMessageDto> messages) {
        return messages.stream()
            .map(ConversationMessageDto::messageKey)
            .filter(key -> key != null && !key.isBlank())
            .toList();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
            .findAndAddModules()
            .build();
    }
}
