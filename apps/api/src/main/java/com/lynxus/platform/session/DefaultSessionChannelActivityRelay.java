package com.lynxus.platform.session;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.ErrorPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockDeltaPayload;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.contracts.session.SessionContracts.TurnCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.TurnStartedPayload;
import com.lynxus.platform.channel.ChannelGatewayClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
final class DefaultSessionChannelActivityRelay implements SessionChannelActivityRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSessionChannelActivityRelay.class);

    private final ChannelGatewayClient channelGatewayClient;

    DefaultSessionChannelActivityRelay(ChannelGatewayClient channelGatewayClient) {
        this.channelGatewayClient = channelGatewayClient;
    }

    @Override
    public void relay(AgentTurnTransientFrame frame) {
        List<ChannelOutboundActivityType> activityTypes = activityTypes(frame);
        if (activityTypes.isEmpty()) {
            return;
        }
        ChannelConversationBinding binding;
        try {
            binding = channelGatewayClient.getBindingBySession(frame.sessionId());
        } catch (NoSuchElementException missingBinding) {
            return;
        } catch (RuntimeException error) {
            LOGGER.warn(
                "failed to look up channel binding for activity relay sessionId={} frameId={}",
                frame.sessionId(),
                frame.frameId(),
                error
            );
            return;
        }
        if (binding == null
            || binding.channelProfileId() == null
            || binding.assistantId() == null
            || binding.externalConversationId() == null) {
            return;
        }
        for (ChannelOutboundActivityType activityType : activityTypes) {
            try {
                channelGatewayClient.sendOutboundActivity(new ChannelOutboundActivityRequest(
                    binding.channelProfileId(),
                    binding.assistantId(),
                    binding.externalConversationId(),
                    frame.sessionId(),
                    frame.turnId(),
                    frame.frameId(),
                    activityType,
                    activityIdempotencyKey(frame.frameId(), activityType),
                    activityPayload(frame, activityType),
                    null
                ));
            } catch (RuntimeException error) {
                LOGGER.warn(
                    "failed to relay channel activity sessionId={} frameId={} activityType={}",
                    frame.sessionId(),
                    frame.frameId(),
                    activityType,
                    error
                );
            }
        }
    }

    private static List<ChannelOutboundActivityType> activityTypes(AgentTurnTransientFrame frame) {
        if (frame == null || frame.kind() == null) {
            return List.of();
        }
        if (frame.visibility() == StreamVisibility.INTERNAL || frame.visibility() == StreamVisibility.DEVELOPER) {
            return List.of();
        }
        return switch (frame.kind()) {
            case TURN_STARTED -> List.of(ChannelOutboundActivityType.TYPING_START);
            case REPLY_BLOCK_DELTA -> customerOnly(frame, ChannelOutboundActivityType.DRAFT_UPDATE);
            case REPLY_BLOCK_COMPLETED -> List.of(ChannelOutboundActivityType.DRAFT_COMPLETE, ChannelOutboundActivityType.TYPING_STOP);
            case TURN_COMPLETED -> List.of(ChannelOutboundActivityType.TYPING_STOP);
            case ERROR -> List.of(ChannelOutboundActivityType.DRAFT_DISCARD, ChannelOutboundActivityType.TYPING_STOP);
            case MODEL_STARTED,
                MODEL_COMPLETED,
                ACTION_TOOL_STARTED,
                ACTION_TOOL_COMPLETED -> List.of();
        };
    }

    private static List<ChannelOutboundActivityType> customerOnly(
        AgentTurnTransientFrame frame,
        ChannelOutboundActivityType activityType
    ) {
        return frame.visibility() == StreamVisibility.CUSTOMER ? List.of(activityType) : List.of();
    }

    private static Map<String, Object> activityPayload(
        AgentTurnTransientFrame frame,
        ChannelOutboundActivityType activityType
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activityType", activityType.name());
        payload.put("frameId", frame.frameId());
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

    private static String activityIdempotencyKey(String frameId, ChannelOutboundActivityType activityType) {
        return "stream-frame:" + frameId + ":" + activityType.name();
    }
}
