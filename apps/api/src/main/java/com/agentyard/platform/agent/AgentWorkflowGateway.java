package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;

import com.agentyard.contracts.agent.ActivationWorkflow;
import com.agentyard.contracts.agent.ToolRunWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

/** Transport boundary; the caller keeps database locks until each durable dispatch acknowledgement. */
interface AgentWorkflowGateway {
    void startActivation(String id);
    void cancelActivation(String id);
    void startToolRun(String id);
    void cancelToolRun(String id);
    void pauseToolRun(String id,boolean paused);
    void resumeToolRun(String id,ToolRunResume resume);

    final class Temporal implements AgentWorkflowGateway {
        private final WorkflowClient client;
        private final String queue;
        Temporal(WorkflowClient client,String queue) {this.client=client;this.queue=queue;}
        private WorkflowOptions options(String id) {
            return WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(queue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build();
        }
        public void startActivation(String id) {
            var workflow=client.newWorkflowStub(ActivationWorkflow.class,options(AgentDispatcher.activationWorkflowId(id)));
            WorkflowClient.start(workflow::run,new ActivationStart(id));
        }
        public void cancelActivation(String id) {client.newUntypedWorkflowStub(AgentDispatcher.activationWorkflowId(id)).cancel();}
        public void startToolRun(String id) {
            var workflow=client.newWorkflowStub(ToolRunWorkflow.class,options(AgentDispatcher.toolRunWorkflowId(id)));
            WorkflowClient.start(workflow::run,new ToolRunStart(id));
        }
        public void cancelToolRun(String id) {client.newWorkflowStub(ToolRunWorkflow.class,AgentDispatcher.toolRunWorkflowId(id)).cancel();}
        public void pauseToolRun(String id,boolean paused) {client.newWorkflowStub(ToolRunWorkflow.class,AgentDispatcher.toolRunWorkflowId(id)).setPaused(paused);}
        public void resumeToolRun(String id,ToolRunResume resume) {client.newWorkflowStub(ToolRunWorkflow.class,AgentDispatcher.toolRunWorkflowId(id)).resume(resume);}
    }
}
