package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.session.SessionContracts.SessionMessageRole;
import com.agentyard.persistence.session.SessionRuntimeStore;
import com.agentyard.platform.channel.ChannelOutboundFramePublisher.FinalReplayWindowExhausted;
import com.agentyard.platform.session.SessionRuntimeRepository;
import com.agentyard.platform.shared.redis.RedisSharedStateProperties;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import com.agentyard.shared.redis.RedisPubSubBus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundFramePublisherTest {
    private final List<ChannelOutboundFramePublisher> publishers = new ArrayList<>();

    @AfterEach
    void closePublishers() throws Exception {
        for (ChannelOutboundFramePublisher publisher : publishers) {
            publisher.close();
        }
        publishers.clear();
    }

    @Test
    void lastEventIdMissDoesNotBlockDurableFinalReplay() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        TrackingSseEmitter emitter = new TrackingSseEmitter();
        ChannelOutboundFramePublisher publisher = publisher(repository, pubSubBus, emitter);
        when(repository.listChannelOutboundFinalMessages("channel-profile-1", 0L, 101))
            .thenReturn(List.of(finalMessage(11L, "message-11")));
        publisher.publishTransient(transientFrame(1L));

        publisher.connect("channel-profile-1", "missing-cursor", null, null);

        List<ChannelOutboundFrame> frames = emitter.frames();
        assertEquals(1, frames.size());
        assertEquals(ChannelOutboundFrameKind.FINAL_DELIVERY, frames.get(0).kind());
        assertEquals(11L, frames.get(0).finalSequence());
        verify(pubSubBus).publish(eq(new RedisKeyspace().channelOutboundFrameChannel()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emitsWindowExhaustedWhenFinalReplayBacklogExceedsMax() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        TrackingSseEmitter emitter = new TrackingSseEmitter();
        ChannelOutboundFramePublisher publisher = publisher(repository, mock(RedisPubSubBus.class), emitter);
        when(repository.listChannelOutboundFinalMessages("channel-profile-1", 0L, 3)).thenReturn(List.of(
            finalMessage(7L, "message-7"),
            finalMessage(8L, "message-8"),
            finalMessage(9L, "message-9")
        ));

        publisher.connect("channel-profile-1", null, null, 2);

        List<ChannelOutboundFrame> frames = emitter.frames();
        assertEquals(List.of(7L, 8L), frames.stream().map(ChannelOutboundFrame::finalSequence).toList());
        assertEquals(new FinalReplayWindowExhausted(2, 8L), emitter.exhaustedEvents().get(0));
        assertEquals(1, emitter.completeCalls());
    }

    @Test
    void finalReplayStartsAfterCheckpointAndUsesStableFinalFrameShape() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        TrackingSseEmitter emitter = new TrackingSseEmitter();
        ChannelOutboundFramePublisher publisher = publisher(repository, mock(RedisPubSubBus.class), emitter);
        when(repository.listChannelOutboundFinalMessages("channel-profile-1", 10L, 101))
            .thenReturn(List.of(finalMessage(12L, "message-12")));

        publisher.connect("channel-profile-1", null, 10L, null);

        ChannelOutboundFrame frame = emitter.frames().get(0);
        assertEquals("channel-profile-1:session-1:message-12:FINAL_DELIVERY", frame.frameId());
        assertEquals(ChannelContracts.channelOutboundFrameIdempotencyKey(frame.frameId()), frame.idempotencyKey());
        assertNotEquals(frame.frameId(), frame.idempotencyKey());
        assertTrue(frame.idempotencyKey().length() <= 50);
        assertEquals(12L, frame.finalSequence());
        assertEquals("message-12", frame.payload().get("sessionMessageId"));
        assertEquals(3L, frame.payload().get("messageSequence"));
        assertEquals(List.of(Map.of("type", "TEXT", "text", "hello message-12")), frame.payload().get("messageBlocks"));
    }

    @Test
    void emitsHeartbeatAsCommentWithoutCursorEventOrData() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        TrackingSseEmitter emitter = new TrackingSseEmitter();
        ChannelOutboundFramePublisher publisher = publisher(repository, mock(RedisPubSubBus.class), emitter);
        when(repository.listChannelOutboundFinalMessages("channel-profile-1", 10L, 101))
            .thenReturn(List.of())
            .thenReturn(List.of(finalMessage(12L, "message-12")));

        publisher.connect("channel-profile-1", null, 10L, null);
        publisher.emitHeartbeats();
        publisher.publishTransient(transientFrame(1L));
        publisher.notifyFinalAvailableForSession("channel-profile-1");

        assertEquals(List.of(":channel-outbound-heartbeat\n\n"), emitter.commentEvents());
        assertEquals(List.of("id:instance-a:1\nevent:channel-outbound-frame\ndata:", "id:instance-a:2\nevent:channel-outbound-frame\ndata:"), emitter.framePrefixes());
        assertEquals(List.of(12L), emitter.frames().stream()
            .filter(frame -> frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY)
            .map(ChannelOutboundFrame::finalSequence)
            .toList());
    }

    private ChannelOutboundFramePublisher publisher(
        SessionRuntimeRepository repository,
        RedisPubSubBus pubSubBus,
        TrackingSseEmitter emitter
    ) {
        ChannelOutboundFramePublisher publisher = new ChannelOutboundFramePublisher(
            repository,
            pubSubBus,
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties(
                "instance-a",
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                16,
                Duration.ofSeconds(1)
            ),
            new ChannelOutboundFrameStreamProperties(Duration.ofSeconds(20)),
            ignored -> emitter,
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "channel-outbound-frame-publisher-test-heartbeat");
                thread.setDaemon(true);
                return thread;
            })
        );
        publishers.add(publisher);
        return publisher;
    }

    private static SessionRuntimeStore.ChannelOutboundFinalMessageData finalMessage(long finalSequence, String messageId) {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        return new SessionRuntimeStore.ChannelOutboundFinalMessageData(
            finalSequence,
            messageId,
            "session-1",
            3L,
            SessionMessageRole.ASSISTANT,
            List.of(Map.of("type", "TEXT", "text", "hello " + messageId)),
            Map.of(),
            now,
            now,
            "channel-profile-1",
            "provider.acme",
            "chat-1",
            "assistant-1"
        );
    }

    private static ChannelOutboundFrame transientFrame(long sourceSeq) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "channel-profile-1:exec-1:" + sourceSeq + ":TYPING_START",
            "channel-profile-1",
            "provider.acme",
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.TYPING_START,
            Instant.parse("2026-05-02T00:00:00Z"),
            "channel-profile-1:exec-1:" + sourceSeq + ":TYPING_START",
            Map.of("replyMessageId", "message-1"),
            null
        );
    }

    private static final class TrackingSseEmitter extends SseEmitter {
        private final List<Object> sentData = new ArrayList<>();
        private int completeCalls;

        private TrackingSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            builder.build().forEach(data -> sentData.add(data.getData()));
        }

        @Override
        public void complete() {
            completeCalls += 1;
        }

        private List<ChannelOutboundFrame> frames() {
            return sentData.stream()
                .filter(ChannelOutboundFrame.class::isInstance)
                .map(ChannelOutboundFrame.class::cast)
                .toList();
        }

        private List<FinalReplayWindowExhausted> exhaustedEvents() {
            return sentData.stream()
                .filter(FinalReplayWindowExhausted.class::isInstance)
                .map(FinalReplayWindowExhausted.class::cast)
                .toList();
        }

        private List<String> commentEvents() {
            return sentData.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.startsWith(":"))
                .toList();
        }

        private List<String> framePrefixes() {
            return sentData.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.contains("event:channel-outbound-frame"))
                .toList();
        }

        private int completeCalls() {
            return completeCalls;
        }
    }
}
