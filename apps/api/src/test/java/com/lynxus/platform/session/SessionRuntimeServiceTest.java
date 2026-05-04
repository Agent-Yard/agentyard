package com.lynxus.platform.session;

import com.lynxus.platform.auth.AuthModels;
import com.lynxus.platform.auth.CurrentUserResolver;
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
import static org.mockito.Mockito.doThrow;

import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.findSession("session-existing")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-existing")).thenReturn(true);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", textMessageInput("你好"))
        );

        assertEquals("session-existing", result.id());
        verify(gateway, never()).start(any());
    }

    @Test
    void createSession_reusesExistingActiveSessionForImageOnlyOpeningMessage() {
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.findSession("session-existing")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-existing")).thenReturn(true);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", imageMessageInput("https://example.com/refund.png"))
        );

        assertEquals("session-existing", result.id());
        verify(gateway, never()).start(any());
        verify(gateway).submitUserMessage(eq("session-existing"), any());
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(existing));
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());
        when(gateway.isWorkflowOpen("session-closed")).thenReturn(false);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", textMessageInput(""))
        );

        assertNotEquals("session-closed", result.id());
        verify(repository).saveSession(argThat(session -> session.id().equals("session-closed") && "ENDED".equals(session.status())));
        verify(gateway).start(any());
    }

    @Test
    void createSession_dispatchesImageOnlyOpeningMessageAfterStartingNewWorkflow() {
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());
        when(gateway.isWorkflowOpen(any())).thenReturn(true);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", imageMessageInput("https://example.com/refund.png"))
        );

        assertNotEquals("session-closed", result.id());
        verify(gateway).start(any());
        verify(gateway).submitUserMessage(any(), argThat(message ->
            message.message() != null
                && message.message().blocks().size() == 1
                && message.message().blocks().getFirst() instanceof Map<?, ?> block
                && "IMAGE".equals(block.get("type"))
        ));
    }

    @Test
    void sendMessage_marksClosedSessionEndedAndRollsOverToNewSession() {
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findSession("session-closed")).thenReturn(java.util.Optional.of(existing));
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(argThat(id -> !"session-closed".equals(id)))).thenReturn(java.util.Optional.empty());
        when(gateway.isWorkflowOpen(any())).thenAnswer(invocation -> !"session-closed".equals(invocation.getArgument(0)));

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.sendMessage(
            "session-closed",
            new SendSessionMessageRequest("customer-1", textMessageInput("你好"))
        );

        assertNotEquals("session-closed", result.id());
        verify(repository).saveSession(argThat(session -> session.id().equals("session-closed") && "ENDED".equals(session.status())));
        verify(gateway).start(any());
        verify(gateway).submitUserMessage(argThat(id -> !"session-closed".equals(id)), any());
    }

    @Test
    void sendMessage_rollsOverEndedSessionIntoNewSession() {
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
        SessionRuntimeDtos.SessionRuntimeSessionDto ended = session("session-ended", "ENDED", null);

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findSession("session-ended")).thenReturn(java.util.Optional.of(ended));
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(argThat(id -> !"session-ended".equals(id)))).thenReturn(java.util.Optional.empty());
        when(gateway.isWorkflowOpen(any())).thenAnswer(invocation -> !"session-ended".equals(invocation.getArgument(0)));

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.sendMessage(
            "session-ended",
            new SendSessionMessageRequest("customer-1", textMessageInput("继续处理"))
        );

        assertNotEquals("session-ended", result.id());
        verify(gateway).start(any());
        verify(gateway).submitUserMessage(argThat(id -> !"session-ended".equals(id)), any());
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

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());
        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.createSession(
            new CreateSessionRequest("ast-1", "customer-1", textMessageInput(""))
        );

        assertEquals("1.0.0", result.assistantReleaseVersion());
        verify(gateway).start(argThat(startRequest ->
            startRequest.assistant().primaryAgentId().equals("agent-release")
                && startRequest.agents().size() == 1
                && startRequest.agents().getFirst().agentId().equals("agent-release")
                && "release prompt".equals(startRequest.agents().getFirst().systemPrompt())
                && startRequest.agents().getFirst().model() != null
                && "rv-model-1".equals(startRequest.agents().getFirst().model().resourceVersionId())
                && Boolean.TRUE.equals(startRequest.agents().getFirst().model().enableThinking())
                && "high".equals(startRequest.agents().getFirst().model().reasoningEffort())
                && startRequest.agents().getFirst().knowledgeBinding() != null
                && "snapshot-kb-1".equals(startRequest.agents().getFirst().knowledgeBinding().snapshotId())
                && startRequest.agents().getFirst().skills().size() == 1
                && "退款技能".equals(startRequest.agents().getFirst().skills().getFirst().skillName())
                && startRequest.agents().getFirst().tools().size() == 1
                && "simple-http".equals(startRequest.agents().getFirst().tools().getFirst().connector().connectorType())
                && startRequest.agents().getFirst().tools().getFirst().connector().accountSnapshot() != null
                && "integration-account-1".equals(startRequest.agents().getFirst().tools().getFirst().connector().accountSnapshot().accountId())
                && "vault://tool-secret".equals(startRequest.agents().getFirst().tools().getFirst().connector().accountSnapshot().externalSecretRef())
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().mode()
                    == com.lynxus.contracts.session.SessionContracts.ToolConnectorRetryMode.EXPONENTIAL
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().maxAttempts() == 3
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().initialDelayMs() == 100
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().maxDelayMs() == 1000
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().backoffMultiplier() == 2.0
                && startRequest.agents().getFirst().tools().getFirst().connector().retryPolicy().retryableCategories()
                    .equals(List.of("REMOTE_TIMEOUT", "REMOTE_UNAVAILABLE", "REMOTE_RATE_LIMITED", "UNKNOWN"))
                && "rv-tool-1".equals(startRequest.agents().getFirst().tools().getFirst().resourceVersionId())
        ));
    }

    @Test
    void createSession_shouldUseLlmReasoningSettingsWhenAssistantPolicyDoesNotOverride() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        CatalogDtos.AssistantDto assistant = assistantWithFrozenReleaseDescriptors(
            "ast-1",
            "EXPONENTIAL_BACKOFF",
            new CatalogDtos.AssistantModelPolicyDto("model-1")
        );

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());

        service.createSession(new CreateSessionRequest("ast-1", "customer-1", textMessageInput("")));

        verify(gateway).start(argThat(startRequest ->
            startRequest.agents().getFirst().model() != null
                && Boolean.FALSE.equals(startRequest.agents().getFirst().model().enableThinking())
                && "low".equals(startRequest.agents().getFirst().model().reasoningEffort())
        ));
    }

    @Test
    void createSession_shouldRejectUnknownToolConnectorRetryPolicyPresetDuringReleaseMapping() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        CatalogDtos.AssistantDto assistant = assistantWithFrozenReleaseDescriptors("ast-1", "LINEAR");

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant);
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenReturn(java.util.Optional.empty());

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.createSession(new CreateSessionRequest("ast-1", "customer-1", textMessageInput("")))
        );

        assertEquals("unsupported tool connector retryPolicy preset: LINEAR", error.getMessage());
        verify(gateway, never()).start(any());
    }

    @Test
    void humanResume_shouldRejectPlaybookRunThatDoesNotBelongToSession() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        CurrentUserResolver currentUserResolver = currentUserResolver("user-1");
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService(),
            new org.springframework.data.redis.core.StringRedisTemplate(),
            new tools.jackson.databind.ObjectMapper(),
            new com.lynxus.shared.redis.RedisKeyspace(),
            new com.lynxus.platform.shared.redis.RedisIdempotencyService(
                new org.springframework.data.redis.core.StringRedisTemplate(),
                new com.lynxus.shared.redis.RedisJsonCodec(new tools.jackson.databind.ObjectMapper()),
                new com.lynxus.platform.shared.redis.RedisSharedStateProperties(null, null, null, null, null, 0, null),
                new com.lynxus.shared.redis.RedisSharedStateMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            ),
            null,
            new ExternalCallbackIdempotencyKeyFactory(new tools.jackson.databind.ObjectMapper()),
            currentUserResolver
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

    @Test
    void humanOperatorReply_shouldUseCurrentUserIdAsOperatorId() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        CurrentUserResolver currentUserResolver = currentUserResolver("user-operator");
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService(),
            new org.springframework.data.redis.core.StringRedisTemplate(),
            new tools.jackson.databind.ObjectMapper(),
            new com.lynxus.shared.redis.RedisKeyspace(),
            new com.lynxus.platform.shared.redis.RedisIdempotencyService(
                new org.springframework.data.redis.core.StringRedisTemplate(),
                new com.lynxus.shared.redis.RedisJsonCodec(new tools.jackson.databind.ObjectMapper()),
                new com.lynxus.platform.shared.redis.RedisSharedStateProperties(null, null, null, null, null, 0, null),
                new com.lynxus.shared.redis.RedisSharedStateMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            ),
            null,
            new ExternalCallbackIdempotencyKeyFactory(new tools.jackson.databind.ObjectMapper()),
            currentUserResolver
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "ACTIVE", null);
        SessionRuntimeDtos.SessionRuntimeSessionDto updated = new SessionRuntimeDtos.SessionRuntimeSessionDto(
            existing.id(),
            existing.scenarioId(),
            existing.title(),
            existing.customerId(),
            existing.assistantId(),
            existing.assistantName(),
            existing.assistantReleaseVersion(),
            existing.status(),
            existing.primaryAgentId(),
            existing.currentOwnerAgentId(),
            existing.activePlaybookRunId(),
            existing.agentTurnActive(),
            existing.sessionHumanHandoffActive(),
            existing.pendingOwnerReevaluation(),
            existing.draining(),
            existing.sharedState(),
            existing.idleDeadline(),
            existing.createdAt(),
            existing.updatedAt().plusSeconds(1),
            existing.endedAt(),
            existing.latestMessageSequence() + 1,
            existing.latestEventSequence()
        );

        when(repository.findSession("session-1"))
            .thenReturn(java.util.Optional.of(existing))
            .thenReturn(java.util.Optional.of(existing))
            .thenReturn(java.util.Optional.of(updated));
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.humanOperatorReply(
            "session-1",
            new SessionRuntimeDtos.HumanOperatorReplyRequest(textMessageInput("人工回复"), Map.of())
        );

        assertEquals(updated, result);
        verify(gateway).humanOperatorReply(eq("session-1"), argThat(signal ->
            "user-operator".equals(signal.operatorId()) && signal.message() != null
        ));
    }

    @Test
    void sendMessage_shouldReturnAfterWorkflowAcceptsMessageWithoutWaitingForSessionChange() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "IDLE", null);

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);

        SessionRuntimeDtos.SessionRuntimeSessionDto result = service.sendMessage(
            "session-1",
            new SendSessionMessageRequest("customer-1", textMessageInput("你好"))
        );

        assertEquals(existing, result);
        verify(gateway).submitUserMessage(eq("session-1"), any());
    }

    @Test
    void sendMessage_shouldPropagateConflictWhenWorkflowUpdateTimesOutButWorkflowIsStillOpen() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "IDLE", null);

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);
        doThrow(new ConflictException("session message acceptance timed out"))
            .when(gateway)
            .submitUserMessage(any(), any());

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendMessage("session-1", new SendSessionMessageRequest("customer-1", textMessageInput("你好")))
        );

        assertEquals("session message acceptance timed out", error.getMessage());
    }

    @Test
    void sendMessage_shouldPropagateRejectedBlankMessageReason() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto existing = session("session-1", "IDLE", null);

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(existing));
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendMessage("session-1", new SendSessionMessageRequest("customer-1", textMessageInput("")))
        );

        assertEquals("message content required", error.getMessage());
    }

    @Test
    void channelInboundMessage_shouldReturnAfterWorkflowAcceptsMessageWithoutWaitingForTurnCompletion() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        com.lynxus.platform.shared.redis.RedisIdempotencyService idempotencyService = mock(
            com.lynxus.platform.shared.redis.RedisIdempotencyService.class
        );
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService(),
            new org.springframework.data.redis.core.StringRedisTemplate(),
            new tools.jackson.databind.ObjectMapper(),
            new com.lynxus.shared.redis.RedisKeyspace(),
            idempotencyService,
            null,
            new ExternalCallbackIdempotencyKeyFactory(new tools.jackson.databind.ObjectMapper()),
            currentUserResolver("operator-1")
        );
        ChannelInboundSessionMessageRequest request = new ChannelInboundSessionMessageRequest(
            "channel-profile-1",
            "chat-1",
            "msg-1",
            "channel-inbound-event-1",
            "dedup-1",
            "ast-1",
            "customer-1",
            null,
            textMessageInput("hello")
        );

        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.empty());
        when(repository.findSession(any())).thenAnswer(invocation -> java.util.Optional.of(
            session(invocation.getArgument(0), "IDLE", null)
        ));
        when(gateway.isWorkflowOpen(any())).thenReturn(true);
        when(idempotencyService.execute(any(), eq(ChannelInboundSessionMessageResponse.class), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<ChannelInboundSessionMessageResponse> action = invocation.getArgument(2);
            return action.get();
        });

        ChannelInboundSessionMessageResponse response = service.channelInboundMessage(request, "dedup-1");

        assertEquals("IDLE", response.status());
        verify(gateway).start(any());
        verify(gateway).submitUserMessage(eq(response.sessionId()), argThat(message ->
            message.message().metadata().get("source").equals("channel-inbound")
                && message.message().metadata().get("channelProfileId").equals("channel-profile-1")
                && message.message().metadata().get("externalConversationId").equals("chat-1")
        ));
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

    private static CurrentUserResolver currentUserResolver(String userId) {
        return () -> new AuthModels.PlatformUser(
            userId,
            "operator",
            "Operator",
            "operator@example.com",
            AuthModels.AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            AuthModels.UserStatus.ACTIVE,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            List.of(AuthModels.Role.DEVELOPER)
        );
    }

    private static CatalogDtos.AssistantDto assistantWithFrozenReleaseDescriptors(String assistantId) {
        return assistantWithFrozenReleaseDescriptors(assistantId, "EXPONENTIAL_BACKOFF");
    }

    private static CatalogDtos.AssistantDto assistantWithFrozenReleaseDescriptors(String assistantId, String retryPolicy) {
        return assistantWithFrozenReleaseDescriptors(
            assistantId,
            retryPolicy,
            new CatalogDtos.AssistantModelPolicyDto("model-1", true, "high")
        );
    }

    private static CatalogDtos.AssistantDto assistantWithFrozenReleaseDescriptors(
        String assistantId,
        String retryPolicy,
        CatalogDtos.AssistantModelPolicyDto modelPolicy
    ) {
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
                        new CatalogDtos.LlmModelConfigDto(
                            "OPENAI_COMPATIBLE",
                            "gpt-test",
                            "https://runtime.example",
                            "TEST_OPENAI_COMPATIBLE_API_KEY",
                            0,
                            512,
                            false,
                            false,
                            "low"
                        ),
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
                            new CatalogDtos.ToolConnectorConfigDto(
                                "simple-http",
                                null,
                                new CatalogDtos.ToolConnectorAccountSnapshotDto("integration-account-1", "vault://tool-secret"),
                                15,
                                retryPolicy,
                                Map.of("baseUrl", "https://tool.example"),
                                Map.of("create_ticket", Map.of("method", "POST", "path", "/invoke", "requestPlacement", "JSON_BODY"))
                            )
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
            modelPolicy,
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
                    List.of(com.lynxus.contracts.session.SessionContracts.AgentDecisionAction.NO_OP),
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
            0L,
            0L
        );
    }

    private static SessionMessageInput textMessageInput(String text) {
        return new SessionMessageInput(
            text == null || text.isBlank() ? List.of() : List.of(Map.of("type", "TEXT", "text", text)),
            Map.of()
        );
    }

    private static SessionMessageInput imageMessageInput(String url) {
        return new SessionMessageInput(
            List.of(Map.of("type", "IMAGE", "url", url)),
            Map.of()
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
