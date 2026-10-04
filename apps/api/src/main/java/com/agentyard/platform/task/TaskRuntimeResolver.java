package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.catalog.ReleaseRuntimeMapper.*;
import com.agentyard.platform.catalog.CatalogRepository;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import org.springframework.stereotype.Component;

@Component
public class TaskRuntimeResolver {
    private final CatalogRepository catalog;
    public TaskRuntimeResolver(CatalogRepository catalog) { this.catalog = catalog; }
    public String agentName(String agentId) {
        return catalog.listAgents().stream().filter(a -> a.id().equals(agentId)).findFirst().orElseThrow(TaskAccess::missing).name();
    }
    public RuntimeSnapshot resolve(String releaseId, String agentId, String playbookId) {
        var release = catalog.findAssistantReleaseById(releaseId).orElseThrow(TaskAccess::missing);
        if (release.status() != VersionStatus.PUBLISHED) TaskAccess.bad("execution requires a published release");
        var agent = release.agents().stream().filter(a -> a.agentId().equals(agentId)).findFirst().orElseThrow(TaskAccess::missing);
        var playbook = playbookId == null ? null : release.playbooks().stream().filter(p -> p.id().equals(playbookId)).findFirst().orElseThrow(TaskAccess::missing);
        if (playbook != null && !agent.playbookIds().contains(playbookId)) TaskAccess.bad("playbook is not assigned to this agent in the release");
        return new RuntimeSnapshot(release.assistantId(), release.releaseVersion(), toAgentConfig(agent, release, indexResourcesByVersionId(release)),
            playbook == null ? null : toPlaybookConfig(playbook));
    }
}
