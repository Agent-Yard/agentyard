package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.platform.agent.AgentAuthorization.*;

import com.agentyard.persistence.agent.AgentStore;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Mounted descriptors describe capabilities; current grants decide whether they may execute. */
@Service
public class CapabilityAccess {
    private final AgentAuthorization authorization;
    public CapabilityAccess(AgentAuthorization authorization) { this.authorization=authorization; }

    public Object authorize(AgentStore store,Session session,AgentProfile profile,String type,String resourceId,String operation) {
        return switch(type) {
            case "TOOL" -> {
                var tool=profile.tools().stream().filter(t->t.resourceId().equals(resourceId)||t.resourceVersionId().equals(resourceId))
                    .findFirst().orElseThrow(()->denied("tool is not mounted in this instance"));
                if(tool.operations().stream().noneMatch(o->o.name().equals(operation))) throw denied("tool operation is not mounted");
                if(!authorization.allowed(store,session.participantId(),"tool.invoke:"+operation,ScopeKind.RESOURCE,tool.resourceId()))
                    authorization.require(store,session.participantId(),"tool.invoke",ScopeKind.RESOURCE,tool.resourceId(),false);
                yield tool;
            }
            case "KNOWLEDGE" -> {
                var binding=profile.knowledgeBinding();
                if(binding==null||!binding.knowledgeBaseId().equals(resourceId)) throw denied("knowledge is not mounted");
                authorization.require(store,session.participantId(),"knowledge.read",ScopeKind.RESOURCE,resourceId,false);
                yield binding;
            }
            case "SKILL" -> {
                var skill=profile.skills().stream().filter(s->s.resourceId().equals(resourceId)||s.resourceVersionId().equals(resourceId))
                    .findFirst().orElseThrow(()->denied("skill is not mounted"));
                authorization.require(store,session.participantId(),"skill.read",ScopeKind.RESOURCE,skill.resourceId(),false);
                yield skill;
            }
            case "SESSION_HISTORY" -> {
                if(!session.id().equals(resourceId)) authorization.require(store,session.participantId(),"session.history",ScopeKind.SESSION,resourceId,false);
                var target=store.session(resourceId,false).orElseThrow(()->notFound("Session"));
                yield Map.of("sessionId",resourceId,"participantId",target.participantId());
            }
            case "PLAYBOOK" -> {
                var playbook=profile.playbooks().stream().filter(p->p.playbookId().equals(resourceId)).findFirst()
                    .orElseThrow(()->denied("playbook version is not mounted"));
                authorization.require(store,session.participantId(),"playbook.run",ScopeKind.RESOURCE,resourceId,false);
                yield playbook;
            }
            default -> throw new IllegalArgumentException("unknown capability type");
        };
    }
}
