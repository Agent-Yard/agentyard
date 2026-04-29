package com.lynxus.platform.catalog;

import static com.lynxus.persistence.jooq.Tables.CATALOG_AGENT;
import static com.lynxus.persistence.jooq.Tables.CATALOG_ASSISTANT;
import static com.lynxus.persistence.jooq.Tables.CATALOG_ASSISTANT_RELEASES;
import static com.lynxus.persistence.jooq.Tables.CATALOG_DOMAIN;
import static com.lynxus.persistence.jooq.Tables.CATALOG_PLAYBOOK;
import static com.lynxus.persistence.jooq.Tables.CATALOG_REF_KNOWLEDGE_BINDING;
import static com.lynxus.persistence.jooq.Tables.CATALOG_REF_RELEASE_KNOWLEDGE;
import static com.lynxus.persistence.jooq.Tables.CATALOG_REF_RELEASE_RESOURCE;
import static com.lynxus.persistence.jooq.Tables.CATALOG_REF_RESOURCE_BINDING;
import static com.lynxus.persistence.jooq.Tables.CATALOG_RESOURCE;
import static com.lynxus.persistence.jooq.Tables.CATALOG_RESOURCE_VERSIONS;
import static com.lynxus.persistence.jooq.Tables.CATALOG_SCENARIO;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import com.lynxus.persistence.shared.SharedStateRevisionStore;
import com.lynxus.platform.shared.ConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JooqCatalogRepository implements CatalogRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AgentDecisionAction>> ACTION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PlaybookNodeDto>> PLAYBOOK_NODE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PlaybookEdgeDto>> PLAYBOOK_EDGE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantReleaseResourceDto>> ASSISTANT_RELEASE_RESOURCE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantReleaseAgentDto>> ASSISTANT_RELEASE_AGENT_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PlaybookDto>> PLAYBOOK_LIST = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;
    private final SharedStateRevisionStore revisionStore;
    private final ThreadLocal<Boolean> writeTransactionOpen = ThreadLocal.withInitial(() -> false);

    public JooqCatalogRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.jsonbSupport = new JooqJsonbSupport(
            objectMapper.rebuild()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build()
        );
        this.revisionStore = new SharedStateRevisionStore(dsl);
    }

    @Override
    public long revision() {
        return revisionStore.revision("catalog");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T inReadTransaction(java.util.function.Supplier<T> action) {
        return action.get();
    }

    @Override
    @Transactional
    public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
        if (writeTransactionOpen.get()) {
            return action.get();
        }
        long expectedRevision = revision();
        if (!revisionStore.reserveRevision("catalog", expectedRevision)) {
            throw new ConflictException("catalog changed on another instance; retry the request");
        }
        writeTransactionOpen.set(true);
        try {
            T result = action.get();
            refreshDerivedReferenceTables();
            return result;
        } finally {
            writeTransactionOpen.remove();
        }
    }

    @Override
    public List<BusinessDomainDto> listDomains() {
        return dsl.selectFrom(CATALOG_DOMAIN)
            .orderBy(CATALOG_DOMAIN.ID.asc())
            .fetch(record -> new BusinessDomainDto(
                record.getId(),
                record.getName(),
                record.getDescription(),
                List.of(),
                List.of(),
                List.of()
            ));
    }

    @Override
    public Optional<BusinessDomainDto> findDomain(String domainId) {
        return dsl.selectFrom(CATALOG_DOMAIN)
            .where(CATALOG_DOMAIN.ID.eq(domainId))
            .fetchOptional(record -> new BusinessDomainDto(
                record.getId(),
                record.getName(),
                record.getDescription(),
                List.of(),
                List.of(),
                List.of()
            ));
    }

    @Override
    public List<ScenarioDto> listScenarios() {
        return dsl.selectFrom(CATALOG_SCENARIO)
            .orderBy(CATALOG_SCENARIO.ID.asc())
            .fetch(record -> new ScenarioDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                record.getGoal(),
                new VersionDto(
                    record.getVersion(),
                    VersionStatus.valueOf(record.getVersionStatus()),
                    JooqTimeSupport.toInstant(record.getVersionUpdatedAt())
                ),
                List.of()
            ));
    }

    @Override
    public Optional<ScenarioDto> findScenario(String scenarioId) {
        return dsl.selectFrom(CATALOG_SCENARIO)
            .where(CATALOG_SCENARIO.ID.eq(scenarioId))
            .fetchOptional(record -> new ScenarioDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                record.getGoal(),
                new VersionDto(
                    record.getVersion(),
                    VersionStatus.valueOf(record.getVersionStatus()),
                    JooqTimeSupport.toInstant(record.getVersionUpdatedAt())
                ),
                List.of()
            ));
    }

    @Override
    public List<AssistantDto> listAssistants() {
        return dsl.selectFrom(CATALOG_ASSISTANT)
            .orderBy(CATALOG_ASSISTANT.ID.asc())
            .fetch(record -> new AssistantDto(
                record.getId(),
                record.getScenarioId(),
                record.getName(),
                record.getDescription(),
                new VersionDto(
                    record.getVersion(),
                    VersionStatus.valueOf(record.getVersionStatus()),
                    JooqTimeSupport.toInstant(record.getVersionUpdatedAt())
                ),
                List.of(),
                List.of(),
                null,
                List.of(),
                record.getPrimaryAgentId(),
                jsonbSupport.read(record.getOwnerPolicy(), AssistantOwnerPolicyDto.class),
                jsonbSupport.read(record.getSessionPolicy(), AssistantSessionPolicyDto.class),
                jsonbSupport.read(record.getReplyPolicy(), AssistantReplyPolicyDto.class),
                jsonbSupport.read(record.getPlaybookPolicy(), AssistantPlaybookPolicyDto.class),
                jsonbSupport.read(record.getModelPolicy(), AssistantModelPolicyDto.class),
                record.getPrivacyModelResourceId(),
                record.getPrivacyMappingEnabled(),
                jsonbSupport.read(record.getKnowledgeAccessPolicy(), KnowledgeAccessPolicyDto.class),
                jsonbSupport.read(record.getMemoryPolicy(), MemoryPolicyDto.class)
            ));
    }

    @Override
    public Optional<AssistantDto> findAssistant(String assistantId) {
        return dsl.selectFrom(CATALOG_ASSISTANT)
            .where(CATALOG_ASSISTANT.ID.eq(assistantId))
            .fetchOptional(record -> new AssistantDto(
                record.getId(),
                record.getScenarioId(),
                record.getName(),
                record.getDescription(),
                new VersionDto(
                    record.getVersion(),
                    VersionStatus.valueOf(record.getVersionStatus()),
                    JooqTimeSupport.toInstant(record.getVersionUpdatedAt())
                ),
                List.of(),
                List.of(),
                null,
                List.of(),
                record.getPrimaryAgentId(),
                jsonbSupport.read(record.getOwnerPolicy(), AssistantOwnerPolicyDto.class),
                jsonbSupport.read(record.getSessionPolicy(), AssistantSessionPolicyDto.class),
                jsonbSupport.read(record.getReplyPolicy(), AssistantReplyPolicyDto.class),
                jsonbSupport.read(record.getPlaybookPolicy(), AssistantPlaybookPolicyDto.class),
                jsonbSupport.read(record.getModelPolicy(), AssistantModelPolicyDto.class),
                record.getPrivacyModelResourceId(),
                record.getPrivacyMappingEnabled(),
                jsonbSupport.read(record.getKnowledgeAccessPolicy(), KnowledgeAccessPolicyDto.class),
                jsonbSupport.read(record.getMemoryPolicy(), MemoryPolicyDto.class)
            ));
    }

    @Override
    public List<AgentDto> listAgents() {
        return dsl.selectFrom(CATALOG_AGENT)
            .orderBy(CATALOG_AGENT.ID.asc())
            .fetch(record -> new AgentDto(
                record.getId(),
                record.getAssistantId(),
                record.getName(),
                record.getRole(),
                record.getResponsibility(),
                jsonbSupport.read(record.getExecutionPolicy(), AgentExecutionPolicyDto.class),
                record.getCanOwnSession(),
                readActionList(record.getAllowedActions()),
                readStringList(record.getSwitchableOwnerAgentIds()),
                readStringList(record.getPlaybookIds())
            ));
    }

    @Override
    public Optional<AgentDto> findAgent(String agentId) {
        return dsl.selectFrom(CATALOG_AGENT)
            .where(CATALOG_AGENT.ID.eq(agentId))
            .fetchOptional(record -> new AgentDto(
                record.getId(),
                record.getAssistantId(),
                record.getName(),
                record.getRole(),
                record.getResponsibility(),
                jsonbSupport.read(record.getExecutionPolicy(), AgentExecutionPolicyDto.class),
                record.getCanOwnSession(),
                readActionList(record.getAllowedActions()),
                readStringList(record.getSwitchableOwnerAgentIds()),
                readStringList(record.getPlaybookIds())
            ));
    }

    @Override
    public List<PlaybookDto> listPlaybooks() {
        return dsl.selectFrom(CATALOG_PLAYBOOK)
            .orderBy(CATALOG_PLAYBOOK.ID.asc())
            .fetch(record -> new PlaybookDto(
                record.getId(),
                record.getAssistantId(),
                record.getName(),
                record.getDescription(),
                record.getInputSchema(),
                record.getResultSchema(),
                jsonbSupport.read(record.getExecutionPolicy(), PlaybookExecutionPolicyDto.class),
                record.getAllowHumanTask(),
                record.getAllowExternalInteraction(),
                record.getEntryNodeKey(),
                readPlaybookNodes(record.getNodes()),
                readPlaybookEdges(record.getEdges())
            ));
    }

    @Override
    public Optional<PlaybookDto> findPlaybook(String playbookId) {
        return dsl.selectFrom(CATALOG_PLAYBOOK)
            .where(CATALOG_PLAYBOOK.ID.eq(playbookId))
            .fetchOptional(record -> new PlaybookDto(
                record.getId(),
                record.getAssistantId(),
                record.getName(),
                record.getDescription(),
                record.getInputSchema(),
                record.getResultSchema(),
                jsonbSupport.read(record.getExecutionPolicy(), PlaybookExecutionPolicyDto.class),
                record.getAllowHumanTask(),
                record.getAllowExternalInteraction(),
                record.getEntryNodeKey(),
                readPlaybookNodes(record.getNodes()),
                readPlaybookEdges(record.getEdges())
            ));
    }

    @Override
    public List<ResourceDto> listResources() {
        return dsl.selectFrom(CATALOG_RESOURCE)
            .orderBy(CATALOG_RESOURCE.ID.asc())
            .fetch(record -> new ResourceDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                ResourceType.valueOf(record.getType()),
                ShareScope.valueOf(record.getShareScope()),
                record.getOwnerType(),
                record.getOwnerId(),
                record.getSummary(),
                record.getSteward(),
                readStringList(record.getTags()),
                null,
                null,
                List.of()
            ));
    }

    @Override
    public Optional<ResourceDto> findResource(String resourceId) {
        return dsl.selectFrom(CATALOG_RESOURCE)
            .where(CATALOG_RESOURCE.ID.eq(resourceId))
            .fetchOptional(record -> new ResourceDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                ResourceType.valueOf(record.getType()),
                ShareScope.valueOf(record.getShareScope()),
                record.getOwnerType(),
                record.getOwnerId(),
                record.getSummary(),
                record.getSteward(),
                readStringList(record.getTags()),
                null,
                null,
                List.of()
            ));
    }

    @Override
    public List<StoredResourceVersion> listResourceVersions(String resourceId) {
        return dsl.selectFrom(CATALOG_RESOURCE_VERSIONS)
            .where(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID.eq(resourceId))
            .orderBy(CATALOG_RESOURCE_VERSIONS.CREATED_AT.asc(), CATALOG_RESOURCE_VERSIONS.ID.asc())
            .fetch(record -> new StoredResourceVersion(
                record.getId(),
                record.getResourceId(),
                record.getVersion(),
                VersionStatus.valueOf(record.getStatus()),
                record.getSummary(),
                record.getConfigDigest(),
                JooqTimeSupport.toInstant(record.getCreatedAt()),
                JooqTimeSupport.toInstant(record.getPublishedAt()),
                jsonbSupport.read(record.getConfiguration(), ResourceVersionConfigurationDto.class)
            ));
    }

    @Override
    public List<AssistantReleaseDto> listAssistantReleases(String assistantId) {
        return dsl.selectFrom(CATALOG_ASSISTANT_RELEASES)
            .where(CATALOG_ASSISTANT_RELEASES.ASSISTANT_ID.eq(assistantId))
            .orderBy(CATALOG_ASSISTANT_RELEASES.CREATED_AT.asc(), CATALOG_ASSISTANT_RELEASES.ID.asc())
            .fetch(record -> new AssistantReleaseDto(
                record.getId(),
                record.getAssistantId(),
                record.getReleaseVersion(),
                VersionStatus.valueOf(record.getStatus()),
                JooqTimeSupport.toInstant(record.getCreatedAt()),
                JooqTimeSupport.toInstant(record.getPublishedAt()),
                jsonbSupport.read(record.getAssistantKnowledgeBinding(), KnowledgeBindingSnapshotDto.class),
                jsonbSupport.read(record.getDefaultModelBinding(), DefaultModelBindingDto.class),
                jsonbSupport.read(record.getPrivacyModelBinding(), DefaultModelBindingDto.class),
                record.getPrivacyMappingEnabled(),
                readAssistantReleaseResources(record.getResources()),
                readAssistantReleaseAgents(record.getAgents()),
                readPlaybookSnapshots(record.getPlaybooks()),
                record.getPrimaryAgentId(),
                jsonbSupport.read(record.getOwnerPolicy(), AssistantOwnerPolicyDto.class),
                jsonbSupport.read(record.getSessionPolicy(), AssistantSessionPolicyDto.class),
                jsonbSupport.read(record.getReplyPolicy(), AssistantReplyPolicyDto.class),
                jsonbSupport.read(record.getPlaybookPolicy(), AssistantPlaybookPolicyDto.class),
                jsonbSupport.read(record.getModelPolicy(), AssistantModelPolicyDto.class),
                jsonbSupport.read(record.getKnowledgeAccessPolicy(), KnowledgeAccessPolicyDto.class),
                jsonbSupport.read(record.getMemoryPolicy(), MemoryPolicyDto.class)
            ));
    }

    @Override
    public Optional<AssistantReleaseDto> findAssistantReleaseById(String releaseId) {
        return dsl.selectFrom(CATALOG_ASSISTANT_RELEASES)
            .where(CATALOG_ASSISTANT_RELEASES.ID.eq(releaseId))
            .fetchOptional(record -> new AssistantReleaseDto(
                record.getId(),
                record.getAssistantId(),
                record.getReleaseVersion(),
                VersionStatus.valueOf(record.getStatus()),
                JooqTimeSupport.toInstant(record.getCreatedAt()),
                JooqTimeSupport.toInstant(record.getPublishedAt()),
                jsonbSupport.read(record.getAssistantKnowledgeBinding(), KnowledgeBindingSnapshotDto.class),
                jsonbSupport.read(record.getDefaultModelBinding(), DefaultModelBindingDto.class),
                jsonbSupport.read(record.getPrivacyModelBinding(), DefaultModelBindingDto.class),
                record.getPrivacyMappingEnabled(),
                readAssistantReleaseResources(record.getResources()),
                readAssistantReleaseAgents(record.getAgents()),
                readPlaybookSnapshots(record.getPlaybooks()),
                record.getPrimaryAgentId(),
                jsonbSupport.read(record.getOwnerPolicy(), AssistantOwnerPolicyDto.class),
                jsonbSupport.read(record.getSessionPolicy(), AssistantSessionPolicyDto.class),
                jsonbSupport.read(record.getReplyPolicy(), AssistantReplyPolicyDto.class),
                jsonbSupport.read(record.getPlaybookPolicy(), AssistantPlaybookPolicyDto.class),
                jsonbSupport.read(record.getModelPolicy(), AssistantModelPolicyDto.class),
                jsonbSupport.read(record.getKnowledgeAccessPolicy(), KnowledgeAccessPolicyDto.class),
                jsonbSupport.read(record.getMemoryPolicy(), MemoryPolicyDto.class)
            ));
    }

    @Override
    public void upsertDomain(BusinessDomainDto domain) {
        dsl.insertInto(CATALOG_DOMAIN)
            .set(CATALOG_DOMAIN.ID, domain.id())
            .set(CATALOG_DOMAIN.NAME, domain.name())
            .set(CATALOG_DOMAIN.DESCRIPTION, domain.description())
            .onConflict(CATALOG_DOMAIN.ID)
            .doUpdate()
            .set(CATALOG_DOMAIN.NAME, domain.name())
            .set(CATALOG_DOMAIN.DESCRIPTION, domain.description())
            .execute();
    }

    @Override
    public void deleteDomain(String domainId) {
        dsl.deleteFrom(CATALOG_DOMAIN)
            .where(CATALOG_DOMAIN.ID.eq(domainId))
            .execute();
    }

    @Override
    public void upsertScenario(ScenarioDto scenario) {
        dsl.insertInto(CATALOG_SCENARIO)
            .set(CATALOG_SCENARIO.ID, scenario.id())
            .set(CATALOG_SCENARIO.DOMAIN_ID, scenario.domainId())
            .set(CATALOG_SCENARIO.NAME, scenario.name())
            .set(CATALOG_SCENARIO.GOAL, scenario.goal())
            .set(CATALOG_SCENARIO.VERSION, scenario.version().version())
            .set(CATALOG_SCENARIO.VERSION_STATUS, scenario.version().status().name())
            .set(CATALOG_SCENARIO.VERSION_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(scenario.version().updatedAt()))
            .onConflict(CATALOG_SCENARIO.ID)
            .doUpdate()
            .set(CATALOG_SCENARIO.DOMAIN_ID, scenario.domainId())
            .set(CATALOG_SCENARIO.NAME, scenario.name())
            .set(CATALOG_SCENARIO.GOAL, scenario.goal())
            .set(CATALOG_SCENARIO.VERSION, scenario.version().version())
            .set(CATALOG_SCENARIO.VERSION_STATUS, scenario.version().status().name())
            .set(CATALOG_SCENARIO.VERSION_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(scenario.version().updatedAt()))
            .execute();
    }

    @Override
    public void deleteScenario(String scenarioId) {
        dsl.deleteFrom(CATALOG_SCENARIO)
            .where(CATALOG_SCENARIO.ID.eq(scenarioId))
            .execute();
    }

    @Override
    public void upsertAssistant(AssistantDto assistant) {
        dsl.insertInto(CATALOG_ASSISTANT)
            .set(CATALOG_ASSISTANT.ID, assistant.id())
            .set(CATALOG_ASSISTANT.SCENARIO_ID, assistant.scenarioId())
            .set(CATALOG_ASSISTANT.NAME, assistant.name())
            .set(CATALOG_ASSISTANT.DESCRIPTION, assistant.description())
            .set(CATALOG_ASSISTANT.VERSION, assistant.version().version())
            .set(CATALOG_ASSISTANT.VERSION_STATUS, assistant.version().status().name())
            .set(CATALOG_ASSISTANT.VERSION_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(assistant.version().updatedAt()))
            .set(CATALOG_ASSISTANT.PRIMARY_AGENT_ID, assistant.primaryAgentId())
            .set(CATALOG_ASSISTANT.OWNER_POLICY, jsonbSupport.toJsonb(assistant.ownerPolicy()))
            .set(CATALOG_ASSISTANT.SESSION_POLICY, jsonbSupport.toJsonb(assistant.sessionPolicy()))
            .set(CATALOG_ASSISTANT.REPLY_POLICY, jsonbSupport.toJsonb(assistant.replyPolicy()))
            .set(CATALOG_ASSISTANT.PLAYBOOK_POLICY, jsonbSupport.toJsonb(assistant.playbookPolicy()))
            .set(CATALOG_ASSISTANT.MODEL_POLICY, jsonbSupport.toJsonb(assistant.modelPolicy()))
            .set(CATALOG_ASSISTANT.PRIVACY_MODEL_RESOURCE_ID, assistant.privacyModelResourceId())
            .set(CATALOG_ASSISTANT.PRIVACY_MAPPING_ENABLED, assistant.privacyMappingEnabled())
            .set(CATALOG_ASSISTANT.KNOWLEDGE_ACCESS_POLICY, jsonbSupport.toJsonb(assistant.knowledgeAccessPolicy()))
            .set(CATALOG_ASSISTANT.MEMORY_POLICY, jsonbSupport.toJsonb(assistant.memoryPolicy()))
            .onConflict(CATALOG_ASSISTANT.ID)
            .doUpdate()
            .set(CATALOG_ASSISTANT.SCENARIO_ID, assistant.scenarioId())
            .set(CATALOG_ASSISTANT.NAME, assistant.name())
            .set(CATALOG_ASSISTANT.DESCRIPTION, assistant.description())
            .set(CATALOG_ASSISTANT.VERSION, assistant.version().version())
            .set(CATALOG_ASSISTANT.VERSION_STATUS, assistant.version().status().name())
            .set(CATALOG_ASSISTANT.VERSION_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(assistant.version().updatedAt()))
            .set(CATALOG_ASSISTANT.PRIMARY_AGENT_ID, assistant.primaryAgentId())
            .set(CATALOG_ASSISTANT.OWNER_POLICY, jsonbSupport.toJsonb(assistant.ownerPolicy()))
            .set(CATALOG_ASSISTANT.SESSION_POLICY, jsonbSupport.toJsonb(assistant.sessionPolicy()))
            .set(CATALOG_ASSISTANT.REPLY_POLICY, jsonbSupport.toJsonb(assistant.replyPolicy()))
            .set(CATALOG_ASSISTANT.PLAYBOOK_POLICY, jsonbSupport.toJsonb(assistant.playbookPolicy()))
            .set(CATALOG_ASSISTANT.MODEL_POLICY, jsonbSupport.toJsonb(assistant.modelPolicy()))
            .set(CATALOG_ASSISTANT.PRIVACY_MODEL_RESOURCE_ID, assistant.privacyModelResourceId())
            .set(CATALOG_ASSISTANT.PRIVACY_MAPPING_ENABLED, assistant.privacyMappingEnabled())
            .set(CATALOG_ASSISTANT.KNOWLEDGE_ACCESS_POLICY, jsonbSupport.toJsonb(assistant.knowledgeAccessPolicy()))
            .set(CATALOG_ASSISTANT.MEMORY_POLICY, jsonbSupport.toJsonb(assistant.memoryPolicy()))
            .execute();
    }

    @Override
    public void deleteAssistant(String assistantId) {
        dsl.deleteFrom(CATALOG_ASSISTANT)
            .where(CATALOG_ASSISTANT.ID.eq(assistantId))
            .execute();
    }

    @Override
    public void upsertAgent(AgentDto agent) {
        dsl.insertInto(CATALOG_AGENT)
            .set(CATALOG_AGENT.ID, agent.id())
            .set(CATALOG_AGENT.ASSISTANT_ID, agent.assistantId())
            .set(CATALOG_AGENT.NAME, agent.name())
            .set(CATALOG_AGENT.ROLE, agent.role())
            .set(CATALOG_AGENT.RESPONSIBILITY, agent.responsibility())
            .set(CATALOG_AGENT.EXECUTION_POLICY, jsonbSupport.toJsonb(agent.executionPolicy()))
            .set(CATALOG_AGENT.CAN_OWN_SESSION, agent.canOwnSession())
            .set(CATALOG_AGENT.ALLOWED_ACTIONS, jsonbSupport.toJsonb(agent.allowedActions()))
            .set(CATALOG_AGENT.SWITCHABLE_OWNER_AGENT_IDS, jsonbSupport.toJsonb(agent.switchableOwnerAgentIds()))
            .set(CATALOG_AGENT.PLAYBOOK_IDS, jsonbSupport.toJsonb(agent.playbookIds()))
            .onConflict(CATALOG_AGENT.ID)
            .doUpdate()
            .set(CATALOG_AGENT.ASSISTANT_ID, agent.assistantId())
            .set(CATALOG_AGENT.NAME, agent.name())
            .set(CATALOG_AGENT.ROLE, agent.role())
            .set(CATALOG_AGENT.RESPONSIBILITY, agent.responsibility())
            .set(CATALOG_AGENT.EXECUTION_POLICY, jsonbSupport.toJsonb(agent.executionPolicy()))
            .set(CATALOG_AGENT.CAN_OWN_SESSION, agent.canOwnSession())
            .set(CATALOG_AGENT.ALLOWED_ACTIONS, jsonbSupport.toJsonb(agent.allowedActions()))
            .set(CATALOG_AGENT.SWITCHABLE_OWNER_AGENT_IDS, jsonbSupport.toJsonb(agent.switchableOwnerAgentIds()))
            .set(CATALOG_AGENT.PLAYBOOK_IDS, jsonbSupport.toJsonb(agent.playbookIds()))
            .execute();
    }

    @Override
    public void deleteAgent(String agentId) {
        dsl.deleteFrom(CATALOG_AGENT)
            .where(CATALOG_AGENT.ID.eq(agentId))
            .execute();
    }

    @Override
    public void upsertPlaybook(PlaybookDto playbook) {
        dsl.insertInto(CATALOG_PLAYBOOK)
            .set(CATALOG_PLAYBOOK.ID, playbook.id())
            .set(CATALOG_PLAYBOOK.ASSISTANT_ID, playbook.assistantId())
            .set(CATALOG_PLAYBOOK.NAME, playbook.name())
            .set(CATALOG_PLAYBOOK.DESCRIPTION, playbook.description())
            .set(CATALOG_PLAYBOOK.INPUT_SCHEMA, playbook.inputSchema())
            .set(CATALOG_PLAYBOOK.RESULT_SCHEMA, playbook.resultSchema())
            .set(CATALOG_PLAYBOOK.EXECUTION_POLICY, jsonbSupport.toJsonb(playbook.executionPolicy()))
            .set(CATALOG_PLAYBOOK.ALLOW_HUMAN_TASK, playbook.allowHumanTask())
            .set(CATALOG_PLAYBOOK.ALLOW_EXTERNAL_INTERACTION, playbook.allowExternalInteraction())
            .set(CATALOG_PLAYBOOK.ENTRY_NODE_KEY, playbook.entryNodeKey())
            .set(CATALOG_PLAYBOOK.NODES, jsonbSupport.toJsonb(safe(playbook.nodes())))
            .set(CATALOG_PLAYBOOK.EDGES, jsonbSupport.toJsonb(safe(playbook.edges())))
            .onConflict(CATALOG_PLAYBOOK.ID)
            .doUpdate()
            .set(CATALOG_PLAYBOOK.ASSISTANT_ID, playbook.assistantId())
            .set(CATALOG_PLAYBOOK.NAME, playbook.name())
            .set(CATALOG_PLAYBOOK.DESCRIPTION, playbook.description())
            .set(CATALOG_PLAYBOOK.INPUT_SCHEMA, playbook.inputSchema())
            .set(CATALOG_PLAYBOOK.RESULT_SCHEMA, playbook.resultSchema())
            .set(CATALOG_PLAYBOOK.EXECUTION_POLICY, jsonbSupport.toJsonb(playbook.executionPolicy()))
            .set(CATALOG_PLAYBOOK.ALLOW_HUMAN_TASK, playbook.allowHumanTask())
            .set(CATALOG_PLAYBOOK.ALLOW_EXTERNAL_INTERACTION, playbook.allowExternalInteraction())
            .set(CATALOG_PLAYBOOK.ENTRY_NODE_KEY, playbook.entryNodeKey())
            .set(CATALOG_PLAYBOOK.NODES, jsonbSupport.toJsonb(safe(playbook.nodes())))
            .set(CATALOG_PLAYBOOK.EDGES, jsonbSupport.toJsonb(safe(playbook.edges())))
            .execute();
    }

    @Override
    public void deletePlaybook(String playbookId) {
        dsl.deleteFrom(CATALOG_PLAYBOOK)
            .where(CATALOG_PLAYBOOK.ID.eq(playbookId))
            .execute();
    }

    @Override
    public void upsertResource(ResourceDto resource) {
        dsl.insertInto(CATALOG_RESOURCE)
            .set(CATALOG_RESOURCE.ID, resource.id())
            .set(CATALOG_RESOURCE.DOMAIN_ID, resource.domainId())
            .set(CATALOG_RESOURCE.NAME, resource.name())
            .set(CATALOG_RESOURCE.TYPE, resource.type().name())
            .set(CATALOG_RESOURCE.SHARE_SCOPE, resource.shareScope().name())
            .set(CATALOG_RESOURCE.OWNER_TYPE, resource.ownerType())
            .set(CATALOG_RESOURCE.OWNER_ID, resource.ownerId())
            .set(CATALOG_RESOURCE.SUMMARY, resource.summary())
            .set(CATALOG_RESOURCE.STEWARD, resource.steward())
            .set(CATALOG_RESOURCE.TAGS, jsonbSupport.toJsonb(safe(resource.tags())))
            .onConflict(CATALOG_RESOURCE.ID)
            .doUpdate()
            .set(CATALOG_RESOURCE.DOMAIN_ID, resource.domainId())
            .set(CATALOG_RESOURCE.NAME, resource.name())
            .set(CATALOG_RESOURCE.TYPE, resource.type().name())
            .set(CATALOG_RESOURCE.SHARE_SCOPE, resource.shareScope().name())
            .set(CATALOG_RESOURCE.OWNER_TYPE, resource.ownerType())
            .set(CATALOG_RESOURCE.OWNER_ID, resource.ownerId())
            .set(CATALOG_RESOURCE.SUMMARY, resource.summary())
            .set(CATALOG_RESOURCE.STEWARD, resource.steward())
            .set(CATALOG_RESOURCE.TAGS, jsonbSupport.toJsonb(safe(resource.tags())))
            .execute();
    }

    @Override
    public void deleteResource(String resourceId) {
        dsl.deleteFrom(CATALOG_RESOURCE)
            .where(CATALOG_RESOURCE.ID.eq(resourceId))
            .execute();
    }

    @Override
    public void replaceResourceVersions(String resourceId, List<StoredResourceVersion> versions) {
        deleteResourceVersions(resourceId);
        for (StoredResourceVersion version : versions) {
            dsl.insertInto(CATALOG_RESOURCE_VERSIONS)
                .set(CATALOG_RESOURCE_VERSIONS.ID, version.id())
                .set(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID, version.resourceId())
                .set(CATALOG_RESOURCE_VERSIONS.VERSION, version.version())
                .set(CATALOG_RESOURCE_VERSIONS.STATUS, version.status().name())
                .set(CATALOG_RESOURCE_VERSIONS.SUMMARY, version.summary())
                .set(CATALOG_RESOURCE_VERSIONS.CONFIG_DIGEST, version.configDigest())
                .set(CATALOG_RESOURCE_VERSIONS.CREATED_AT, JooqTimeSupport.toOffsetDateTime(version.createdAt()))
                .set(CATALOG_RESOURCE_VERSIONS.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(version.publishedAt()))
                .set(CATALOG_RESOURCE_VERSIONS.CONFIGURATION, configurationJsonb(version.configuration()))
                .execute();
        }
    }

    @Override
    public void deleteResourceVersions(String resourceId) {
        dsl.deleteFrom(CATALOG_RESOURCE_VERSIONS)
            .where(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID.eq(resourceId))
            .execute();
    }

    @Override
    public void replaceAssistantReleases(String assistantId, List<AssistantReleaseDto> releases) {
        deleteAssistantReleases(assistantId);
        for (AssistantReleaseDto release : releases) {
            dsl.insertInto(CATALOG_ASSISTANT_RELEASES)
                .set(CATALOG_ASSISTANT_RELEASES.ID, release.id())
                .set(CATALOG_ASSISTANT_RELEASES.ASSISTANT_ID, release.assistantId())
                .set(CATALOG_ASSISTANT_RELEASES.RELEASE_VERSION, release.releaseVersion())
                .set(CATALOG_ASSISTANT_RELEASES.STATUS, release.status().name())
                .set(CATALOG_ASSISTANT_RELEASES.CREATED_AT, JooqTimeSupport.toOffsetDateTime(release.createdAt()))
                .set(CATALOG_ASSISTANT_RELEASES.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(release.publishedAt()))
                .set(CATALOG_ASSISTANT_RELEASES.ASSISTANT_KNOWLEDGE_BINDING, jsonbOrNull(release.assistantKnowledgeBinding()))
                .set(CATALOG_ASSISTANT_RELEASES.DEFAULT_MODEL_BINDING, jsonbOrNull(release.defaultModelBinding()))
                .set(CATALOG_ASSISTANT_RELEASES.PRIVACY_MODEL_BINDING, jsonbOrNull(release.privacyModelBinding()))
                .set(CATALOG_ASSISTANT_RELEASES.PRIVACY_MAPPING_ENABLED, release.privacyMappingEnabled())
                .set(CATALOG_ASSISTANT_RELEASES.RESOURCES, releaseResourcesJsonb(release.resources()))
                .set(CATALOG_ASSISTANT_RELEASES.AGENTS, jsonbSupport.toJsonb(safe(release.agents())))
                .set(CATALOG_ASSISTANT_RELEASES.PLAYBOOKS, jsonbSupport.toJsonb(safe(release.playbooks())))
                .set(CATALOG_ASSISTANT_RELEASES.PRIMARY_AGENT_ID, release.primaryAgentId())
                .set(CATALOG_ASSISTANT_RELEASES.OWNER_POLICY, jsonbSupport.toJsonb(release.ownerPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.SESSION_POLICY, jsonbSupport.toJsonb(release.sessionPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.REPLY_POLICY, jsonbSupport.toJsonb(release.replyPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.PLAYBOOK_POLICY, jsonbSupport.toJsonb(release.playbookPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.MODEL_POLICY, jsonbSupport.toJsonb(release.modelPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.KNOWLEDGE_ACCESS_POLICY, jsonbSupport.toJsonb(release.knowledgeAccessPolicy()))
                .set(CATALOG_ASSISTANT_RELEASES.MEMORY_POLICY, jsonbSupport.toJsonb(release.memoryPolicy()))
                .execute();
        }
    }

    @Override
    public void deleteAssistantReleases(String assistantId) {
        dsl.deleteFrom(CATALOG_ASSISTANT_RELEASES)
            .where(CATALOG_ASSISTANT_RELEASES.ASSISTANT_ID.eq(assistantId))
            .execute();
    }

    @Override
    public List<ResourceBindingRef> findResourceBindings(String resourceId) {
        return dsl.selectFrom(CATALOG_REF_RESOURCE_BINDING)
            .where(CATALOG_REF_RESOURCE_BINDING.RESOURCE_ID.eq(resourceId))
            .fetch(record -> new ResourceBindingRef(
                record.getSourceType(),
                record.getSourceId(),
                record.getResourceId(),
                record.getBindingKind()
            ));
    }

    @Override
    public List<ReleaseResourceRef> findReleaseResourceRefs(String resourceId) {
        return dsl.selectFrom(CATALOG_REF_RELEASE_RESOURCE)
            .where(CATALOG_REF_RELEASE_RESOURCE.RESOURCE_ID.eq(resourceId))
            .fetch(record -> new ReleaseResourceRef(
                record.getReleaseId(),
                record.getAssistantId(),
                record.getResourceId(),
                record.getResourceVersionId(),
                record.getResourceVersion()
            ));
    }

    @Override
    public List<KnowledgeBindingRef> findKnowledgeBindings(String knowledgeBaseId) {
        return dsl.selectFrom(CATALOG_REF_KNOWLEDGE_BINDING)
            .where(CATALOG_REF_KNOWLEDGE_BINDING.KNOWLEDGE_BASE_ID.eq(knowledgeBaseId))
            .fetch(record -> new KnowledgeBindingRef(
                record.getSourceType(),
                record.getSourceId(),
                record.getKnowledgeBaseId(),
                record.getBindingKind()
            ));
    }

    @Override
    public List<ReleaseKnowledgeRef> findReleaseKnowledgeRefs(String knowledgeBaseId) {
        return dsl.selectFrom(CATALOG_REF_RELEASE_KNOWLEDGE)
            .where(CATALOG_REF_RELEASE_KNOWLEDGE.KNOWLEDGE_BASE_ID.eq(knowledgeBaseId))
            .fetch(record -> new ReleaseKnowledgeRef(
                record.getReleaseId(),
                record.getAssistantId(),
                record.getKnowledgeBaseId(),
                record.getKnowledgeReleaseId()
            ));
    }

    @Override
    public List<ResourceBindingRef> findAllResourceBindings() {
        return dsl.selectFrom(CATALOG_REF_RESOURCE_BINDING)
            .fetch(record -> new ResourceBindingRef(
                record.getSourceType(),
                record.getSourceId(),
                record.getResourceId(),
                record.getBindingKind()
            ));
    }

    @Override
    public List<ReleaseResourceRef> findAllReleaseResourceRefs() {
        return dsl.selectFrom(CATALOG_REF_RELEASE_RESOURCE)
            .fetch(record -> new ReleaseResourceRef(
                record.getReleaseId(),
                record.getAssistantId(),
                record.getResourceId(),
                record.getResourceVersionId(),
                record.getResourceVersion()
            ));
    }

    private void refreshDerivedReferenceTables() {
        replaceResourceBindings(listAssistants(), listAgents());
        replaceKnowledgeBindings(listAssistants(), listAgents());
        replaceReleaseResources(listAssistants().stream().collect(java.util.stream.Collectors.toMap(
            AssistantDto::id,
            assistant -> listAssistantReleases(assistant.id()),
            (left, __) -> left,
            java.util.LinkedHashMap::new
        )));
        replaceReleaseKnowledge(listAssistants().stream().collect(java.util.stream.Collectors.toMap(
            AssistantDto::id,
            assistant -> listAssistantReleases(assistant.id()),
            (left, __) -> left,
            java.util.LinkedHashMap::new
        )));
    }

    private void replaceResourceBindings(List<AssistantDto> assistants, List<AgentDto> agents) {
        dsl.deleteFrom(CATALOG_REF_RESOURCE_BINDING).execute();
        for (AssistantDto assistant : assistants) {
            if (assistant.modelPolicy() != null && assistant.modelPolicy().defaultModelResourceId() != null) {
                insertResourceBinding("ASSISTANT", assistant.id(), assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");
            }
            if (assistant.privacyModelResourceId() != null) {
                insertResourceBinding("ASSISTANT", assistant.id(), assistant.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL");
            }
        }
        for (AgentDto agent : agents) {
            AgentExecutionPolicyDto policy = agent.executionPolicy();
            if (policy == null) {
                continue;
            }
            if (policy.modelResourceId() != null) {
                insertResourceBinding("AGENT", agent.id(), policy.modelResourceId(), "AGENT_OVERRIDE_MODEL");
            }
            if (policy.privacyModelResourceId() != null) {
                insertResourceBinding("AGENT", agent.id(), policy.privacyModelResourceId(), "AGENT_PRIVACY_MODEL_OVERRIDE");
            }
            for (String skillId : safe(policy.skillResourceIds())) {
                insertResourceBinding("AGENT", agent.id(), skillId, "AGENT_SKILL_ENABLED");
            }
            for (String toolId : safe(policy.toolResourceIds())) {
                insertResourceBinding("AGENT", agent.id(), toolId, "AGENT_TOOL_ENABLED");
            }
        }
    }

    private void replaceKnowledgeBindings(List<AssistantDto> assistants, List<AgentDto> agents) {
        dsl.deleteFrom(CATALOG_REF_KNOWLEDGE_BINDING).execute();
        for (AssistantDto assistant : assistants) {
            if (assistant.knowledgeAccessPolicy() != null && assistant.knowledgeAccessPolicy().enabled() && assistant.knowledgeAccessPolicy().knowledgeBaseId() != null) {
                insertKnowledgeBinding("ASSISTANT", assistant.id(), assistant.knowledgeAccessPolicy().knowledgeBaseId(), "ASSISTANT_DEFAULT_KNOWLEDGE_BASE");
            }
        }
        for (AgentDto agent : agents) {
            AgentExecutionPolicyDto policy = agent.executionPolicy();
            if (policy == null) {
                continue;
            }
            if (policy.knowledgeEnabled() && !policy.inheritAssistantKnowledge() && policy.knowledgeBaseId() != null) {
                insertKnowledgeBinding("AGENT", agent.id(), policy.knowledgeBaseId(), "AGENT_OVERRIDE_KNOWLEDGE_BASE");
            }
        }
    }

    private void replaceReleaseResources(Map<String, List<AssistantReleaseDto>> assistantReleases) {
        dsl.deleteFrom(CATALOG_REF_RELEASE_RESOURCE).execute();
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : assistantReleases.entrySet()) {
            String assistantId = entry.getKey();
            for (AssistantReleaseDto release : entry.getValue()) {
                for (AssistantReleaseResourceDto resource : safe(release.resources())) {
                    dsl.insertInto(CATALOG_REF_RELEASE_RESOURCE)
                        .set(CATALOG_REF_RELEASE_RESOURCE.RELEASE_ID, release.id())
                        .set(CATALOG_REF_RELEASE_RESOURCE.ASSISTANT_ID, assistantId)
                        .set(CATALOG_REF_RELEASE_RESOURCE.RESOURCE_ID, resource.resourceId())
                        .set(CATALOG_REF_RELEASE_RESOURCE.RESOURCE_VERSION_ID, resource.resourceVersionId())
                        .set(CATALOG_REF_RELEASE_RESOURCE.RESOURCE_VERSION, resource.resourceVersion())
                        .execute();
                }
            }
        }
    }

    private void replaceReleaseKnowledge(Map<String, List<AssistantReleaseDto>> assistantReleases) {
        dsl.deleteFrom(CATALOG_REF_RELEASE_KNOWLEDGE).execute();
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : assistantReleases.entrySet()) {
            String assistantId = entry.getKey();
            for (AssistantReleaseDto release : entry.getValue()) {
                insertReleaseKnowledgeIfPresent(release.id(), assistantId, release.assistantKnowledgeBinding());
                for (AssistantReleaseAgentDto agent : safe(release.agents())) {
                    insertReleaseKnowledgeIfPresent(release.id(), assistantId, agent.knowledgeBinding());
                }
            }
        }
    }

    private void insertResourceBinding(String sourceType, String sourceId, String resourceId, String bindingKind) {
        dsl.insertInto(CATALOG_REF_RESOURCE_BINDING)
            .set(CATALOG_REF_RESOURCE_BINDING.SOURCE_TYPE, sourceType)
            .set(CATALOG_REF_RESOURCE_BINDING.SOURCE_ID, sourceId)
            .set(CATALOG_REF_RESOURCE_BINDING.RESOURCE_ID, resourceId)
            .set(CATALOG_REF_RESOURCE_BINDING.BINDING_KIND, bindingKind)
            .execute();
    }

    private void insertKnowledgeBinding(String sourceType, String sourceId, String knowledgeBaseId, String bindingKind) {
        dsl.insertInto(CATALOG_REF_KNOWLEDGE_BINDING)
            .set(CATALOG_REF_KNOWLEDGE_BINDING.SOURCE_TYPE, sourceType)
            .set(CATALOG_REF_KNOWLEDGE_BINDING.SOURCE_ID, sourceId)
            .set(CATALOG_REF_KNOWLEDGE_BINDING.KNOWLEDGE_BASE_ID, knowledgeBaseId)
            .set(CATALOG_REF_KNOWLEDGE_BINDING.BINDING_KIND, bindingKind)
            .execute();
    }

    private void insertReleaseKnowledgeIfPresent(String releaseId, String assistantId, KnowledgeBindingSnapshotDto binding) {
        if (binding == null || binding.knowledgeBaseId() == null) {
            return;
        }
        dsl.insertInto(CATALOG_REF_RELEASE_KNOWLEDGE)
            .set(CATALOG_REF_RELEASE_KNOWLEDGE.RELEASE_ID, releaseId)
            .set(CATALOG_REF_RELEASE_KNOWLEDGE.ASSISTANT_ID, assistantId)
            .set(CATALOG_REF_RELEASE_KNOWLEDGE.KNOWLEDGE_BASE_ID, binding.knowledgeBaseId())
            .set(CATALOG_REF_RELEASE_KNOWLEDGE.KNOWLEDGE_RELEASE_ID, binding.knowledgeReleaseId())
            .onConflictDoNothing()
            .execute();
    }

    private JSONB jsonbOrNull(Object value) {
        return value == null ? null : jsonbSupport.toJsonb(value);
    }

    private JSONB configurationJsonb(ResourceVersionConfigurationDto configuration) {
        return jsonbOrNull(storageConfiguration(configuration));
    }

    private JSONB releaseResourcesJsonb(List<AssistantReleaseResourceDto> resources) {
        return jsonbSupport.toJsonb(safe(resources).stream()
            .map(this::storageReleaseResource)
            .toList());
    }

    private Object storageReleaseResource(AssistantReleaseResourceDto resource) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("resourceId", resource.resourceId());
        value.put("resourceName", resource.resourceName());
        value.put("resourceType", resource.resourceType());
        value.put("resourceVersionId", resource.resourceVersionId());
        value.put("resourceVersion", resource.resourceVersion());
        value.put("boundAgents", safe(resource.boundAgents()));
        value.put("configuration", storageConfiguration(resource.configuration()));
        return value;
    }

    private Object storageConfiguration(ResourceVersionConfigurationDto configuration) {
        if (configuration == null) {
            return null;
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("type", configuration.type());
        value.put("tool", storageToolConfig(configuration.tool()));
        value.put("llmModel", configuration.llmModel());
        value.put("skill", configuration.skill());
        return value;
    }

    private Object storageToolConfig(ToolConfigDto tool) {
        if (tool == null) {
            return null;
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("operations", safe(tool.operations()));
        value.put("connector", storageToolConnector(tool.connector()));
        return value;
    }

    private Object storageToolConnector(ToolConnectorConfigDto connector) {
        if (connector == null) {
            return null;
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("connectorType", connector.connectorType());
        value.put("accountId", connector.accountId());
        value.put("accountSnapshot", storageToolConnectorAccountSnapshot(connector.accountSnapshot()));
        value.put("timeoutSeconds", connector.timeoutSeconds());
        value.put("retryPolicy", connector.retryPolicy());
        value.put("config", connector.config());
        value.put("operationMappings", connector.operationMappings());
        return value;
    }

    private Object storageToolConnectorAccountSnapshot(ToolConnectorAccountSnapshotDto accountSnapshot) {
        if (accountSnapshot == null) {
            return null;
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("accountId", accountSnapshot.accountId());
        value.put("hasExternalSecretRef", accountSnapshot.hasExternalSecretRef());
        value.put("runtimeSecretRef", accountSnapshot.runtimeSecretRef());
        return value;
    }

    private List<String> readStringList(JSONB value) {
        List<String> items = jsonbSupport.read(value, STRING_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<AgentDecisionAction> readActionList(JSONB value) {
        List<AgentDecisionAction> items = jsonbSupport.read(value, ACTION_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<PlaybookNodeDto> readPlaybookNodes(JSONB value) {
        List<PlaybookNodeDto> items = jsonbSupport.read(value, PLAYBOOK_NODE_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<PlaybookEdgeDto> readPlaybookEdges(JSONB value) {
        List<PlaybookEdgeDto> items = jsonbSupport.read(value, PLAYBOOK_EDGE_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<AssistantReleaseResourceDto> readAssistantReleaseResources(JSONB value) {
        List<AssistantReleaseResourceDto> items = jsonbSupport.read(value, ASSISTANT_RELEASE_RESOURCE_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<AssistantReleaseAgentDto> readAssistantReleaseAgents(JSONB value) {
        List<AssistantReleaseAgentDto> items = jsonbSupport.read(value, ASSISTANT_RELEASE_AGENT_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private List<PlaybookDto> readPlaybookSnapshots(JSONB value) {
        List<PlaybookDto> items = jsonbSupport.read(value, PLAYBOOK_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
