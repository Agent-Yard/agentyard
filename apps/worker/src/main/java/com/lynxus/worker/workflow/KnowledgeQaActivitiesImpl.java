package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeQaActivitiesImpl implements KnowledgeQaActivities {
    private final AgentRuntimeGateway agentRuntimeGateway;

    public KnowledgeQaActivitiesImpl(AgentRuntimeGateway agentRuntimeGateway) {
        this.agentRuntimeGateway = agentRuntimeGateway;
    }

    @Override
    public WorkflowResult startExecution(WorkflowStartRequest request) {
        return agentRuntimeGateway.start(request);
    }

    @Override
    public WorkflowResult resumeExecution(WorkflowResumeRequest request) {
        return agentRuntimeGateway.resume(request);
    }
}
