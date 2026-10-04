package com.agentyard.platform.task;

import com.agentyard.contracts.task.TaskContracts.ExecutionStart;
import com.agentyard.contracts.task.TaskExecutionWorkflow;
import io.temporal.client.*;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The database is a durable outbox; dispatch retries survive API/Temporal restarts. */
@Component
public class TaskExecutionDispatcher {
    private static final Logger log=LoggerFactory.getLogger(TaskExecutionDispatcher.class);
    private final CollaborationService service; private final WorkflowClient client; private final String queue;
    public TaskExecutionDispatcher(CollaborationService service,WorkflowClient client,@Value("${agentyard.temporal.task-queue}") String queue) { this.service=service;this.client=client;this.queue=queue; }
    @Scheduled(fixedDelayString="${agentyard.tasks.dispatch-delay-ms:2000}")
    public void dispatch() {
        for(String id:service.store.pendingExecutions()) attempt(id,()-> {
            var workflow=client.newWorkflowStub(TaskExecutionWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId(workflowId(id)).setTaskQueue(queue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
            try { WorkflowClient.start(workflow::run,new ExecutionStart(id)); } catch(WorkflowExecutionAlreadyStarted ignored) { }
            service.store.dispatched(id,true);
        });
        for(String id:service.store.cancelledExecutions()) attempt(id,()-> {
            try { client.newWorkflowStub(TaskExecutionWorkflow.class,workflowId(id)).cancel(); } catch(WorkflowNotFoundException ignored) { }
            service.store.dispatched(id,false);
        });
        for(var pending:service.store.pendingSignals()) attempt(pending.executionId(),()-> {
            try { client.newWorkflowStub(TaskExecutionWorkflow.class,workflowId(pending.executionId())).resume(pending.signal()); }
            catch(WorkflowNotFoundException ignored) {
                if(!TaskCommandService.executionTerminal(service.store.execution(pending.executionId()).orElseThrow().status())) throw ignored;
            }
            service.store.signalDelivered(pending.signal().signalId());
        });
    }
    public static String workflowId(String id) { return "task-execution:"+id; }
    private void attempt(String id,Runnable action) { try { action.run(); } catch(RuntimeException error) { log.warn("Task execution dispatch will retry: {} ({})",id,error.getClass().getSimpleName()); } }
}
