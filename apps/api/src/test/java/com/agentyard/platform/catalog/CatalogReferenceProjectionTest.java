package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.contracts.session.SessionContracts.AgentDecisionAction;
import com.agentyard.contracts.runtime.WorkflowContracts.ResourceType;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.catalog.CatalogRepository.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogReferenceProjectionTest {
    private InMemoryCatalogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCatalogRepository();

        AssistantDto assistant = new AssistantDto(
            "ast-1", "scn-1", "Test Assistant", "desc",
            new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.now()),
            List.of(), List.of(), null, List.of(),
            "agt-1",
            new AssistantOwnerPolicyDto(3),
            new AssistantSessionPolicyDto("PT30M", "P7D", 20_000),
            new AssistantReplyPolicyDto(true),
            new AssistantPlaybookPolicyDto(null, null),
            new AssistantModelPolicyDto("res-llm-1"),
            new KnowledgeAccessPolicyDto(true, "kb-1"),
            new MemoryPolicyDto(false, 0)
        );

        AgentDto agent = new AgentDto(
            "agt-1", "ast-1", "Test Agent", "worker", "handles requests",
            new AgentExecutionPolicyDto(
                false, "res-llm-2", "system prompt",
                true, false, "kb-2",
                5,
                List.of("res-skill-1", "res-skill-2"),
                List.of("res-tool-1")
            ),
            true,
            List.of(
                AgentDecisionAction.REPLY,
                AgentDecisionAction.NO_OP,
                AgentDecisionAction.SWITCH_OWNER,
                AgentDecisionAction.RUN_PLAYBOOK,
                AgentDecisionAction.SESSION_HUMAN_HANDOFF
            ),
            List.of(),
            List.of()
        );

        AssistantReleaseDto release = new AssistantReleaseDto(
            "rel-1", "ast-1", "1.0.0", VersionStatus.PUBLISHED,
            Instant.now(), Instant.now(),
            new KnowledgeBindingSnapshotDto("kb-1", "知识库", "kr-1", "1.0.0", "snap-1", 5, "HYBRID", 0.5),
            new DefaultModelBindingDto("res-llm-1", "LLM Model", "rv-llm-1", "1.0.0", "OPENAI_COMPATIBLE", "gpt-test"),
            List.of(
                new AssistantReleaseResourceDto("res-llm-1", "LLM Model", ResourceType.LLM_MODEL, "rv-llm-1", "1.0.0", List.of(), null),
                new AssistantReleaseResourceDto("res-tool-1", "Tool", ResourceType.TOOL, "rv-tool-1", "1.0.0", List.of("agt-1"), null)
            ),
            List.of(new AssistantReleaseAgentDto(
                "agt-1", "Test Agent", "worker", "handles requests",
                agent.executionPolicy(),
                new KnowledgeBindingSnapshotDto("kb-2", "知识库2", "kr-2", "1.0.0", "snap-2", 5, "HYBRID", 0.5),
                true,
                agent.allowedActions(),
                List.of(),
                List.of(),
                List.of(), List.of()
            )),
            List.of(),
            assistant.primaryAgentId(),
            assistant.ownerPolicy(),
            assistant.sessionPolicy(),
            assistant.replyPolicy(),
            assistant.playbookPolicy(),
            assistant.modelPolicy(),
            assistant.knowledgeAccessPolicy(),
            assistant.memoryPolicy()
        );

        repository.inWriteTransaction(() -> {
            repository.upsertDomain(new BusinessDomainDto("dom-1", "Test Domain", "desc", List.of(), List.of(), List.of()));
            repository.upsertScenario(new ScenarioDto("scn-1", "dom-1", "Test Scenario", "goal", new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.now()), List.of()));
            repository.upsertAssistant(assistant);
            repository.upsertAgent(agent);
            repository.replaceAssistantReleases("ast-1", List.of(release));
            return null;
        });
    }

    @Test
    void findResourceBindings_returnsAssistantDefaultModel() {
        List<ResourceBindingRef> refs = repository.findResourceBindings("res-llm-1");
        assertEquals(1, refs.size());
        assertEquals("ASSISTANT_DEFAULT_MODEL", refs.getFirst().bindingKind());
        assertEquals("ASSISTANT", refs.getFirst().sourceType());
        assertEquals("ast-1", refs.getFirst().sourceId());
    }

    @Test
    void findResourceBindings_returnsAgentOverrideModel() {
        List<ResourceBindingRef> refs = repository.findResourceBindings("res-llm-2");
        assertEquals(1, refs.size());
        assertEquals("AGENT_OVERRIDE_MODEL", refs.getFirst().bindingKind());
        assertEquals("agt-1", refs.getFirst().sourceId());
    }

    @Test
    void findResourceBindings_returnsAgentSkillAndToolBindings() {
        List<ResourceBindingRef> skillRefs = repository.findResourceBindings("res-skill-1");
        assertEquals(1, skillRefs.size());
        assertEquals("AGENT_SKILL_ENABLED", skillRefs.getFirst().bindingKind());

        List<ResourceBindingRef> toolRefs = repository.findResourceBindings("res-tool-1");
        assertEquals(1, toolRefs.size());
        assertEquals("AGENT_TOOL_ENABLED", toolRefs.getFirst().bindingKind());
    }

    @Test
    void findResourceBindings_returnsEmptyForUnreferencedResource() {
        assertTrue(repository.findResourceBindings("res-nonexistent").isEmpty());
    }

    @Test
    void findReleaseResourceRefs_returnsFrozenResources() {
        List<ReleaseResourceRef> refs = repository.findReleaseResourceRefs("res-llm-1");
        assertEquals(1, refs.size());
        assertEquals("rel-1", refs.getFirst().releaseId());
        assertEquals("ast-1", refs.getFirst().assistantId());
        assertEquals("rv-llm-1", refs.getFirst().resourceVersionId());
        assertEquals("1.0.0", refs.getFirst().resourceVersion());
    }

    @Test
    void findKnowledgeBindings_returnsAssistantAndAgentBindings() {
        List<KnowledgeBindingRef> assistantRefs = repository.findKnowledgeBindings("kb-1");
        assertEquals(1, assistantRefs.size());
        assertEquals("ASSISTANT_DEFAULT_KNOWLEDGE_BASE", assistantRefs.getFirst().bindingKind());
        assertEquals("ASSISTANT", assistantRefs.getFirst().sourceType());

        List<KnowledgeBindingRef> agentRefs = repository.findKnowledgeBindings("kb-2");
        assertEquals(1, agentRefs.size());
        assertEquals("AGENT_OVERRIDE_KNOWLEDGE_BASE", agentRefs.getFirst().bindingKind());
        assertEquals("AGENT", agentRefs.getFirst().sourceType());
    }

    @Test
    void findReleaseKnowledgeRefs_returnsFrozenKnowledgeBindings() {
        List<ReleaseKnowledgeRef> refs = repository.findReleaseKnowledgeRefs("kb-1");
        assertEquals(1, refs.size());
        assertEquals("rel-1", refs.getFirst().releaseId());
        assertEquals("kr-1", refs.getFirst().knowledgeReleaseId());

        List<ReleaseKnowledgeRef> agentRefs = repository.findReleaseKnowledgeRefs("kb-2");
        assertEquals(1, agentRefs.size());
        assertEquals("kr-2", agentRefs.getFirst().knowledgeReleaseId());
    }

    @Test
    void findAllResourceBindings_returnsCompleteBindingSet() {
        List<ResourceBindingRef> all = repository.findAllResourceBindings();
        // 1 assistant default model + 1 agent override model + 2 agent skills + 1 agent tool = 5
        assertEquals(5, all.size());
    }

    @Test
    void findAllReleaseResourceRefs_returnsAllFrozenResources() {
        List<ReleaseResourceRef> all = repository.findAllReleaseResourceRefs();
        // 2 resources frozen in the release
        assertEquals(2, all.size());
    }
}
