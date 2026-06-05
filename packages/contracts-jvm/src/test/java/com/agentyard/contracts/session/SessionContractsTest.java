package com.agentyard.contracts.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.agentyard.contracts.session.SessionContracts.AgentConfig;
import com.agentyard.contracts.session.SessionContracts.AgentRuntimeContextEntry;
import com.agentyard.contracts.session.SessionContracts.AgentRuntimeContextEntryType;
import com.agentyard.contracts.session.SessionContracts.AgentTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ChannelIdentityImportTarget;
import com.agentyard.contracts.session.SessionContracts.ImportSessionTarget;
import com.agentyard.contracts.session.SessionContracts.SendSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.SessionMessage;
import com.agentyard.contracts.session.SessionContracts.SessionMessageProducerType;
import com.agentyard.contracts.session.SessionContracts.SessionMessageRole;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSender;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSenderType;
import com.agentyard.contracts.session.SessionContracts.SessionMessageStatus;
import com.agentyard.contracts.session.SessionContracts.SessionTrigger;
import com.agentyard.contracts.session.SessionContracts.SessionTriggerType;
import com.agentyard.contracts.session.SessionContracts.TrustedImportSessionTurnMessage;
import com.agentyard.contracts.session.SessionContracts.TrustedImportSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.UserTurn;
import com.agentyard.contracts.session.SessionContracts.WebSessionTurnMessageInput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContractsTest {
    @Test
    void sessionMessageExposesTurnAndProducerFields() {
        SessionMessage message = sessionMessage("msg-1", 1, "turn-1", 0, SessionMessageProducerType.EXTERNAL);

        assertEquals("turn-1", message.turnId());
        assertEquals(0, message.turnIndex());
        assertEquals(SessionMessageProducerType.EXTERNAL, message.producerType());
        assertEquals("external-msg-1", message.externalMessageId());
        assertEquals("client-msg-1", message.clientMessageId());
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), message.occurredAt());
    }

    @Test
    void sendSessionTurnRequestUsesWebMessageInputsWithoutRoleOrSender() {
        SendSessionTurnRequest request = new SendSessionTurnRequest(
            null,
            "assistant-1",
            "customer-1",
            "turn-dedup-1",
            List.of(new WebSessionTurnMessageInput("client-msg-1", Instant.parse("2026-05-01T00:00:00Z"), List.of(textBlock("hello")), Map.of())),
            Map.of()
        );

        assertEquals("turn-dedup-1", request.turnDedupKey());
        assertEquals("client-msg-1", request.messages().getFirst().clientMessageId());
    }

    @Test
    void trustedImportTurnAllowsTrustedRolesAndTargets() {
        ImportSessionTarget target = new ChannelIdentityImportTarget("profile-1", "conversation-1", "customer-1", "assistant-1");
        TrustedImportSessionTurnRequest request = new TrustedImportSessionTurnRequest(
            target,
            "import-batch-1",
            "batch-1",
            "crm",
            List.of(new TrustedImportSessionTurnMessage(
                "import-msg-1",
                null,
                Instant.parse("2026-05-01T00:00:00Z"),
                SessionMessageRole.ASSISTANT,
                new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "Agent"),
                null,
                Map.of()
            )),
            Map.of()
        );

        assertTrue(request.target() instanceof ChannelIdentityImportTarget);
        assertEquals(SessionMessageRole.ASSISTANT, request.messages().getFirst().role());
    }

    @Test
    void userTurnAndAgentTurnRequestCarryPersistedDeltaAndContextEntries() {
        SessionMessage message = sessionMessage("msg-1", 1, "turn-1", 0, SessionMessageProducerType.EXTERNAL);
        UserTurn userTurn = new UserTurn("turn-1", "customer-1", "turn-dedup-1", List.of(message), Map.of());
        AgentRuntimeContextEntry contextEntry = new AgentRuntimeContextEntry(
            "event-1",
            AgentRuntimeContextEntryType.SESSION_EVENT,
            1L,
            Instant.parse("2026-05-01T00:00:01Z"),
            Map.of("eventType", "HUMAN_RESUME_RECEIVED")
        );
        AgentTurnRequest request = new AgentTurnRequest(
            "session-1",
            "turn-1",
            "turn-1:exec-1",
            "reply-msg-1",
            1L,
            "assistant-1",
            "1.0.0",
            agentConfig(),
            List.of(agentConfig()),
            List.of(),
            null,
            Map.of(),
            null,
            false,
            new SessionTrigger(SessionTriggerType.USER_MESSAGE, "turn-1", "event-1", Map.of()),
            List.of(message),
            List.of(contextEntry),
            true
        );

        assertEquals(userTurn.messages(), request.messages());
        assertEquals("reply-msg-1", request.replyMessageId());
        assertEquals(AgentRuntimeContextEntryType.SESSION_EVENT, request.contextEntries().getFirst().entryType());
        assertTrue(request.transcriptBootstrap());
        assertFalse(request.messages().isEmpty());
    }

    private static SessionMessage sessionMessage(
        String messageId,
        long sequence,
        String turnId,
        int turnIndex,
        SessionMessageProducerType producerType
    ) {
        return new SessionMessage(
            messageId,
            "session-1",
            sequence,
            turnId,
            turnIndex,
            producerType,
            "external-" + messageId,
            "client-" + messageId,
            Instant.parse("2026-05-01T00:00:00Z"),
            SessionMessageRole.USER,
            new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "Customer"),
            SessionMessageStatus.SENT,
            List.of(textBlock("hello")),
            Map.of(),
            null,
            null,
            null,
            Instant.parse("2026-05-01T00:00:01Z"),
            Instant.parse("2026-05-01T00:00:01Z")
        );
    }

    private static Map<String, Object> textBlock(String text) {
        return Map.of("type", "TEXT", "text", text);
    }

    private static AgentConfig agentConfig() {
        return new AgentConfig(
            "agent-1",
            "Agent",
            "support",
            "Handle support",
            null,
            null,
            false,
            "",
            false,
            null,
            null,
            0,
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
