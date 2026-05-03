package com.lynxus.platform.session;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.platform.channel.ChannelGatewayClient;
import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeSessionDto;
import com.lynxus.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamEvent;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SessionRuntimeStreamServiceTest {
    @Test
    void shouldPublishSessionUpdatedEventWhenObservedFingerprintChanges() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 1L);
        SessionRuntimeRepository.SessionRuntimeChangeStamp initial = new SessionRuntimeRepository.SessionRuntimeChangeStamp(
            "session-1",
            session.updatedAt(),
            session.latestMessageSequence(),
            session.latestEventSequence(),
            null
        );
        SessionRuntimeRepository.SessionRuntimeChangeStamp updated = new SessionRuntimeRepository.SessionRuntimeChangeStamp(
            "session-1",
            session.updatedAt().plusSeconds(5),
            session.latestMessageSequence() + 1,
            session.latestEventSequence() + 1,
            null
        );

        when(replayStore.replayAfter("session-1", null)).thenReturn(new SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult(false, List.of()));
        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(initial), Optional.of(updated));
        when(replayStore.append(any())).thenReturn(true);

        service.connect("session-1", null, "tester");
        service.pollSubscribedSessions();

        verify(replayStore).append(argThat(event ->
            "SESSION_SNAPSHOT".equals(event.type()) && "session-snapshot:session-1:1:1".equals(event.id())
        ));
        verify(replayStore).append(argThat(event -> "SESSION_UPDATED".equals(event.type())));
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldMaterializeRuntimeChangeNoticeWhenSessionHasLocalSubscribers() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 1L);
        String initialFingerprint = "session-1:0:0:0:0";
        String updatedFingerprint = "session-1:1713657605000:2:2:0";

        when(replayStore.replayAfter("session-1", null)).thenReturn(new SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult(false, List.of()));
        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(
            new SessionRuntimeRepository.SessionRuntimeChangeStamp("session-1", Instant.ofEpochMilli(0), 0L, 0L, null)
        ));
        when(replayStore.append(any())).thenReturn(true);

        service.connect("session-1", null, "tester");
        service.handleRuntimeChangeNotice(new SessionRuntimeChangeNotice("session-1", updatedFingerprint, Instant.now(), "worker-1"));

        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), argThat(payload -> payload.contains(updatedFingerprint)));
    }

    @Test
    void shouldMaterializeRuntimeChangeNoticeIntoReplayWithoutLocalSubscribers() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 2L);
        String updatedFingerprint = "session-1:1713657605000:2:2:0";

        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(replayStore.append(any())).thenReturn(true);

        service.handleRuntimeChangeNotice(new SessionRuntimeChangeNotice("session-1", updatedFingerprint, Instant.now(), "worker-1"));

        verify(replayStore).append(any());
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), argThat(payload -> payload.contains(updatedFingerprint)));
    }

    @Test
    void shouldIgnoreInternalAndFinalOutcomeFrames() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );

        service.acceptStreamFrame(frame(
            AgentTurnStreamFrameKind.MODEL_STARTED,
            StreamVisibility.INTERNAL,
            2,
            Map.of("modelRoundId", "round-1")
        ));
        service.acceptStreamFrame(frame(AgentTurnStreamFrameKind.FINAL_OUTCOME, StreamVisibility.INTERNAL, 3, Map.of()));

        verify(replayStore, org.mockito.Mockito.never()).append(any());
        verify(pubSubBus, org.mockito.Mockito.never()).publish(any(), any());
    }

    @Test
    void shouldRelayAcceptedStreamFramesToChannelActivityRelay() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        SessionChannelActivityRelay activityRelay = mock(SessionChannelActivityRelay.class);
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            mock(SessionRuntimeRepository.class),
            replayStore,
            pubSubBus,
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            activityRelay,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        AgentTurnStreamFrame frame = frame(AgentTurnStreamFrameKind.TURN_STARTED, StreamVisibility.OPERATOR, 2, Map.of());
        service.acceptStreamFrame(frame);

        verify(activityRelay).relay(frame);
    }

    @Test
    void shouldStillAcceptAndPublishFrameWhenChannelBindingLookupFails() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        ChannelGatewayClient channelGatewayClient = mock(ChannelGatewayClient.class);
        when(channelGatewayClient.getBindingBySession("session-1")).thenThrow(new IllegalStateException("gateway unavailable"));
        when(replayStore.append(any())).thenReturn(true);
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            mock(SessionRuntimeRepository.class),
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            new DefaultSessionChannelActivityRelay(channelGatewayClient),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        assertDoesNotThrow(() -> service.acceptStreamFrame(frame(AgentTurnStreamFrameKind.TURN_STARTED, StreamVisibility.OPERATOR, 2, Map.of())));

        verify(replayStore).append(argThat(event -> "SESSION_PROGRESS".equals(event.type())));
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldRecordRuntimeStreamMetricsFromAcceptedFrames() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            mock(SessionRuntimeRepository.class),
            replayStore,
            pubSubBus,
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            SessionChannelActivityRelay.noop(),
            meterRegistry
        );

        service.acceptStreamFrame(frameAt(AgentTurnStreamFrameKind.TURN_STARTED, StreamVisibility.OPERATOR, 1, Map.of(), Instant.parse("2026-05-02T00:00:00Z")));
        service.acceptStreamFrame(frameAt(AgentTurnStreamFrameKind.ACTION_TOOL_STARTED, StreamVisibility.OPERATOR, 2, Map.of("toolName", "lookup"), Instant.parse("2026-05-02T00:00:01Z")));
        service.acceptStreamFrame(frameAt(AgentTurnStreamFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.CUSTOMER, 3, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "hi"
        ), Instant.parse("2026-05-02T00:00:02Z")));
        service.acceptStreamFrame(frameAt(AgentTurnStreamFrameKind.REPLY_BLOCK_COMPLETED, StreamVisibility.CUSTOMER, 4, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "block",
            Map.of("type", "TEXT", "text", "hi")
        ), Instant.parse("2026-05-02T00:00:05Z")));

        assertEquals(1.0, meterRegistry.get("lynxus.runtime_stream.action_tool.started").counter().count());
        assertEquals(1, meterRegistry.get("lynxus.runtime_stream.ttft").timer().count());
        assertEquals(1, meterRegistry.get("lynxus.runtime_stream.assistant_text.duration").timer().count());
    }

    @Test
    void shouldProjectErrorIntoDraftDiscardAndStreamError() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus, keyspace);
        when(replayStore.append(any())).thenReturn(true);

        service.acceptStreamFrame(frame(AgentTurnStreamFrameKind.ERROR, StreamVisibility.OPERATOR, 5, Map.of(
            "code",
            "PROVIDER_STREAM_MALFORMED",
            "message",
            "provider stream malformed"
        )));

        ArgumentCaptor<SessionRuntimeStreamEvent> events = ArgumentCaptor.forClass(SessionRuntimeStreamEvent.class);
        verify(replayStore, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals("SESSION_REPLY_DRAFT", events.getAllValues().get(0).type());
        assertEquals(com.lynxus.contracts.session.SessionContracts.SessionReplyDraftOperation.DISCARD, events.getAllValues().get(0).operation());
        assertEquals("SESSION_STREAM_ERROR", events.getAllValues().get(1).type());
        verify(pubSubBus, org.mockito.Mockito.times(2)).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldProjectReplyDraftWithRuntimeReplyMessageId() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus, keyspace);
        when(replayStore.append(any())).thenReturn(true);

        service.acceptStreamFrame(frame(AgentTurnStreamFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.CUSTOMER, 2, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "hi"
        )));

        verify(replayStore).append(argThat(event ->
            "SESSION_REPLY_DRAFT".equals(event.type())
                && "session-message-reply-1".equals(event.messageId())
                && "hi".equals(event.delta())
        ));
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldRejectCustomerActionToolFramesBeforeProjection() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus);

        assertThrows(ResponseStatusException.class, () -> service.acceptStreamFrame(frame(
            AgentTurnStreamFrameKind.ACTION_TOOL_STARTED,
            StreamVisibility.CUSTOMER,
            2,
            Map.of("toolName", "order_service.lookupShipment")
        )));

        verify(replayStore, org.mockito.Mockito.never()).append(any());
        verify(pubSubBus, org.mockito.Mockito.never()).publish(any(), any());
    }

    @Test
    void shouldRejectCustomerFramesWithSensitiveInternalFields() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus);
        List<Map<String, Object>> sensitivePayloads = List.of(
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "model", "gpt"),
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "toolName", "lookup"),
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "prompt", "raw prompt"),
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "credential", "vault://secret"),
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "privacy", Map.of("placeholder", "x")),
            Map.of("messageId", "session-message-reply-1", "blockId", "block-1", "blockType", "TEXT", "delta", "ok", "system-reminder", "hidden")
        );

        for (int index = 0; index < sensitivePayloads.size(); index += 1) {
            Map<String, Object> payload = sensitivePayloads.get(index);
            int seq = index + 10;
            assertThrows(ResponseStatusException.class, () -> service.acceptStreamFrame(frame(
                AgentTurnStreamFrameKind.REPLY_BLOCK_DELTA,
                StreamVisibility.CUSTOMER,
                seq,
                payload
            )));
        }

        verify(replayStore, org.mockito.Mockito.never()).append(any());
        verify(pubSubBus, org.mockito.Mockito.never()).publish(any(), any());
    }

    @Test
    void shouldNotProjectInternalFramesToWebReplay() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus);

        service.acceptStreamFrame(frame(
            AgentTurnStreamFrameKind.MODEL_STARTED,
            StreamVisibility.INTERNAL,
            2,
            Map.of("modelRoundId", "round-1")
        ));

        verify(replayStore, org.mockito.Mockito.never()).append(any());
        verify(pubSubBus, org.mockito.Mockito.never()).publish(any(), any());
    }

    @Test
    void shouldAllowCustomerReplyBlockCompletedWithSafeTextBlock() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus, keyspace);
        when(replayStore.append(any())).thenReturn(true);

        service.acceptStreamFrame(frame(AgentTurnStreamFrameKind.REPLY_BLOCK_COMPLETED, StreamVisibility.CUSTOMER, 2, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "block",
            Map.of("type", "TEXT", "text", "这是一段最终回复。")
        )));

        verify(replayStore).append(argThat(event ->
            "SESSION_REPLY_DRAFT".equals(event.type())
                && StreamVisibility.CUSTOMER == event.visibility()
                && "session-message-reply-1".equals(event.messageId())
                && "block-1".equals(event.blockId())
                && "这是一段最终回复。".equals(event.text())
        ));
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldRejectCustomerReplyBlockCompletedWithInternalBlockFields() {
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        SessionRuntimeStreamService service = serviceWith(replayStore, pubSubBus);

        assertThrows(ResponseStatusException.class, () -> service.acceptStreamFrame(frame(
            AgentTurnStreamFrameKind.REPLY_BLOCK_COMPLETED,
            StreamVisibility.CUSTOMER,
            2,
            Map.of(
                "messageId",
                "session-message-reply-1",
                "blockId",
                "block-1",
                "block",
                Map.of("type", "TEXT", "text", "ok", "providerDebug", Map.of("model", "gpt-internal"))
            )
        )));

        verify(replayStore, org.mockito.Mockito.never()).append(any());
        verify(pubSubBus, org.mockito.Mockito.never()).publish(any(), any());
    }

    private static SessionRuntimeStreamService serviceWith(
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus
    ) {
        return serviceWith(replayStore, pubSubBus, new RedisKeyspace());
    }

    private static SessionRuntimeStreamService serviceWith(
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace
    ) {
        return new SessionRuntimeStreamService(
            mock(SessionRuntimeRepository.class),
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
    }

    private static SessionRuntimeSessionDto session(String sessionId, Instant updatedAt, long latestSequence) {
        return new SessionRuntimeSessionDto(
            sessionId,
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
            null,
            updatedAt.minusSeconds(5),
            updatedAt,
            null,
            latestSequence,
            latestSequence
        );
    }

    private static AgentTurnStreamFrame frame(
        AgentTurnStreamFrameKind kind,
        StreamVisibility visibility,
        long seq,
        Map<String, Object> payload
    ) {
        return frameAt(kind, visibility, seq, payload, Instant.parse("2026-05-02T00:00:00Z"));
    }

    private static AgentTurnStreamFrame frameAt(
        AgentTurnStreamFrameKind kind,
        StreamVisibility visibility,
        long seq,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        return new AgentTurnStreamFrame(
            AgentTurnStreamFrame.PROTOCOL,
            "exec-1:" + seq,
            "stream-1",
            "session-1",
            "turn-1",
            "exec-1",
            "agent-1",
            1,
            seq,
            kind,
            visibility,
            occurredAt,
            payload
        );
    }
}
