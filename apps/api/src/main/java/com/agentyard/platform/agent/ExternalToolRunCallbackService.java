package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.platform.agent.AgentAuthorization.*;
import static com.agentyard.platform.agent.AgentService.*;

import com.agentyard.contracts.agent.ToolRunCallbacks.*;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookResumeSource;
import com.agentyard.contracts.task.TaskContracts.Permission;
import com.agentyard.platform.integration.IntegrationAccountService;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted adapters verify provider authentication before invoking this boundary. */
@Service
public class ExternalToolRunCallbackService {
    private final AgentService agents;
    private final ToolRunService runs;
    private final IntegrationAccountService accounts;
    public ExternalToolRunCallbackService(AgentService agents,ToolRunService runs,IntegrationAccountService accounts) {
        this.agents=agents;this.runs=runs;this.accounts=accounts;
    }

    @Transactional
    public ToolRunResume receive(String runId,Callback callback) {
        required(callback.waitingId(),"waitingId");required(callback.eventId(),"eventId");
        var source=callback.source();
        if(source==null) throw new IllegalArgumentException("source is required");
        var type=IntegrationAccountSubjectType.valueOf(required(source.subjectType(),"source.subjectType"));
        required(source.descriptorId(),"source.descriptorId");required(source.accountId(),"source.accountId");
        accounts.requireRuntimeAccountSnapshot(source.accountId(),type,source.descriptorId());
        return agents.store().transaction(s->{
            var run=s.toolRun(runId,true).orElseThrow(()->notFound("ToolRun"));
            if(run.status()==ToolRunStatus.CANCELLED
                ||ExecutionControl.resolve(s,agents.authorization(),run.sessionId(),run.taskId(),Permission.EXECUTE)==ExecutionControl.CLOSED)
                throw denied("the waiting work is closed or no longer authorized");
            var t=com.agentyard.persistence.jooq.Tables.AGENT_TOOL_RUN_SIGNAL;
            var existing=s.database().selectFrom(t).where(t.TOOL_RUN_ID.eq(runId).and(t.WAITING_ID.eq(callback.waitingId()))).fetchOne();
            if(existing!=null) {
                var audit=s.message(existing.getSourceMessageId()).orElseThrow(()->denied("callback source audit is missing"));
                if(!"EXTERNAL_SYSTEM".equals(existing.getSource())||!Objects.equals(audit.body().get("externalEventId"),callback.eventId())
                    ||!Objects.equals(audit.body().get("externalSource"),agents.mapper().convertValue(source,Map.class)))
                    throw new IllegalArgumentException("waiting point already received another external event");
                return runs.persistSignal(s,runId,callback.waitingId(),run.participantId(),audit.id(),PlaybookResumeSource.EXTERNAL_SYSTEM,callback.payload());
            }
            if(run.status()!=ToolRunStatus.WAITING||!callback.waitingId().equals(run.waiting().get("waitingId")))
                throw new IllegalArgumentException("this waiting point is no longer active");
            if(!"EXTERNAL_SYSTEM".equals(run.waiting().get("source"))) throw denied("this waiting point requires another response source");
            var definition=agents.catalog().resolvePlaybookVersion(run.playbookVersionId());
            var node=definition.nodes().stream().filter(n->n.nodeKey().equals(run.waiting().get("nodeKey")))
                .findFirst().orElseThrow(()->denied("waiting node is not part of this playbook version"));
            if(node.nodeType()!=PlaybookNodeType.EXTERNAL_INTERACTION||!(node.config().get("externalSource") instanceof Map<?,?> expected))
                throw denied("external waiting point has no configured callback source");
            if(!Objects.equals(expected.get("subjectType"),source.subjectType())||!Objects.equals(expected.get("descriptorId"),source.descriptorId())
                ||!Objects.equals(expected.get("accountId"),source.accountId())) throw denied("callback source does not match the waiting point binding");
            // This Message is an audit record, not a claimed human decision or an extra Agent activation.
            var audit=new Message(id(),run.participantId(),run.participantId(),run.taskId(),null,MessageKind.EVENT,
                Map.of("text","A verified external callback was received.","toolRunId",runId,"waitingId",callback.waitingId(),
                    "externalEventId",callback.eventId(),"externalSource",source,"payload",callback.payload()),Instant.now());
            s.message(audit);
            return runs.persistSignal(s,runId,callback.waitingId(),run.participantId(),audit.id(),PlaybookResumeSource.EXTERNAL_SYSTEM,callback.payload());
        });
    }
}
