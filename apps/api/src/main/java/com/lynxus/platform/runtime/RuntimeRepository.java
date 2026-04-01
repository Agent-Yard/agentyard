package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import java.util.List;
import java.util.Optional;

public interface RuntimeRepository {
    List<TaskInstanceDto> listTasks();

    Optional<TaskInstanceDto> findTask(String taskId);

    Optional<String> findTaskSessionId(String taskId);

    Optional<TaskInstanceDto> findTaskByWorkflowInstanceId(String workflowInstanceId);

    List<WorkflowInstanceDto> listWorkflows();

    List<WorkflowInstanceDto> listActiveWorkflows();

    Optional<WorkflowInstanceDto> findWorkflow(String workflowId);

    List<ConversationSessionDto> listSessions();

    Optional<ConversationSessionDto> findSession(String sessionId);

    void saveSession(ConversationSessionDto session);

    Optional<ResumeInterventionDto> findPendingResumeIntervention(String workflowInstanceId);

    List<ResumeInterventionDto> listPendingResumeInterventions();

    void persistProjection(TaskInstanceDto task, WorkflowInstanceDto workflow, ConversationSessionDto session, ResumeInterventionDto intervention);

    void saveResumeIntervention(ResumeInterventionDto intervention);
}
