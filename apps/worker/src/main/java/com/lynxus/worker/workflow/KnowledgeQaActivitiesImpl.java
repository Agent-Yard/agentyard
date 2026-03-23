package com.lynxus.worker.workflow;

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
    public WorkflowResult executeAgentRuntime(WorkflowStartRequest request) {
        return agentRuntimeGateway.run(request);
    }
}
