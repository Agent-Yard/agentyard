package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.session.SessionContracts.PlaybookRun;
import com.agentyard.contracts.session.SessionContracts.PlaybookRunStatus;
import com.agentyard.contracts.session.SessionContracts.SessionActorType;
import com.agentyard.contracts.session.SessionContracts.SessionEvent;
import com.agentyard.contracts.session.SessionContracts.SessionEventType;
import com.agentyard.contracts.session.SessionContracts.SessionMessage;
import com.agentyard.contracts.session.SessionContracts.SessionMessageProducerType;
import com.agentyard.contracts.session.SessionContracts.SessionMessageRole;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSender;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSenderType;
import com.agentyard.contracts.session.SessionContracts.SessionMessageStatus;
import com.agentyard.persistence.session.SessionRuntimeStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionPersistenceActivitiesImplTest {
    @Test
    void shouldPublishRuntimeChangeNoticeAfterProjectionWrites() {
        JooqSessionProjectionRepository repository = mock(JooqSessionProjectionRepository.class);
        SessionRuntimeChangePublisher publisher = mock(SessionRuntimeChangePublisher.class);
        SessionPersistenceActivitiesImpl activities = new SessionPersistenceActivitiesImpl(repository, publisher);
        SessionPersistenceActivities.SessionRecord session = new SessionPersistenceActivities.SessionRecord(
            "session-1",
            "scenario-1",
            "Session",
            "customer-1",
            "assistant-1",
            "Assistant",
            "1.0.0",
            "ACTIVE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            0L,
            null,
            Instant.parse("2026-04-21T00:00:00Z"),
            Instant.parse("2026-04-21T00:00:00Z"),
            null
        );
        SessionMessage message = new SessionMessage(
            "message-1",
            "session-1",
            1L,
            "turn-1",
            0,
            SessionMessageProducerType.PLATFORM,
            null,
            null,
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionMessageRole.USER,
            new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "customer-1"),
            SessionMessageStatus.SENT,
            List.of(Map.of("type", "TEXT", "text", "退款")),
            Map.of(),
            null,
            null,
            null,
            Instant.parse("2026-04-21T00:00:01Z"),
            Instant.parse("2026-04-21T00:00:01Z")
        );
        SessionEvent event = new SessionEvent(
            "event-1",
            "session-1",
            1L,
            SessionEventType.OWNER_SWITCH,
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionActorType.AGENT,
            "agent-1",
            Map.of(),
            null,
            null,
            null
        );
        PlaybookRun playbookRun = new PlaybookRun(
            "run-1",
            "session-1",
            "event-1",
            "playbook-1",
            "agent-1",
            PlaybookRunStatus.RUNNING,
            Map.of(),
            Map.of(),
            null,
            Instant.parse("2026-04-21T00:00:02Z"),
            Instant.parse("2026-04-21T00:00:02Z"),
            null
        );
        SessionRuntimeStore.SessionRuntimeTurnData platformTurn = new SessionRuntimeStore.SessionRuntimeTurnData(
            "turn-platform-1",
            "session-1",
            "dedup-platform-1",
            "PLAYBOOK_COMPLETED",
            "ALLOCATED_IDS",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            Map.of("sourceEventId", "persisted-event-1"),
            Instant.parse("2026-04-21T00:00:02Z"),
            Instant.parse("2026-04-21T00:00:02Z"),
            null
        );
        when(repository.allocatePlatformTurn(
            "session-1",
            "PLAYBOOK_COMPLETED",
            "dedup-platform-1",
            "event-1",
            Map.of("playbookRunId", "run-1")
        )).thenReturn(platformTurn);

        activities.saveSession(session);
        SessionPersistenceActivities.PlatformTurnAllocation allocation = activities.allocatePlatformTurn(
            "session-1",
            "PLAYBOOK_COMPLETED",
            "dedup-platform-1",
            "event-1",
            Map.of("playbookRunId", "run-1")
        );
        assertEquals("persisted-event-1", allocation.sourceEventId());
        var appendRecord = new SessionPersistenceActivities.SessionMessageAppendRecord(
            message.messageId(),
            message.producerType(),
            message.externalMessageId(),
            message.clientMessageId(),
            message.occurredAt(),
            message.role(),
            message.sender(),
            message.status(),
            message.blocks(),
            message.metadata(),
            message.relatedPlaybookRunId(),
            message.relatedOwnerAgentId(),
            message.sourceEventId(),
            message.createdAt(),
            message.updatedAt()
        );
        activities.appendSessionMessages("session-1", "turn-1", List.of(appendRecord));
        activities.appendEvent(event);
        activities.appendLlmUsage(List.of(new SessionPersistenceActivities.LlmUsageRecord(
            "usage-1",
            "SESSION_OWNER_MODEL",
            "session-1",
            "event-1",
            "USER_MESSAGE",
            null,
            "scenario-1",
            "customer-1",
            "assistant-1",
            "1.0.0",
            "agent-1",
            "OPENAI_COMPATIBLE",
            "model-1",
            "model-ver-1",
            "gpt-test",
            true,
            10,
            5,
            15,
            Map.of("prompt_tokens", 10),
            1,
            0,
            Instant.parse("2026-04-21T00:00:01Z")
        )));
        activities.savePlaybookRun(playbookRun);

        verify(publisher, times(5)).publishSessionChanged("session-1");
        verify(repository).saveSession(session);
        verify(repository).allocatePlatformTurn(
            "session-1",
            "PLAYBOOK_COMPLETED",
            "dedup-platform-1",
            "event-1",
            Map.of("playbookRunId", "run-1")
        );
        verify(repository).appendSessionMessages("session-1", "turn-1", List.of(appendRecord));
        verify(repository).appendEvent(event);
        verify(repository).appendLlmUsage(List.of(new SessionPersistenceActivities.LlmUsageRecord(
            "usage-1",
            "SESSION_OWNER_MODEL",
            "session-1",
            "event-1",
            "USER_MESSAGE",
            null,
            "scenario-1",
            "customer-1",
            "assistant-1",
            "1.0.0",
            "agent-1",
            "OPENAI_COMPATIBLE",
            "model-1",
            "model-ver-1",
            "gpt-test",
            true,
            10,
            5,
            15,
            Map.of("prompt_tokens", 10),
            1,
            0,
            Instant.parse("2026-04-21T00:00:01Z")
        )));
        verify(repository).savePlaybookRun(playbookRun);
    }
}
