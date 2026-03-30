package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.PauseReasonSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRuntimeRepositoryTest {
    private final InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();

    @Test
    void shouldRoundTripRuntimeProjectionAggregates() {
        Instant now = Instant.now();
        TaskInstanceDto task = new TaskInstanceDto(
            "task-1",
            "scenario-1",
            "assistant-1",
            "助手A",
            "release-v1",
            "用户问题",
            "tester",
            TaskStatus.WAITING_HUMAN,
            now,
            "wf-1"
        );
        WorkflowInstanceDto workflow = new WorkflowInstanceDto(
            "wf-1",
            "task-1",
            "assistant-1",
            "助手A",
            "release-v1",
            now,
            now,
            WorkflowStatus.WAITING_HUMAN,
            "等待人工",
            null,
            "human-review",
            true,
            new ExecutionCheckpoint("cp-1", "resume", "human-review", "{\"step\":1}", 1),
            new HumanTaskSnapshot("human-review", "人工待办", "请审核", "填写备注", "GRAPH_NODE", List.of("CONFIRM")),
            new PauseReasonSnapshot("GRAPH_HUMAN_NODE", "需要人工审核", "GRAPH_NODE"),
            new ToolOutcomeSummary("resource-tool", "工单工具", "create_ticket", "MCP", Map.of("ticketId", "T-1")),
            List.of("tool@v1"),
            List.of(new NodeExecutionDto("node-1", "wf-1", "human-review", "人工审核", NodeStatus.WAITING_HUMAN, "等待人工", now)),
            List.of(),
            List.of(),
            List.of("skill-v1"),
            new SharedSessionState(Map.of("fact", "value"), Map.of(), Map.of())
        );
        ConversationSessionDto session = new ConversationSessionDto(
            "session-1",
            "scenario-1",
            "测试会话",
            "tester",
            "assistant-1",
            "助手A",
            "release-v1",
            now,
            now,
            List.of(
                new ConversationMessageDto("msg-1", "session-1", "USER", "USER", "user", "tester", "你好", now, null, null),
                new ConversationMessageDto("msg-2", "session-1", "ASSISTANT", "ASSISTANT", "assistant-1", "助手A", "已进入人工流程", now, "task-1", "wf-1")
            ),
            "task-1",
            "wf-1",
            workflow.latestToolOutcome(),
            workflow.humanTask(),
            workflow.pauseReason(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState()
        );
        HumanInterventionDto intervention = new HumanInterventionDto(
            "human-1",
            "wf-1",
            "CONFIRM",
            "operator-1",
            "已处理",
            Map.of("ticketId", "T-1"),
            HumanInterventionStatus.APPLIED,
            now,
            now,
            null
        );

        repository.persistProjection(task, workflow, session, intervention);

        ConversationSessionDto storedSession = repository.findSession("session-1").orElseThrow();
        WorkflowInstanceDto storedWorkflow = repository.findWorkflow("wf-1").orElseThrow();

        assertEquals(2, storedSession.messages().size());
        assertEquals("已进入人工流程", storedSession.messages().getLast().content());
        assertEquals("session-1", repository.findTaskSessionId("task-1").orElseThrow());
        assertEquals(1, storedWorkflow.interventions().size());
        assertEquals("CONFIRM", storedWorkflow.interventions().getFirst().action());
        assertEquals(HumanInterventionStatus.APPLIED, storedWorkflow.interventions().getFirst().status());
        assertNotNull(storedWorkflow.checkpoint());
        assertEquals("value", storedWorkflow.sharedState().facts().get("fact"));
    }
}
