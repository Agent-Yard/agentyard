package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.task.TaskAccess.*;
import static com.agentyard.platform.task.CollaborationService.*;
import static com.agentyard.platform.task.TaskCommandService.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionService {
    private final CollaborationService collaboration;
    private final TaskCommandService commands;
    public TaskExecutionService(CollaborationService collaboration, TaskCommandService commands) { this.collaboration=collaboration;this.commands=commands; }
    public ExecutionInput input(String id) {
        return collaboration.store.transaction(s -> {
            var execution=s.execution(id).orElseThrow(TaskAccess::missing);
            var task=collaboration.task(s,execution.taskId(),execution.actorId(),true);
            if(terminal(task.status()) || executionTerminal(execution.status())) conflict("execution is closed");
            require(permissions(task.policy().roles(),roles(s.participants(task.id()),execution.actorId())),Permission.EXECUTE);
            var detail=collaboration.detail(s,task,execution.actorId());
            stringList(execution.input().get("contextEntryIds")).forEach(entryId->commands.entry(s,task,execution.actorId(),entryId));
            var context=((List<?>)execution.input().getOrDefault("contextEntries",List.of())).stream()
                .map(value -> collaboration.mapper.convertValue(value,ContextEntry.class)).toList();
            // No other executions, session identities, or unselected context are passed to the model.
            var scoped=new TaskDetail(task,detail.participants(),context,detail.relations(),List.of(),List.of(),List.of(),detail.permissions());
            return new ExecutionInput(execution,s.runtime(id),scoped);
        });
    }
    public Execution progress(String id, ExecutionProgress progress) {
        return collaboration.store.transaction(s -> {
            var before=s.execution(id).orElseThrow(TaskAccess::missing); var task=s.task(before.taskId(),true).orElseThrow(TaskAccess::missing);
            var execution=s.execution(id).orElseThrow();
            if(executionTerminal(execution.status())) return execution;
            if(execution.status()==progress.status() && execution.result().equals(object(progress.result()))
                && execution.waiting().equals(object(progress.waiting())) && Objects.equals(execution.failureReason(),progress.failureReason())) return execution;
            if(progress.status()==null || progress.status()==ExecutionStatus.QUEUED) bad("invalid execution progress");
            if(progress.status()==ExecutionStatus.WAITING && (progress.waiting()==null || !(progress.waiting().get("id") instanceof String))) bad("waiting requires a correlation id");
            if(progress.status()==ExecutionStatus.SUCCEEDED) {
                require(permissions(task.policy().roles(),roles(s.participants(task.id()),execution.actorId())),Permission.EXECUTE);
                if(terminal(task.status())) conflict("task is closed");
            }
            s.progress(id,new ExecutionProgress(progress.status(),object(progress.result()),object(progress.waiting()),progress.failureReason()));
            Draft draft=new Draft(task); draft.material=true; s.saveTask(draft.build());
            // Visibility is enforced on the referenced execution read.
            s.event(new Event(task.id(),task.revision()+1,execution.actorId(),"EXECUTION_"+progress.status(),Map.of("objectId",id),Set.of(),Instant.now()));
            return s.execution(id).orElseThrow();
        });
    }
    public CommandResult command(String executionId, Command request) {
        ExecutionInput input=input(executionId);
        if(!Set.of(CommandKind.PUT_CONTEXT,CommandKind.SET_CONTEXT_STATE,CommandKind.CREATE_CHILD).contains(request.kind())) bad("execution capability allows context contributions, decisions and child creation only");
        Map<String,Object> data=new LinkedHashMap<>(object(request.data()));
        if(request.kind()==CommandKind.PUT_CONTEXT) {
            var source=new LinkedHashMap<>(map(data.get("source"))); source.put("executionId",executionId); data.put("source",source);
        }
        return commands.command(input.execution().taskId(),input.execution().actorId(),new Command(request.requestId(),request.expectedRevision(),request.kind(),data),executionId);
    }
}
