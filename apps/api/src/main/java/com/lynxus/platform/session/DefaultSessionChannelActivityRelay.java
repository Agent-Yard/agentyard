package com.lynxus.platform.session;

import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.ErrorPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockDeltaPayload;
import com.lynxus.contracts.session.SessionContracts.SessionMessageBlockType;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.contracts.session.SessionContracts.TurnCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.TurnStartedPayload;
import com.lynxus.platform.channel.ChannelBindingSnapshotLookupService;
import com.lynxus.platform.channel.ChannelOutboundFramePublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
final class DefaultSessionChannelActivityRelay implements SessionChannelActivityRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSessionChannelActivityRelay.class);

    private final ChannelBindingSnapshotLookupService bindingLookupService;
    private final ChannelOutboundFramePublisher framePublisher;

    DefaultSessionChannelActivityRelay(
        ChannelBindingSnapshotLookupService bindingLookupService,
        ChannelOutboundFramePublisher framePublisher
    ) {
        this.bindingLookupService = bindingLookupService;
        this.framePublisher = framePublisher;
    }

    @Override
    public void relay(AgentTurnTransientFrame frame) {
        List<ChannelOutboundFrameKind> frameKinds = frameKinds(frame);
        if (frameKinds.isEmpty()) {
            return;
        }
        Optional<ChannelOutboundBindingSnapshot> snapshot;
        try {
            snapshot = bindingLookupService.findActiveBySession(frame.sessionId());
        } catch (RuntimeException error) {
            LOGGER.warn(
                "failed to look up channel binding snapshot for frame relay sessionId={} frameId={}",
                frame.sessionId(),
                frame.frameId(),
                error
            );
            return;
        }
        if (snapshot.isEmpty()) {
            return;
        }
        ChannelOutboundBindingSnapshot binding = snapshot.orElseThrow();
        if (binding == null
            || binding.channelProfileId() == null
            || binding.providerType() == null
            || binding.assistantId() == null
            || binding.externalConversationId() == null) {
            return;
        }
        for (ChannelOutboundFrameKind frameKind : frameKinds) {
            try {
                framePublisher.publishTransient(toFrame(binding, frame, frameKind));
            } catch (RuntimeException error) {
                LOGGER.warn(
                    "failed to publish channel outbound transient frame sessionId={} frameId={} frameKind={}",
                    frame.sessionId(),
                    frame.frameId(),
                    frameKind,
                    error
                );
            }
        }
    }

    private static List<ChannelOutboundFrameKind> frameKinds(AgentTurnTransientFrame frame) {
        if (frame == null || frame.kind() == null) {
            return List.of();
        }
        if (frame.visibility() == StreamVisibility.INTERNAL || frame.visibility() == StreamVisibility.DEVELOPER) {
            return List.of();
        }
        return switch (frame.kind()) {
            case TURN_STARTED -> List.of(ChannelOutboundFrameKind.TYPING_START);
            case REPLY_BLOCK_DELTA -> customerOnly(frame, ChannelOutboundFrameKind.DRAFT_UPDATE);
            case REPLY_BLOCK_COMPLETED -> List.of(ChannelOutboundFrameKind.DRAFT_COMPLETE, ChannelOutboundFrameKind.TYPING_STOP);
            case TURN_COMPLETED -> List.of(ChannelOutboundFrameKind.TYPING_STOP);
            case ERROR -> List.of(ChannelOutboundFrameKind.DRAFT_DISCARD, ChannelOutboundFrameKind.TYPING_STOP);
            case MODEL_STARTED,
                MODEL_COMPLETED,
                ACTION_TOOL_STARTED,
                ACTION_TOOL_COMPLETED -> List.of();
        };
    }

    private static List<ChannelOutboundFrameKind> customerOnly(
        AgentTurnTransientFrame frame,
        ChannelOutboundFrameKind frameKind
    ) {
        return frame.visibility() == StreamVisibility.CUSTOMER ? List.of(frameKind) : List.of();
    }

    private static ChannelOutboundFrame toFrame(
        ChannelOutboundBindingSnapshot binding,
        AgentTurnTransientFrame frame,
        ChannelOutboundFrameKind frameKind
    ) {
        String frameId = binding.channelProfileId() + ":" + frame.turnExecutionId() + ":" + frame.seq() + ":" + frameKind.name();
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            binding.channelProfileId(),
            binding.providerType(),
            binding.assistantId(),
            binding.externalConversationId(),
            frame.sessionId(),
            frame.turnId(),
            frame.turnExecutionId(),
            frame.seq(),
            null,
            frameKind,
            frame.occurredAt(),
            ChannelContracts.channelOutboundFrameIdempotencyKey(frameId),
            framePayload(frame, frameKind),
            null
        );
    }

    private static Map<String, Object> framePayload(
        AgentTurnTransientFrame frame,
        ChannelOutboundFrameKind frameKind
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (messageId(frame) != null) {
            payload.put("messageId", messageId(frame));
        }
        String blockId = blockId(frame);
        if (blockId != null) {
            payload.put("blockId", blockId);
        }
        String blockType = blockType(frame);
        if (blockType != null) {
            payload.put("blockType", blockType);
        }
        String delta = delta(frame);
        if (delta != null) {
            payload.put("delta", delta);
        }
        Object block = block(frame);
        if (block != null) {
            payload.put("block", block);
            payload.putIfAbsent("blockType", blockTypeFromBlock(block));
        }
        if (frameKind == ChannelOutboundFrameKind.TYPING_START || frameKind == ChannelOutboundFrameKind.TYPING_STOP) {
            payload.keySet().retainAll(java.util.Set.of("messageId"));
        }
        if (frameKind == ChannelOutboundFrameKind.DRAFT_DISCARD && frame.payload() instanceof ErrorPayload errorPayload) {
            payload.put("reason", errorPayload.code());
        }
        return Map.copyOf(payload);
    }

    private static String messageId(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            return payload.messageId();
        }
        if (frame.payload() instanceof ReplyBlockCompletedPayload payload) {
            return payload.messageId();
        }
        if (frame.payload() instanceof ErrorPayload payload) {
            return payload.messageId();
        }
        if (frame.payload() instanceof TurnStartedPayload payload) {
            return payload.messageId();
        }
        if (frame.payload() instanceof TurnCompletedPayload payload) {
            return payload.messageId();
        }
        return null;
    }

    private static String blockId(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            return payload.blockId();
        }
        if (frame.payload() instanceof ReplyBlockCompletedPayload payload) {
            return payload.blockId();
        }
        return null;
    }

    private static String blockType(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload && payload.blockType() != null) {
            return payload.blockType().name();
        }
        return null;
    }

    private static String delta(AgentTurnTransientFrame frame) {
        return frame.payload() instanceof ReplyBlockDeltaPayload payload ? payload.delta() : null;
    }

    private static Object block(AgentTurnTransientFrame frame) {
        return frame.payload() instanceof ReplyBlockCompletedPayload payload ? payload.block() : null;
    }

    private static String blockTypeFromBlock(Object block) {
        if (block instanceof Map<?, ?> blockMap) {
            Object type = blockMap.get("type");
            return type == null ? SessionMessageBlockType.TEXT.name() : String.valueOf(type);
        }
        if (block instanceof com.lynxus.contracts.session.SessionContracts.TextMessageBlock) {
            return SessionMessageBlockType.TEXT.name();
        }
        return SessionMessageBlockType.TEXT.name();
    }
}
