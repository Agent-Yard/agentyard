package com.agentyard.contracts.session;

import com.agentyard.contracts.session.SessionContracts.PlaybookResumeSignal;
import com.agentyard.contracts.session.SessionContracts.PlaybookRun;
import com.agentyard.contracts.session.SessionContracts.PlaybookStartRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PlaybookWorkflow {
    @WorkflowMethod
    PlaybookRun run(PlaybookStartRequest request);

    @SignalMethod
    void resume(PlaybookResumeSignal signal);

    @QueryMethod
    PlaybookRun currentRun();
}
