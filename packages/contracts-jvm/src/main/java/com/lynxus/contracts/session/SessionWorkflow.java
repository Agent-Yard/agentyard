package com.lynxus.contracts.session;

import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookProgressUpdate;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface SessionWorkflow {
    @WorkflowMethod
    SessionSnapshot run(SessionStartRequest request);

    @UpdateMethod
    SessionUserMessageUpdateResult submitUserMessage(UserMessage message);

    @SignalMethod
    void humanResume(HumanResumeSignal signal);

    @SignalMethod
    void externalCallback(ExternalCallbackSignal signal);

    @SignalMethod
    void endHumanHandoff();

    @SignalMethod
    void syncPlaybookProgress(PlaybookProgressUpdate update);

    @SignalMethod
    void humanOperatorReply(HumanOperatorReplySignal signal);

    @QueryMethod
    SessionSnapshot currentSnapshot();

}
