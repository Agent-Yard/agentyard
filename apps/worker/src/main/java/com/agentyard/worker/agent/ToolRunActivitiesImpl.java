package com.agentyard.worker.agent;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.worker.runtime.ExecutionControlPlane;
import com.agentyard.worker.runtime.ExecutionPaused;
import com.agentyard.worker.runtime.WorkerHttpClient.WorkerHttpException;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Component;

@Component
public class ToolRunActivitiesImpl implements ToolRunActivities {
    private final ExecutionControlPlane controlPlane;

    public ToolRunActivitiesImpl(ExecutionControlPlane controlPlane) {
        this.controlPlane = controlPlane;
    }

    public ToolRunRuntime loadToolRun(String id) {
        try { return controlPlane.toolRun(id); }
        catch(RuntimeException failure) {
            ExecutionPaused.rethrowIfPaused(failure);
            if(failure instanceof WorkerHttpException http && http.statusCode()>=400 && http.statusCode()<500)
                throw ApplicationFailure.newNonRetryableFailure("Tool invocation is unavailable or no longer authorized","TOOL_RUN_UNAVAILABLE");
            throw failure;
        }
    }
    public void recordToolRunProgress(String id, ToolRunProgress progress) { controlPlane.progress(id, progress); }
    public void acknowledgeToolRunSignal(String id, String signalId) { controlPlane.acknowledgeSignal(id, signalId); }
}
