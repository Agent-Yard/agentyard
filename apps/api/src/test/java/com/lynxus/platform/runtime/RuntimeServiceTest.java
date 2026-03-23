package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import org.junit.jupiter.api.Test;

class RuntimeServiceTest {
    private final CatalogService catalogService = new CatalogService();
    private final RuntimeService service = new RuntimeService(
        request -> {
            boolean waitingHuman = request.question().contains("投诉");
            return new WorkflowContracts.WorkflowResult(
                request.workflowInstanceId(),
                waitingHuman ? WorkflowContracts.WorkflowStatus.WAITING_HUMAN : WorkflowContracts.WorkflowStatus.COMPLETED,
                waitingHuman ? "已创建人工协同工单，等待人工处理。" : "问题已自动处理完成。",
                java.util.List.of(
                    new WorkflowContracts.NodeSnapshot("question-received", "问题接收", WorkflowContracts.NodeStatus.COMPLETED, request.question(), java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("knowledge-retrieval", "知识检索", WorkflowContracts.NodeStatus.COMPLETED, "命中 FAQ", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("answer-generation", "回答生成", WorkflowContracts.NodeStatus.COMPLETED, "已生成答案", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("mcp-ticketing", "MCP 协同调用", WorkflowContracts.NodeStatus.COMPLETED, "创建协同工单", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("escalation-decision", "升级判定", waitingHuman ? WorkflowContracts.NodeStatus.WAITING_HUMAN : WorkflowContracts.NodeStatus.COMPLETED, waitingHuman ? "等待人工接管" : "流程结束", java.time.Instant.now())
                ),
                waitingHuman,
                new WorkflowContracts.McpInvocationSummary(
                    "创建协同工单",
                    "TICKET-10001",
                    waitingHuman ? "ACCEPTED" : "RECORDED",
                    waitingHuman ? "HUMAN_HANDOFF" : "AUTO_CLOSE",
                    waitingHuman ? "需要人工介入" : "无需人工介入"
                )
            );
        },
        catalogService
    );

    @Test
    void shouldRouteComplaintToHumanIntervention() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-knowledge-escalation", "客户投诉，需要人工处理", "tester")
        );

        assertEquals(TaskStatus.WAITING_HUMAN, task.status());
        assertFalse(task.assistantReleaseVersion().isBlank());
        assertNotNull(service.getWorkflow(task.workflowInstanceId()).mcpSummary());
    }

    @Test
    void shouldCompleteSimpleFaqAndExposeMcpSummary() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-knowledge-escalation", "怎么重置密码", "tester")
        );

        assertEquals(TaskStatus.COMPLETED, task.status());
        assertFalse(service.getWorkflow(task.workflowInstanceId()).resourceAnchors().isEmpty());
        assertNotNull(service.getWorkflow(task.workflowInstanceId()).mcpSummary());
    }
}
