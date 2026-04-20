package com.lynxus.platform.session;

import static com.lynxus.platform.session.SessionRuntimeDtos.CreateSessionRequest;
import static com.lynxus.platform.session.SessionRuntimeDtos.SendSessionMessageRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionUserMessageUpdateResult;
import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.shared.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionRuntimeServiceTest {
    @Test
    void createSession_reusesExistingActiveSessionForSameConversation() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        CatalogDtos.AssistantDto assistant = assistant("ast-1");
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-existing", "ACTIVE", null);

        when(catalogService.getAssistant("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.findSession("session-existing")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-existing")).thenReturn(true);
        when(gateway.submitUserMessage(any(), any())).thenReturn(
            new SessionUserMessageUpdateResult(SessionMessageDeliveryStatus.ACCEPTED, "session-existing", null)
        );

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", "你好")
        );

        assertEquals("session-existing", result.id());
        verify(gateway, never()).start(any());
    }

    @Test
    void createSession_marksClosedSessionEndedAndStartsNewWorkflow() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        CatalogDtos.AssistantDto assistant = assistant("ast-1");
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-closed", "IDLE", null);

        when(catalogService.getAssistant("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());
        when(gateway.isWorkflowOpen("session-closed")).thenReturn(false);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", "")
        );

        assertNotEquals("session-closed", result.id());
        verify(repository).saveSession(argThat(session -> session.id().equals("session-closed") && "ENDED".equals(session.status())));
        verify(gateway).start(any());
    }

    @Test
    void sendMessage_rejectsClosedSessionAndPersistsEndedStatus() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-closed", "IDLE", null);

        when(repository.findSession("session-closed")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-closed")).thenReturn(false);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendMessage("session-closed", new SendSessionMessageRequest("customer-1", "你好"))
        );

        assertEquals("session has ended", error.getMessage());
        verify(repository).saveSession(argThat(session -> session.id().equals("session-closed") && "ENDED".equals(session.status())));
        verify(gateway, never()).submitUserMessage(any(), any());
    }

    @Test
    void createSession_shouldInjectFrozenReleaseModelSkillAndToolDescriptorsIntoWorkflowStartRequest() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        CatalogDtos.AssistantDto assistant = assistantWithFrozenReleaseDescriptors("ast-1");

        when(catalogService.getAssistant("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());
        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", "")
        );

        assertEquals("1.0.0", result.assistantReleaseVersion());
        verify(gateway).start(argThat(startRequest ->
            startRequest.assistant().primaryAgentId().equals("agent-release")
                && startRequest.agents().size() == 1
                && startRequest.agents().getFirst().agentId().equals("agent-release")
                && "release prompt".equals(startRequest.agents().getFirst().systemPrompt())
                && startRequest.agents().getFirst().model() != null
                && "rv-model-1".equals(startRequest.agents().getFirst().model().resourceVersionId())
                && startRequest.agents().getFirst().knowledgeBinding() != null
                && "snapshot-kb-1".equals(startRequest.agents().getFirst().knowledgeBinding().snapshotId())
                && startRequest.agents().getFirst().skills().size() == 1
                && "退款技能".equals(startRequest.agents().getFirst().skills().getFirst().skillName())
                && startRequest.agents().getFirst().tools().size() == 1
                && "HTTP".equals(startRequest.agents().getFirst().tools().getFirst().providerType())
                && "rv-tool-1".equals(startRequest.agents().getFirst().tools().getFirst().resourceVersionId())
        ));
    }

    @Test
    void humanResume_shouldRejectPlaybookRunThatDoesNotBelongToSession() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "ACTIVE", null);

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of(playbookRun("run-2", "session-2")));

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.humanResume("session-1", new SessionRuntimeDtos.HumanResumeRequest("run-1", Map.of()))
        );

        assertEquals("playbook run does not belong to session", error.getMessage());
        verify(gateway, never()).humanResume(eq("session-1"), any());
    }

    @Test
    void externalCallback_shouldRejectPlaybookRunThatDoesNotBelongToSession() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "ACTIVE", null);

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of(playbookRun("run-2", "session-2")));

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.externalCallback("session-1", new SessionRuntimeDtos.ExternalCallbackRequest("run-1", Map.of()))
        );

        assertEquals("playbook run does not belong to session", error.getMessage());
        verify(gateway, never()).externalCallback(eq("session-1"), any());
    }

    private static CatalogDtos.AssistantDto assistant(String assistantId) {
        Instant now = Instant.now();
        CatalogDtos.AssistantReleaseDto release = new CatalogDtos.AssistantReleaseDto(
            "rel-1",
            assistantId,
            "1.0.0",
            VersionStatus.PUBLISHED,
            now,
            now,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            "agent-1",
            new CatalogDtos.AssistantOwnerPolicyDto(3),
            new CatalogDtos.AssistantSessionPolicyDto("PT30M", "P7D", 20_000),
            new CatalogDtos.AssistantReplyPolicyDto(true),
            new CatalogDtos.AssistantPlaybookPolicyDto(null, null),
            new CatalogDtos.AssistantModelPolicyDto("model-1"),
            new CatalogDtos.KnowledgeAccessPolicyDto(false, null),
            new CatalogDtos.MemoryPolicyDto(true, 8)
        );
        return new CatalogDtos.AssistantDto(
            assistantId,
            "scn-1",
            "Assistant",
            "desc",
            new CatalogDtos.VersionDto("1.0.0", VersionStatus.PUBLISHED, now),
            List.of(),
            List.of(),
            release,
            List.of(release),
            "agent-1",
            new CatalogDtos.AssistantOwnerPolicyDto(3),
            new CatalogDtos.AssistantSessionPolicyDto("PT30M", "P7D", 20_000),
            new CatalogDtos.AssistantReplyPolicyDto(true),
            new CatalogDtos.AssistantPlaybookPolicyDto(null, null),
            new CatalogDtos.AssistantModelPolicyDto("model-1"),
            new CatalogDtos.KnowledgeAccessPolicyDto(false, null),
            new CatalogDtos.MemoryPolicyDto(true, 8)
        );
    }

    private static CatalogDtos.AssistantDto assistantWithFrozenReleaseDescriptors(String assistantId) {
        Instant now = Instant.now();
        CatalogDtos.AssistantReleaseDto release = new CatalogDtos.AssistantReleaseDto(
            "rel-1",
            assistantId,
            "1.0.0",
            VersionStatus.PUBLISHED,
            now,
            now,
            null,
            new CatalogDtos.DefaultModelBindingDto("model-1", "主模型", "rv-model-1", "1.0.0", "OPENAI_COMPATIBLE", "gpt-test"),
            List.of(
                new CatalogDtos.AssistantReleaseResourceDto(
                    "model-1",
                    "主模型",
                    com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.LLM_MODEL,
                    "rv-model-1",
                    "1.0.0",
                    List.of("ASSISTANT_DEFAULT_MODEL"),
                    new CatalogDtos.ResourceVersionConfigurationDto(
                        com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.LLM_MODEL,
                        null,
                        new CatalogDtos.LlmModelConfigDto("OPENAI_COMPATIBLE", "gpt-test", "https://runtime.example", "TEST_OPENAI_COMPATIBLE_API_KEY", 0, 512),
                        null
                    )
                ),
                new CatalogDtos.AssistantReleaseResourceDto(
                    "skill-1",
                    "退款技能资源",
                    com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.SKILL,
                    "rv-skill-1",
                    "1.0.0",
                    List.of("Release Agent"),
                    new CatalogDtos.ResourceVersionConfigurationDto(
                        com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.SKILL,
                        null,
                        null,
                        new CatalogDtos.SkillConfigDto("退款技能", "用于退款语义约束", "先确认订单状态，再决定是否退款。")
                    )
                ),
                new CatalogDtos.AssistantReleaseResourceDto(
                    "tool-1",
                    "工单工具资源",
                    com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.TOOL,
                    "rv-tool-1",
                    "1.0.0",
                    List.of("Release Agent"),
                    new CatalogDtos.ResourceVersionConfigurationDto(
                        com.lynxus.contracts.runtime.WorkflowContracts.ResourceType.TOOL,
                        new CatalogDtos.ToolConfigDto(
                            List.of(new CatalogDtos.ToolOperationDto("create_ticket", "创建工单", "{\"type\":\"object\"}", "{\"type\":\"object\"}")),
                            com.lynxus.contracts.runtime.WorkflowContracts.ToolProviderType.HTTP,
                            "SERVICE_ACCOUNT",
                            15,
                            "NONE",
                            new CatalogDtos.HttpToolProviderConfigDto("https://tool.example/invoke", "POST"),
                            null
                        ),
                        null,
                        null
                    )
                )
            ),
            List.of(
                new CatalogDtos.AssistantReleaseAgentDto(
                    "agent-release",
                    "Release Agent",
                    "support",
                    "release responsibilities",
                    new CatalogDtos.AgentExecutionPolicyDto(true, null, "release prompt", false, false, null, 8, List.of("skill-1"), List.of("tool-1")),
                    new CatalogDtos.KnowledgeBindingSnapshotDto("kb-1", "退款知识库", "kr-1", "1.0.0", "snapshot-kb-1", 5, "HYBRID", 0.1),
                    true,
                    List.of(com.lynxus.contracts.session.SessionContracts.AgentDecisionAction.REPLY),
                    List.of(),
                    List.of(),
                    List.of("rv-skill-1"),
                    List.of("rv-tool-1")
                )
            ),
            List.of(),
            "agent-release",
            new CatalogDtos.AssistantOwnerPolicyDto(3),
            new CatalogDtos.AssistantSessionPolicyDto("PT30M", "P7D", 20_000),
            new CatalogDtos.AssistantReplyPolicyDto(true),
            new CatalogDtos.AssistantPlaybookPolicyDto(null, null),
            new CatalogDtos.AssistantModelPolicyDto("model-1"),
            new CatalogDtos.KnowledgeAccessPolicyDto(false, null),
            new CatalogDtos.MemoryPolicyDto(true, 8)
        );
        return new CatalogDtos.AssistantDto(
            assistantId,
            "scn-1",
            "Assistant",
            "desc",
            new CatalogDtos.VersionDto("1.0.0", VersionStatus.PUBLISHED, now),
            List.of(
                new CatalogDtos.AgentDto(
                    "agent-draft",
                    assistantId,
                    "Draft Agent",
                    "draft",
                    "draft responsibilities",
                    new CatalogDtos.AgentExecutionPolicyDto(true, null, "draft prompt", false, false, null, 4, List.of(), List.of()),
                    true,
                    List.of(com.lynxus.contracts.session.SessionContracts.AgentDecisionAction.NO_REPLY),
                    List.of(),
                    List.of()
                )
            ),
            List.of(),
            release,
            List.of(release),
            "agent-draft",
            new CatalogDtos.AssistantOwnerPolicyDto(1),
            new CatalogDtos.AssistantSessionPolicyDto("PT5M", "P1D", 100),
            new CatalogDtos.AssistantReplyPolicyDto(false),
            new CatalogDtos.AssistantPlaybookPolicyDto("draft-timeout", "draft-retry"),
            new CatalogDtos.AssistantModelPolicyDto("draft-model"),
            new CatalogDtos.KnowledgeAccessPolicyDto(false, null),
            new CatalogDtos.MemoryPolicyDto(true, 2)
        );
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto session(String sessionId, String status, Instant endedAt) {
        Instant now = Instant.now();
        return new SessionRuntimeDtos.SessionRuntimeSessionDto(
            sessionId,
            "scn-1",
            "title",
            "customer-1",
            "ast-1",
            "Assistant",
            "1.0.0",
            status,
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            now.plus(Duration.ofMinutes(30)),
            now,
            now,
            endedAt,
            0
        );
    }

    private static PlaybookRun playbookRun(String runId, String sessionId) {
        Instant now = Instant.now();
        return new PlaybookRun(
            runId,
            sessionId,
            "event-1",
            "playbook-1",
            "agent-1",
            PlaybookRunStatus.WAITING,
            Map.of(),
            Map.of(),
            null,
            now,
            now,
            "human_task:step-1"
        );
    }
}
