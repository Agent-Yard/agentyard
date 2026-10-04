package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.task.TaskAccess.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Session reads require the intersection of its human customer's and current Agent's task scopes. */
@Service
public class TaskSessionBridge {
    private final CollaborationService collaboration;
    public TaskSessionBridge(CollaborationService collaboration) { this.collaboration=collaboration; }
    public List<Task> tasks(String sessionId,String agentId) {
        var scope=scope(sessionId,agentId);
        return collaboration.store.sessionTasks(sessionId,scope.human()).stream()
            .filter(id->collaboration.canView(collaboration.store,id,scope.human())&&collaboration.canView(collaboration.store,id,scope.agent()))
            .map(id->collaboration.store.task(id,false).orElseThrow()).toList();
    }
    public Page<ContextEntry> context(String sessionId,String agentId,String taskId,String cursor) {
        var scope=scope(sessionId,agentId);
        return collaboration.store.transaction(s->{
            if(!s.sessionTasks(sessionId,scope.human()).contains(taskId)) throw missing();
            collaboration.task(s,taskId,scope.human(),false); collaboration.task(s,taskId,scope.agent(),false);
            var roles=roles(s.participants(taskId),scope.human());
            var entries=s.context(taskId,scope.agent(),roles(s.participants(taskId),scope.agent()),cursor,101);
            return new Page<>(entries.stream().limit(100).filter(entry->visible(entry,scope.human(),roles)).toList(),entries.size()>100?entries.get(99).id():null);
        });
    }
    private record Scope(String human,String agent) {}
    private Scope scope(String sessionId,String agentId) {
        var s=collaboration.store;
        if(!s.sessionOwner(sessionId).filter(agentId::equals).isPresent()) throw missing();
        var human=s.sessionCustomer(sessionId).flatMap(id->s.actorBySubject(ActorKind.HUMAN,id)).filter(Actor::active).orElseThrow(TaskAccess::missing);
        var agent=s.actorBySubject(ActorKind.AGENT,agentId).filter(Actor::active).orElseThrow(TaskAccess::missing);
        return new Scope(human.id(),agent.id());
    }
}
