package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.platform.adapters.ResourceAdapters.MockKnowledgeBaseProvider;
import com.lynxus.platform.adapters.ResourceAdapters.MockSkillExecutor;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import org.junit.jupiter.api.Test;

class RuntimeServiceTest {
    private final RuntimeService service = new RuntimeService(new MockKnowledgeBaseProvider(), new MockSkillExecutor());

    @Test
    void shouldRouteComplaintToHumanIntervention() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-knowledge-escalation", "客户投诉，需要人工处理", "tester")
        );

        assertEquals(TaskStatus.WAITING_HUMAN, task.status());
    }
}
