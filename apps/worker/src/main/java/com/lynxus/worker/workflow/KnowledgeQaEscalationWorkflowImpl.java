package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.NodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

public class KnowledgeQaEscalationWorkflowImpl implements KnowledgeQaEscalationWorkflow {
    private final KnowledgeQaActivities activities = Workflow.newActivityStub(
        KnowledgeQaActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
    );

    @Override
    public WorkflowResult run(WorkflowStartRequest request) {
        List<String> contexts = activities.retrieveKnowledge(request.scenarioId(), request.question());
        String answer = activities.generateAnswer(request.question(), contexts);
        boolean escalationRequired = activities.shouldEscalate(request.question(), answer);

        return new WorkflowResult(
            request.workflowInstanceId(),
            escalationRequired ? WorkflowStatus.WAITING_HUMAN : WorkflowStatus.COMPLETED,
            answer,
            List.of(
                new NodeSnapshot("question-received", "问题接收", NodeStatus.COMPLETED, request.question(), now()),
                new NodeSnapshot("knowledge-retrieval", "知识检索", NodeStatus.COMPLETED, String.join("\n", contexts), now()),
                new NodeSnapshot("answer-generation", "回答生成", NodeStatus.COMPLETED, answer, now()),
                new NodeSnapshot("escalation-decision", "升级判定",
                    escalationRequired ? NodeStatus.WAITING_HUMAN : NodeStatus.COMPLETED,
                    escalationRequired ? "等待人工接管" : "流程结束",
                    now())
            ),
            escalationRequired
        );
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
}
