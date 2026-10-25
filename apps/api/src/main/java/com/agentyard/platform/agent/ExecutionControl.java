package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;

import com.agentyard.persistence.agent.AgentStore;

/** Current work authority, shared by dispatch and incoming execution receipts. */
enum ExecutionControl {
    ACTIVE, PAUSED, CLOSED;

    static ExecutionControl resolve(AgentStore store, AgentAuthorization authorization,
        String sessionId, String taskId, Permission permission) {
        var session=store.session(sessionId,false).orElseThrow();
        if(session.status()==SessionStatus.CLOSED || store.tasks().actor(session.participantId()).filter(Actor::active).isEmpty()) return CLOSED;
        if(taskId!=null) {
            var task=store.tasks().task(taskId,false).orElseThrow();
            if(task.status()==TaskStatus.CANCELLED || task.status()==TaskStatus.COMPLETED
                || !authorization.taskPermissions(store,session.participantId(),taskId).contains(permission)) return CLOSED;
            if(task.status()==TaskStatus.PAUSED) return PAUSED;
        }
        return session.status()==SessionStatus.PAUSED ? PAUSED : ACTIVE;
    }
}
