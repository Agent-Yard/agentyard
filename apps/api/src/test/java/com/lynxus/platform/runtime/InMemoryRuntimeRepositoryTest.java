package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeTaskSnapshot;
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
            TaskStatus.WAITING_RESUME,
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
            WorkflowStatus.WAITING_RESUME,
            "等待人工",
            "human-review",
            true,
            new ExecutionCheckpoint("cp-1", "resume", "human-review", "{\"step\":1}", null, 1),
            new ResumeTaskSnapshot("human-review", "人工待办", "请审核", "填写备注", WorkflowContracts.PauseSource.GRAPH_NODE, List.of(WorkflowContracts.ResumeActionType.CONTINUE)),
            new PauseReasonSnapshot("GRAPH_HUMAN_NODE", "需要人工审核", WorkflowContracts.PauseSource.GRAPH_NODE),
            null,
            new ToolOutcomeSummary("call-1", "resource-tool", "工单工具", WorkflowContracts.ToolKind.RESOURCE, "create_ticket", "MCP", "resource-tool", "工单工具", Map.of("ticketId", "T-1")),
            List.of("tool@v1"),
            List.of(new NodeExecutionDto("node-1", "wf-1", "human-review", "人工审核", NodeStatus.WAITING_RESUME, "等待人工", now)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("skill-v1"),
            new SharedSessionState(Map.of("fact", "value"), Map.of(), Map.of()),
            AgentTurnState.empty()
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
                new ConversationMessageDto("msg-1", null, "session-1", "USER", "USER", "user", "tester", ConversationPayloadType.TEXT, Map.of("text", "你好"), "你好", now, null, null),
                new ConversationMessageDto("msg-2", "assistant-final-reply", "session-1", "ASSISTANT", "ASSISTANT", "assistant-1", "助手A", ConversationPayloadType.TEXT, Map.of("text", "已进入人工流程"), "已进入人工流程", now, "task-1", "wf-1")
            ),
            "task-1",
            "wf-1",
            workflow.latestToolOutcome(),
            workflow.resumeTask(),
            workflow.pauseReason(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState()
        );
        ResumeInterventionDto intervention = new ResumeInterventionDto(
            "human-1",
            "wf-1",
            "CONTINUE",
            "HUMAN",
            "user-1",
            "已处理",
            Map.of("ticketId", "T-1"),
            ResumeInterventionStatus.APPLIED,
            now,
            now,
            null
        );

        repository.persistProjection(new RuntimeDtos.ProjectionPlanDto(task, workflow, session, session.messages(), List.of(), List.of(), intervention));

        ConversationSessionDto storedSession = repository.findSession("session-1").orElseThrow();
        WorkflowInstanceDto storedWorkflow = repository.findWorkflow("wf-1").orElseThrow();

        assertEquals(2, storedSession.messages().size());
        assertEquals("已进入人工流程", storedSession.messages().getLast().content());
        assertEquals(ConversationPayloadType.TEXT, storedSession.messages().getLast().payloadType());
        assertEquals("session-1", repository.findTaskSessionId("task-1").orElseThrow());
        assertEquals(1, storedWorkflow.resumeInterventions().size());
        assertEquals("CONTINUE", storedWorkflow.resumeInterventions().getFirst().type());
        assertEquals("HUMAN", storedWorkflow.resumeInterventions().getFirst().source());
        assertEquals(ResumeInterventionStatus.APPLIED, storedWorkflow.resumeInterventions().getFirst().status());
        assertNotNull(storedWorkflow.checkpoint());
        assertEquals("value", storedWorkflow.sharedState().facts().get("fact"));
    }
}
