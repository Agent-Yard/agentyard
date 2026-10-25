package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;

import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.platform.catalog.CatalogDtos.EntryDto;
import com.agentyard.platform.catalog.CatalogService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Execution authorization is independent from role prompts and immutable capability configuration. */
@Service
public class AgentAuthorization {
    private final CatalogService catalog;
    public AgentAuthorization(CatalogService catalog) { this.catalog=catalog; }

    public Grant require(AgentStore store,String participant,String operation,ScopeKind kind,String id,boolean delegable) {
        active(store,participant);
        return store.grants(participant).stream().filter(g->matches(g,operation,kind,id)&&(!delegable||g.delegable()))
            .filter(g->effective(store,g,new HashSet<>())).findFirst()
            .orElseThrow(()->denied("operation is not granted: "+operation));
    }
    public boolean allowed(AgentStore store,String participant,String operation,ScopeKind kind,String id) {
        return store.grants(participant).stream().anyMatch(g->matches(g,operation,kind,id)&&effective(store,g,new HashSet<>()));
    }
    public boolean effective(AgentStore store,Grant grant,Set<String> visited) {
        if(!visited.add(grant.id()) || visited.size()>100 || grant.revokedAt()!=null ||
            grant.expiresAt()!=null&&!grant.expiresAt().isAfter(Instant.now())) return false;
        if(store.tasks().actor(grant.participantId()).filter(Actor::active).isEmpty()) return false;
        if(grant.parentGrantId()!=null) {
            return store.grant(grant.parentGrantId()).filter(parent->parent.delegable()
                && parent.participantId().equals(grant.grantedBy())
                && matches(parent,grant.operation(),grant.scopeKind(),grant.scopeId())
                && (parent.expiresAt()==null || grant.expiresAt()!=null&&!grant.expiresAt().isAfter(parent.expiresAt())))
                .map(parent->effective(store,parent,visited)).orElse(false);
        }
        // Entry policy changes take effect without republishing role templates or replacing Sessions.
        var binding=com.agentyard.persistence.jooq.Tables.AGENT_RECEPTION_BINDING;
        var session=store.participantSession(grant.participantId());
        if(session.isPresent()) {
            String entryId=store.database().select(binding.ENTRY_ID).from(binding).where(binding.SESSION_ID.eq(session.get().id())).fetchOne(binding.ENTRY_ID);
            if(entryId!=null) {
                EntryDto entry;
                try { entry=catalog.getEntry(entryId); } catch(java.util.NoSuchElementException missing) { return false; }
                if(!entry.enabled()) return false;
                if(grant.operation().equals("agent.create")&&grant.scopeKind()==ScopeKind.TEMPLATE)
                    return entry.allowedTemplateIds().contains(grant.scopeId());
                return entry.capabilityGrants().stream().anyMatch(g->g.operation().equals(grant.operation())&&g.scopeKind()==grant.scopeKind()
                    &&g.scopeId().equals(grant.scopeId())&&(!grant.delegable()||g.delegable())
                    &&(g.expiresAt()==null||g.expiresAt().isAfter(Instant.now())));
            }
        }
        return true;
    }
    private boolean matches(Grant g,String operation,ScopeKind kind,String id) {
        boolean operationMatch=g.operation().equals(operation)||g.operation().equals("*");
        return operationMatch && (g.scopeKind()==ScopeKind.PLATFORM&&g.scopeId().equals("*")
            || g.scopeKind()==kind&&(g.scopeId().equals(id)||g.scopeId().equals("*")));
    }
    public Actor active(AgentStore store,String participantId) {
        return store.tasks().actor(participantId).filter(Actor::active).orElseThrow(()->denied("participant is not active"));
    }
    public Set<Permission> taskPermissions(AgentStore store,String participantId,String taskId) {
        active(store,participantId);
        var task=store.tasks().task(taskId,false).orElseThrow(()->notFound("Task"));
        var assigned=store.tasks().participants(taskId).stream().filter(p->p.actorId().equals(participantId))
            .findFirst().map(Participant::roles).orElse(List.of());
        Set<Permission> permissions=new HashSet<>();
        task.policy().roles().stream().filter(r->assigned.contains(r.key())).forEach(r->permissions.addAll(r.permissions()));
        return Set.copyOf(permissions);
    }
    public void requireTask(AgentStore store,String participant,String taskId,Permission permission) {
        if(!taskPermissions(store,participant,taskId).contains(permission)) throw denied("Task operation is not authorized");
    }
    public boolean owns(AgentStore store,String participant,String ownerType,String ownerId) {
        var actor=active(store,participant);
        if("PERSON".equals(ownerType)) return ownerId.equals(participant)||ownerId.equals(actor.subjectId());
        if("ORGANIZATION".equals(ownerType)) return store.tasks().members(ownerId).stream().anyMatch(p->p.actorId().equals(participant));
        return false;
    }
    public boolean entryVisible(AgentStore store,String participant,EntryDto entry) {
        return entry.enabled()&&(entry.shareScope()==com.agentyard.contracts.runtime.WorkflowContracts.ShareScope.SHARED
            ||owns(store,participant,entry.ownerType(),entry.ownerId()));
    }
    public void requireSessionRead(AgentStore store,String participant,Session session) {
        active(store,participant);
        if(participant.equals(session.participantId())) return;
        if(!allowed(store,participant,"session.read",ScopeKind.SESSION,session.id())) throw denied("private Session context is not shared");
    }
    public void requireMessage(AgentStore store,String sender,String recipient,String taskId) {
        active(store,sender);active(store,recipient);
        if(taskId!=null) {
            requireTask(store,sender,taskId,Permission.VIEW);requireTask(store,recipient,taskId,Permission.VIEW);
            return;
        }
        if(sender.equals(recipient)) return;
        var from=store.participantSession(sender);var to=store.participantSession(recipient);
        if(from.filter(s->Objects.equals(s.replyToParticipantId(),recipient)||Objects.equals(s.createdBy(),recipient)).isPresent()
            ||to.filter(s->Objects.equals(s.replyToParticipantId(),sender)||Objects.equals(s.createdBy(),sender)).isPresent()) return;
        if(!allowed(store,sender,"message.send",ScopeKind.PARTICIPANT,recipient)) throw denied("no authorized communication relationship");
    }
    public static ResponseStatusException denied(String reason) { return new ResponseStatusException(HttpStatus.FORBIDDEN,reason); }
    public static ResponseStatusException notFound(String type) { return new ResponseStatusException(HttpStatus.NOT_FOUND,type+" not found"); }
}
