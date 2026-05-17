package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanOperatorReplySignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnMessage;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.ExistingSessionImportTarget;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.contracts.session.SessionContracts.TrustedImportSessionTurnMessage;
import com.lynxus.contracts.session.SessionContracts.TrustedImportSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.UserTurn;
import com.lynxus.contracts.session.SessionContracts.WebSessionTurnMessageInput;
import com.lynxus.persistence.session.SessionRuntimeStore;
import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.session.SessionRuntimeDtos.ExternalCallbackRequest;
import com.lynxus.platform.session.SessionRuntimeDtos.HumanOperatorReplyRequest;
import com.lynxus.platform.session.SessionRuntimeDtos.HumanResumeRequest;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.platform.shared.redis.RedisIdempotencyService;
import com.lynxus.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class SessionRuntimeServiceTest {
    @Test
    void sendTurn_createsWebSessionAppendsMessagesBeforeWorkflowUpdateAndMarksAccepted() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        installTurnRepositoryBehavior(repository, List.of());
        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        when(repository.createOrReuseActiveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findSession(any())).thenAnswer(invocation ->
            java.util.Optional.of(session(invocation.getArgument(0), "IDLE", false, false))
        );

        SendSessionTurnResponse response = service.sendTurn(
            new SendSessionTurnRequest(
                null,
                "ast-1",
                "customer-1",
                "turn-key-1",
                List.of(new WebSessionTurnMessageInput("draft-1", Instant.parse("2026-04-01T00:00:00Z"), textBlocks("hello"), Map.of())),
                Map.of("source", "web-test")
            ),
            "turn-key-1"
        );

        assertEquals(SessionMessageDeliveryStatus.ACCEPTED, response.status());
        assertEquals(1, response.acceptedMessageIds().size());
        assertEquals(new AcceptedSessionMessageAllocation(0, "draft-1", response.acceptedMessageIds().getFirst(), 0),
            response.acceptedMessageAllocations().getFirst());
        verify(repository).createOrReuseActiveSession(argThat(session ->
            "WEB".equals(session.entryScope())
                && session.channelProfileId() == null
                && session.externalConversationId() == null
                && "customer-1".equals(session.customerId())
                && "ast-1".equals(session.assistantId())
                && session.nextMessageSequence() == 1L
        ));
        verify(repository).appendSessionMessages(eq(response.sessionId()), eq(response.turnId()), argThat(messages ->
            messages.size() == 1
                && messages.getFirst().producerType() == SessionMessageProducerType.EXTERNAL
                && messages.getFirst().role() == SessionMessageRole.USER
                && messages.getFirst().sender().senderType() == SessionMessageSenderType.CUSTOMER
                && "draft-1".equals(messages.getFirst().clientMessageId())
        ));
        verify(gateway).start(any());
        verify(gateway).submitUserTurn(eq(response.sessionId()), eq(response.turnId()), argThat(turn ->
            turn.turnId().equals(response.turnId()) && turn.messages().size() == 1
        ));
        verify(repository).updateTurnState(
            eq(response.sessionId()),
            eq(response.turnId()),
            eq("WORKFLOW_ACCEPTED"),
            eq(response.acceptedMessageIds()),
            eq(List.of()),
            eq(response.acceptedMessageIds()),
            eq(response.turnId()),
            eq(null)
        );
    }

    @Test
    void sendTurn_rejectsEmptyMessagesBeforeCreatingTurnRow() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.sendTurn(new SendSessionTurnRequest(null, "ast-1", "customer-1", "turn-key-1", List.of(), Map.of()), "turn-key-1")
        );

        assertEquals("messages are required", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
    }

    @Test
    void sendTurn_requiresIdempotencyKeyToEqualTurnDedupKey() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest(null, "ast-1", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "different-key"
            )
        );

        assertEquals("Idempotency-Key must equal turnDedupKey", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
    }

    @Test
    void channelInboundTurnRequiresSenderNameBeforeTurnAllocation() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.channelInboundTurn(channelInboundRequest(null), "dedup-1")
        );

        assertEquals("messages[0].sender.senderName is required", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
    }

    @Test
    void sendTurn_rejectsBusySessionBeforeTurnAllocation() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session("session-1", "IDLE", true, false)));

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-1", "ast-1", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("session is busy", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
    }

    @Test
    void sendTurn_reloadsSessionInsideLockBeforePreflightAndTurnAllocation() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-1")).thenReturn(
            java.util.Optional.of(session("session-1", "IDLE", false, false)),
            java.util.Optional.of(session("session-1", "IDLE", true, false))
        );
        when(repository.findTurnByDedupKey("session-1", "turn-key-1")).thenReturn(java.util.Optional.empty());

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-1", "ast-1", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("session is busy", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
        verify(repository, never()).updateTurnState(any(), any(), any(), anyList(), anyList(), anyList(), any(), any());
        verify(gateway, never()).start(any());
        verify(gateway, never()).submitUserTurn(any(), any(), any());
    }

    @Test
    void sendTurn_requiresAssistantIdForExplicitWebSession() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session("session-1", "IDLE", false, false)));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-1", null, "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("assistantId is required", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
    }

    @Test
    void sendTurn_rejectsAssistantIdMismatchForExplicitWebSession() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            mock(SessionWorkflowGateway.class),
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session("session-1", "IDLE", false, false)));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-1", "different-assistant", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("assistantId does not match session", error.getMessage());
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
    }

    @Test
    void sendTurn_marksClosedActiveIdentitySessionEndedAndCreatesNewSession() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto closedActive = session("session-closed", "IDLE", false, false);
        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        when(repository.findActiveSession("customer-1", "ast-1")).thenReturn(java.util.Optional.of(closedActive));
        when(gateway.isWorkflowClosed("session-closed")).thenReturn(true);
        when(repository.createOrReuseActiveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findSession(any())).thenAnswer(invocation ->
            java.util.Optional.of(session(invocation.getArgument(0), "IDLE", false, false))
        );
        installTurnRepositoryBehavior(repository, List.of());

        SendSessionTurnResponse response = service.sendTurn(
            new SendSessionTurnRequest(
                null,
                "ast-1",
                "customer-1",
                "turn-key-1",
                List.of(webMessage("draft-1", "hello")),
                Map.of()
            ),
            "turn-key-1"
        );

        assertNotEquals("session-closed", response.sessionId());
        verify(repository).saveSession(argThat(session ->
            "session-closed".equals(session.id()) && "ENDED".equals(session.status())
        ));
        verify(repository).createOrReuseActiveSession(argThat(session ->
            "WEB".equals(session.entryScope())
                && "customer-1".equals(session.customerId())
                && "ast-1".equals(session.assistantId())
        ));
        verify(gateway).start(any());
        verify(gateway).submitUserTurn(eq(response.sessionId()), eq(response.turnId()), any(UserTurn.class));
    }

    @Test
    void sendTurn_rejectsExplicitClosedWorkflowAndMarksSessionEnded() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-closed")).thenReturn(java.util.Optional.of(session("session-closed", "IDLE", false, false)));
        when(gateway.isWorkflowClosed("session-closed")).thenReturn(true);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-closed", "ast-1", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("session has ended", error.getMessage());
        verify(repository).saveSession(argThat(session ->
            "session-closed".equals(session.id()) && "ENDED".equals(session.status())
        ));
        verify(repository, never()).createOrReuseTurn(any());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
        verify(gateway, never()).start(any());
        verify(gateway, never()).submitUserTurn(any(), any(), any());
    }

    @Test
    void channelInboundTurnCreatesNewSessionWhenBindingSessionEnded() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto endedBindingSession = channelSession("session-ended", "ENDED", false, false);
        when(repository.findSession(any())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            if ("session-ended".equals(sessionId)) {
                return java.util.Optional.of(endedBindingSession);
            }
            return java.util.Optional.of(channelSession(sessionId, "IDLE", false, false));
        });
        when(repository.findActiveChannelSession("channel-profile-1", "conversation-1", "customer-1", "ast-1"))
            .thenReturn(java.util.Optional.empty());
        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        when(repository.createOrReuseActiveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));
        installTurnRepositoryBehavior(repository, List.of());

        var response = service.channelInboundTurn(
            channelInboundRequest("Customer", "session-ended"),
            "dedup-1"
        );

        assertNotEquals("session-ended", response.sessionId());
        assertEquals(SessionMessageDeliveryStatus.ACCEPTED, response.status());
        assertEquals(1, response.acceptedMessageAllocations().size());
        verify(repository).createOrReuseActiveSession(argThat(session ->
            "CHANNEL".equals(session.entryScope())
                && "channel-profile-1".equals(session.channelProfileId())
                && "conversation-1".equals(session.externalConversationId())
                && "customer-1".equals(session.customerId())
                && "ast-1".equals(session.assistantId())
        ));
        verify(repository).appendSessionMessages(eq(response.sessionId()), eq(response.turnId()), argThat(messages ->
            messages.size() == 1
                && messages.getFirst().producerType() == SessionMessageProducerType.EXTERNAL
                && "message-1".equals(messages.getFirst().externalMessageId())
        ));
        verify(gateway).start(any());
        verify(gateway).submitUserTurn(eq(response.sessionId()), eq(response.turnId()), any(UserTurn.class));
    }

    @Test
    void importTurn_appendsOnlyNewMessagesAndReportsDuplicateExternalIds() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto session = session("session-1", "IDLE", false, false);
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session));
        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        installTurnRepositoryBehavior(repository, List.of(existingExternalMessage("session-1", "existing-message-1", "external-1")));

        SendSessionTurnResponse response = service.importTurn(
            new TrustedImportSessionTurnRequest(
                new ExistingSessionImportTarget("session-1", "customer-1", "ast-1"),
                "import-turn-1",
                "batch-1",
                "crm",
                List.of(
                    importMessage("import-1", "external-1", SessionMessageRole.USER, "duplicate"),
                    importMessage("import-2", "external-2", SessionMessageRole.ASSISTANT, "new imported assistant")
                ),
                Map.of("importReason", "history-sync")
            ),
            "import-turn-1"
        );

        assertEquals(List.of("external-1"), response.duplicateExternalMessageIds());
        assertEquals(1, response.acceptedMessageIds().size());
        verify(repository).appendSessionMessages(eq("session-1"), eq(response.turnId()), argThat(messages ->
            messages.size() == 1
                && "external-2".equals(messages.getFirst().externalMessageId())
                && messages.getFirst().role() == SessionMessageRole.ASSISTANT
                && messages.getFirst().producerType() == SessionMessageProducerType.EXTERNAL
        ));
        verify(gateway).submitUserTurn(eq("session-1"), eq(response.turnId()), argThat(turn -> turn.messages().size() == 1));
    }

    @Test
    void importTurn_allDuplicateMessagesDoesNotSubmitWorkflowUpdate() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session("session-1", "IDLE", false, false)));
        installTurnRepositoryBehavior(repository, List.of(existingExternalMessage("session-1", "existing-message-1", "external-1")));

        SendSessionTurnResponse response = service.importTurn(
            new TrustedImportSessionTurnRequest(
                new ExistingSessionImportTarget("session-1", "customer-1", "ast-1"),
                "import-turn-1",
                "batch-1",
                "crm",
                List.of(importMessage("import-1", "external-1", SessionMessageRole.USER, "duplicate")),
                Map.of()
            ),
            "import-turn-1"
        );

        assertEquals(List.of(), response.acceptedMessageIds());
        assertEquals(List.of("external-1"), response.duplicateExternalMessageIds());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
        verify(gateway, never()).start(any());
        verify(gateway, never()).submitUserTurn(any(), any(), any());
    }

    @Test
    void sendTurn_preservesTurnAllocationWhenWorkflowRejectsBeforeAcceptedStage() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService()
        );
        SessionRuntimeDtos.SessionRuntimeSessionDto session = session("session-1", "IDLE", false, false);
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session));
        when(catalogService.getAssistantRuntimeSnapshot("ast-1")).thenReturn(assistant("ast-1"));
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);
        doThrow(new ConflictException("session is busy"))
            .when(gateway)
            .submitUserTurn(eq("session-1"), any(), any(UserTurn.class));
        installTurnRepositoryBehavior(repository, List.of());

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendTurn(
                new SendSessionTurnRequest("session-1", "ast-1", "customer-1", "turn-key-1", List.of(webMessage("draft-1", "hello")), Map.of()),
                "turn-key-1"
            )
        );

        assertEquals("session is busy", error.getMessage());
        verify(repository).updateTurnState(
            eq("session-1"),
            any(),
            eq("MESSAGES_APPENDED"),
            anyList(),
            eq(List.of()),
            anyList(),
            any(),
            eq(null)
        );
        verify(repository, never()).updateTurnState(
            eq("session-1"),
            any(),
            eq("WORKFLOW_ACCEPTED"),
            anyList(),
            anyList(),
            anyList(),
            any(),
            eq(null)
        );
    }

    @Test
    void sendTurn_retrySubmitsOnlyAllocatedInputMessagesWhenTurnAlreadyHasPlatformReplies() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = new SessionRuntimeService(
            gateway,
            mock(CatalogService.class),
            repository,
            new SessionDispatchLockService()
        );
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        SessionRuntimeStore.SessionRuntimeTurnData existingTurn = new SessionRuntimeStore.SessionRuntimeTurnData(
            "turn-1",
            "session-1",
            "turn-key-1",
            "USER_MESSAGE",
            "MESSAGES_APPENDED",
            List.of(Map.of("requestIndex", 0, "messageId", "input-message-1", "clientMessageId", "draft-1")),
            List.of("input-message-1"),
            List.of(),
            List.of("input-message-1", "reply-message-1"),
            "turn-1",
            Map.of("source", "retry-test"),
            now,
            now,
            null
        );
        List<SessionMessage> turnMessages = List.of(
            externalTurnMessage("session-1", "turn-1", "input-message-1", 0, null, "draft-1", "hello"),
            platformTurnMessage("session-1", "turn-1", "reply-message-1", 1, "platform reply")
        );

        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session("session-1", "IDLE", false, false)));
        when(repository.findTurnByDedupKey("session-1", "turn-key-1")).thenReturn(java.util.Optional.of(existingTurn));
        when(repository.createOrReuseTurn(any())).thenReturn(existingTurn);
        when(repository.listMessagesForTurn("session-1", "turn-1")).thenReturn(turnMessages);
        when(repository.listMessages("session-1")).thenReturn(turnMessages);
        when(repository.updateTurnState(any(), any(), any(), anyList(), anyList(), anyList(), any(), any())).thenAnswer(invocation ->
            new SessionRuntimeStore.SessionRuntimeTurnData(
                invocation.getArgument(1),
                invocation.getArgument(0),
                existingTurn.dedupKey(),
                existingTurn.triggerType(),
                invocation.getArgument(2),
                existingTurn.inputAllocations(),
                invocation.getArgument(3),
                invocation.getArgument(4),
                invocation.getArgument(5),
                invocation.getArgument(6),
                existingTurn.metadata(),
                existingTurn.createdAt(),
                now,
                invocation.getArgument(7)
            )
        );
        when(gateway.isWorkflowOpen("session-1")).thenReturn(true);

        SendSessionTurnResponse response = service.sendTurn(
            new SendSessionTurnRequest(
                "session-1",
                "ast-1",
                "customer-1",
                "turn-key-1",
                List.of(webMessage("draft-1", "hello")),
                Map.of()
            ),
            "turn-key-1"
        );

        assertEquals(List.of("input-message-1"), response.acceptedMessageIds());
        verify(repository, never()).appendSessionMessages(any(), any(), anyList());
        verify(repository).updateTurnState(
            eq("session-1"),
            eq("turn-1"),
            eq("MESSAGES_APPENDED"),
            eq(List.of("input-message-1")),
            eq(List.of()),
            eq(List.of("input-message-1", "reply-message-1")),
            eq("turn-1"),
            eq(null)
        );
        verify(repository).updateTurnState(
            eq("session-1"),
            eq("turn-1"),
            eq("WORKFLOW_ACCEPTED"),
            eq(List.of("input-message-1")),
            eq(List.of()),
            eq(List.of("input-message-1", "reply-message-1")),
            eq("turn-1"),
            eq(null)
        );
        verify(gateway).submitUserTurn(eq("session-1"), eq("turn-1"), argThat(turn ->
            turn.messages().size() == 1 && "input-message-1".equals(turn.messages().getFirst().messageId())
        ));
    }

    @Test
    void humanResume_allocatesPlatformTurnBeforeSignallingWorkflow() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = serviceWithCurrentUser(gateway, catalogService, repository, "operator-1");
        SessionRuntimeDtos.SessionRuntimeSessionDto session = session("session-1", "ACTIVE", false, false);
        SessionRuntimeDtos.SessionRuntimeSessionDto changed = session("session-1", "ACTIVE", false, false, 1L, 1L);
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session), java.util.Optional.of(changed));
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of(playbookRun("run-1")));
        when(repository.allocatePlatformTurn(eq("session-1"), eq("HUMAN_RESUME"), eq("resume-event-1"), eq("resume-event-1"), any()))
            .thenReturn(platformTurn("turn-resume-1", "session-1", "HUMAN_RESUME", "resume-event-1", "resume-event-1"));

        service.humanResume("session-1", new HumanResumeRequest("run-1", "resume-event-1", Map.of("approved", true)));

        verify(repository).allocatePlatformTurn(eq("session-1"), eq("HUMAN_RESUME"), eq("resume-event-1"), eq("resume-event-1"), argThat(metadata ->
            "run-1".equals(metadata.get("playbookRunId"))
                && "operator-1".equals(metadata.get("operatorId"))
                && Map.of("approved", true).equals(metadata.get("payload"))
        ));
        verify(gateway).humanResume(eq("session-1"), argThat(signal ->
            "turn-resume-1".equals(signal.turnId())
                && "resume-event-1".equals(signal.turnDedupKey())
                && "resume-event-1".equals(signal.sourceEventId())
                && "run-1".equals(signal.playbookRunId())
                && "operator-1".equals(signal.operatorId())
        ));
    }

    @Test
    void externalCallback_allocatesPlatformTurnUsingCallbackIdempotencyKeyBeforeSignallingWorkflow() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        RedisIdempotencyService idempotencyService = mock(RedisIdempotencyService.class);
        SessionRuntimeService service = serviceWithCurrentUser(
            gateway,
            catalogService,
            repository,
            "operator-unused",
            idempotencyService
        );
        when(idempotencyService.execute(any(), eq(SessionRuntimeDtos.SessionRuntimeSessionDto.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<SessionRuntimeDtos.SessionRuntimeSessionDto> action = invocation.getArgument(2);
            return action.get();
        });
        SessionRuntimeDtos.SessionRuntimeSessionDto session = session("session-1", "ACTIVE", false, false);
        SessionRuntimeDtos.SessionRuntimeSessionDto changed = session("session-1", "ACTIVE", false, false, 1L, 1L);
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session), java.util.Optional.of(changed));
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of(playbookRun("run-1")));
        when(repository.allocatePlatformTurn(eq("session-1"), eq("EXTERNAL_CALLBACK"), eq("callback-key-1"), any(), any()))
            .thenReturn(platformTurn("turn-callback-1", "session-1", "EXTERNAL_CALLBACK", "callback-key-1", "event-callback-1"));

        service.externalCallback("session-1", new ExternalCallbackRequest("run-1", Map.of("ok", true)), "callback-key-1");

        verify(repository).allocatePlatformTurn(eq("session-1"), eq("EXTERNAL_CALLBACK"), eq("callback-key-1"), any(), argThat(metadata ->
            "run-1".equals(metadata.get("playbookRunId"))
                && "callback-key-1".equals(metadata.get("idempotencyKey"))
                && Map.of("ok", true).equals(metadata.get("payload"))
        ));
        verify(gateway).externalCallback(eq("session-1"), argThat(signal ->
            "turn-callback-1".equals(signal.turnId())
                && "callback-key-1".equals(signal.turnDedupKey())
                && "run-1".equals(signal.playbookRunId())
        ));
    }

    @Test
    void humanOperatorReply_allocatesPlatformTurnAndAppendsVisibleOperatorMessage() {
        SessionWorkflowGateway gateway = mock(SessionWorkflowGateway.class);
        CatalogService catalogService = mock(CatalogService.class);
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeService service = serviceWithCurrentUser(gateway, catalogService, repository, "operator-1");
        SessionRuntimeDtos.SessionRuntimeSessionDto session = session("session-1", "ACTIVE", false, false);
        SessionRuntimeDtos.SessionRuntimeSessionDto changed = session("session-1", "ACTIVE", false, false, 1L, 1L);
        when(repository.findSession("session-1")).thenReturn(java.util.Optional.of(session), java.util.Optional.of(changed));
        when(repository.allocatePlatformTurn(eq("session-1"), eq("HUMAN_OPERATOR_REPLY"), eq("operator-action-1"), eq(null), any()))
            .thenReturn(platformTurn("turn-operator-1", "session-1", "HUMAN_OPERATOR_REPLY", "operator-action-1", null));
        when(repository.appendSessionMessages(eq("session-1"), eq("turn-operator-1"), anyList())).thenReturn(List.of());

        service.humanOperatorReply(
            "session-1",
            new HumanOperatorReplyRequest("operator-action-1", textMessageInput("operator reply"), Map.of("caseId", "case-1"))
        );

        verify(repository).allocatePlatformTurn(eq("session-1"), eq("HUMAN_OPERATOR_REPLY"), eq("operator-action-1"), eq(null), argThat(metadata ->
            "operator-action-1".equals(metadata.get("operatorActionId"))
                && "operator-1".equals(metadata.get("operatorId"))
                && Map.of("caseId", "case-1").equals(metadata.get("payload"))
        ));
        verify(repository).appendSessionMessages(eq("session-1"), eq("turn-operator-1"), argThat(messages ->
            messages.size() == 1
                && messages.getFirst().producerType() == SessionMessageProducerType.PLATFORM
                && messages.getFirst().role() == SessionMessageRole.HUMAN_OPERATOR
                && messages.getFirst().sender().senderType() == SessionMessageSenderType.HUMAN_OPERATOR
                && "operator-1".equals(messages.getFirst().sender().senderId())
        ));
        verify(gateway, never()).humanOperatorReply(any(), any(HumanOperatorReplySignal.class));
    }

    private static void installTurnRepositoryBehavior(
        SessionRuntimeRepository repository,
        List<SessionMessage> existingSessionMessages
    ) {
        AtomicReference<SessionRuntimeStore.SessionRuntimeTurnData> turnRef = new AtomicReference<>();
        List<SessionMessage> turnMessages = new ArrayList<>();
        when(repository.createOrReuseTurn(any())).thenAnswer(invocation -> {
            SessionRuntimeStore.SessionRuntimeTurnData turn = invocation.getArgument(0);
            turnRef.set(turn);
            return turn;
        });
        when(repository.listMessagesForTurn(any(), any())).thenAnswer(invocation -> List.copyOf(turnMessages));
        when(repository.listMessages(any())).thenReturn(existingSessionMessages);
        when(repository.appendSessionMessages(any(), any(), anyList())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            String turnId = invocation.getArgument(1);
            List<SessionRuntimeStore.SessionMessageAppendData> inputs = invocation.getArgument(2);
            List<SessionMessage> appended = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index += 1) {
                SessionRuntimeStore.SessionMessageAppendData input = inputs.get(index);
                SessionMessage message = new SessionMessage(
                    input.messageId(),
                    sessionId,
                    turnMessages.size() + index + 1L,
                    turnId,
                    turnMessages.size() + index,
                    input.producerType(),
                    input.externalMessageId(),
                    input.clientMessageId(),
                    input.occurredAt(),
                    input.role(),
                    input.sender(),
                    input.status(),
                    input.blocks(),
                    input.metadata(),
                    input.relatedPlaybookRunId(),
                    input.relatedOwnerAgentId(),
                    input.sourceEventId(),
                    input.createdAt(),
                    input.updatedAt()
                );
                appended.add(message);
            }
            turnMessages.addAll(appended);
            return appended;
        });
        when(repository.updateTurnState(any(), any(), any(), anyList(), anyList(), anyList(), any(), any())).thenAnswer(invocation -> {
            SessionRuntimeStore.SessionRuntimeTurnData current = turnRef.get();
            SessionRuntimeStore.SessionRuntimeTurnData updated = new SessionRuntimeStore.SessionRuntimeTurnData(
                invocation.getArgument(1),
                invocation.getArgument(0),
                current.dedupKey(),
                current.triggerType(),
                invocation.getArgument(2),
                current.inputAllocations(),
                invocation.getArgument(3),
                invocation.getArgument(4),
                invocation.getArgument(5),
                invocation.getArgument(6),
                current.metadata(),
                current.createdAt(),
                Instant.now(),
                invocation.getArgument(7)
            );
            turnRef.set(updated);
            return updated;
        });
    }

    private static SessionRuntimeService serviceWithCurrentUser(
        SessionWorkflowGateway gateway,
        CatalogService catalogService,
        SessionRuntimeRepository repository,
        String operatorId
    ) {
        return serviceWithCurrentUser(
            gateway,
            catalogService,
            repository,
            operatorId,
            mock(RedisIdempotencyService.class)
        );
    }

    private static SessionRuntimeService serviceWithCurrentUser(
        SessionWorkflowGateway gateway,
        CatalogService catalogService,
        SessionRuntimeRepository repository,
        String operatorId,
        RedisIdempotencyService idempotencyService
    ) {
        return new SessionRuntimeService(
            gateway,
            catalogService,
            repository,
            new SessionDispatchLockService(),
            mock(StringRedisTemplate.class),
            new ObjectMapper(),
            new RedisKeyspace(),
            idempotencyService,
            null,
            new ExternalCallbackIdempotencyKeyFactory(new ObjectMapper()),
            () -> platformUser(operatorId)
        );
    }

    private static PlatformUser platformUser(String operatorId) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new PlatformUser(
            operatorId,
            operatorId,
            operatorId,
            operatorId + "@example.com",
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            UserStatus.ACTIVE,
            now,
            now,
            now,
            List.of()
        );
    }

    private static SessionRuntimeStore.SessionRuntimeTurnData platformTurn(
        String turnId,
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId
    ) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        Map<String, Object> metadata = sourceEventId == null ? Map.of() : Map.of("sourceEventId", sourceEventId);
        return new SessionRuntimeStore.SessionRuntimeTurnData(
            turnId,
            sessionId,
            dedupKey,
            triggerType,
            "ALLOCATED_IDS",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            metadata,
            now,
            now,
            null
        );
    }

    private static PlaybookRun playbookRun(String runId) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new PlaybookRun(
            runId,
            "session-1",
            "event-1",
            "playbook-1",
            "agent-1",
            PlaybookRunStatus.WAITING,
            Map.of(),
            Map.of(),
            "human_task:approval",
            now,
            now,
            null
        );
    }

    private static WebSessionTurnMessageInput webMessage(String clientMessageId, String text) {
        return new WebSessionTurnMessageInput(clientMessageId, Instant.parse("2026-04-01T00:00:00Z"), textBlocks(text), Map.of());
    }

    private static ChannelInboundSessionTurnRequest channelInboundRequest(String senderName) {
        return channelInboundRequest(senderName, null);
    }

    private static ChannelInboundSessionTurnRequest channelInboundRequest(String senderName, String sessionId) {
        return new ChannelInboundSessionTurnRequest(
            "channel-profile-1",
            "conversation-1",
            "dedup-1",
            "ast-1",
            "customer-1",
            sessionId,
            List.of(new ChannelInboundSessionTurnMessage(
                "event-1",
                "message-1",
                Instant.parse("2026-04-01T00:00:00Z"),
                SessionMessageRole.USER,
                new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", senderName),
                textMessageInput("hello"),
                Map.of()
            )),
            Map.of()
        );
    }

    private static TrustedImportSessionTurnMessage importMessage(
        String importMessageId,
        String externalMessageId,
        SessionMessageRole role,
        String text
    ) {
        SessionMessageSender sender = role == SessionMessageRole.USER
            ? new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "Customer")
            : new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "Agent");
        return new TrustedImportSessionTurnMessage(
            importMessageId,
            externalMessageId,
            Instant.parse("2026-04-01T00:00:00Z"),
            role,
            sender,
            textMessageInput(text),
            Map.of("rawIndex", importMessageId)
        );
    }

    private static SessionMessage existingExternalMessage(String sessionId, String messageId, String externalMessageId) {
        return externalTurnMessage(sessionId, "previous-turn", messageId, 0, externalMessageId, null, "existing");
    }

    private static SessionMessage externalTurnMessage(
        String sessionId,
        String turnId,
        String messageId,
        int turnIndex,
        String externalMessageId,
        String clientMessageId,
        String text
    ) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new SessionMessage(
            messageId,
            sessionId,
            1L,
            turnId,
            turnIndex,
            SessionMessageProducerType.EXTERNAL,
            externalMessageId,
            clientMessageId,
            now,
            SessionMessageRole.USER,
            new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "Customer"),
            SessionMessageStatus.SENT,
            textBlocks(text),
            Map.of(),
            null,
            null,
            null,
            now,
            now
        );
    }

    private static SessionMessage platformTurnMessage(
        String sessionId,
        String turnId,
        String messageId,
        int turnIndex,
        String text
    ) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new SessionMessage(
            messageId,
            sessionId,
            2L,
            turnId,
            turnIndex,
            SessionMessageProducerType.PLATFORM,
            null,
            null,
            now,
            SessionMessageRole.ASSISTANT,
            new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "Agent"),
            SessionMessageStatus.SENT,
            textBlocks(text),
            Map.of(),
            null,
            null,
            null,
            now,
            now
        );
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto session(
        String sessionId,
        String status,
        boolean agentTurnActive,
        boolean draining
    ) {
        return session(sessionId, status, agentTurnActive, draining, 0L, 0L);
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto session(
        String sessionId,
        String status,
        boolean agentTurnActive,
        boolean draining,
        long latestMessageSequence,
        long latestEventSequence
    ) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new SessionRuntimeDtos.SessionRuntimeSessionDto(
            sessionId,
            "scn-1",
            "title",
            "WEB",
            null,
            null,
            "customer-1",
            "ast-1",
            "Assistant",
            "1.0.0",
            status,
            "agent-1",
            "agent-1",
            null,
            agentTurnActive,
            false,
            false,
            draining,
            Map.of(),
            0L,
            now.plus(Duration.ofMinutes(30)),
            now,
            now,
            null,
            latestMessageSequence,
            latestEventSequence
        );
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto channelSession(
        String sessionId,
        String status,
        boolean agentTurnActive,
        boolean draining
    ) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new SessionRuntimeDtos.SessionRuntimeSessionDto(
            sessionId,
            "scn-1",
            "title",
            "CHANNEL",
            "channel-profile-1",
            "conversation-1",
            "customer-1",
            "ast-1",
            "Assistant",
            "1.0.0",
            status,
            "agent-1",
            "agent-1",
            null,
            agentTurnActive,
            false,
            false,
            draining,
            Map.of(),
            0L,
            now.plus(Duration.ofMinutes(30)),
            now,
            now,
            "ENDED".equals(status) ? now : null,
            0L,
            0L
        );
    }

    private static CatalogDtos.AssistantDto assistant(String assistantId) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
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

    private static SessionMessageInput textMessageInput(String text) {
        return new SessionMessageInput(textBlocks(text), Map.of());
    }

    private static List<Object> textBlocks(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(Map.of("type", "TEXT", "text", text));
    }
}
