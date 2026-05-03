package com.lynxus.platform.session;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
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
    public void relay(AgentTurnStreamFrame frame) {
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

    private static List<ChannelOutboundActivityType> activityTypes(AgentTurnStreamFrame frame) {
        if (frame == null || frame.kind() == null) {
            return List.of();
        }
        if (frame.kind() == AgentTurnStreamFrameKind.FINAL_OUTCOME) {
            return List.of(ChannelOutboundActivityType.TYPING_STOP);
        }
        if (frame.visibility() == StreamVisibility.INTERNAL || frame.visibility() == StreamVisibility.DEVELOPER) {
            return List.of();
        }
        return switch (frame.kind()) {
            case TURN_STARTED -> List.of(ChannelOutboundActivityType.TYPING_START);
            case REPLY_BLOCK_DELTA -> customerOnly(frame, ChannelOutboundActivityType.DRAFT_UPDATE);
            case REPLY_BLOCK_COMPLETED -> List.of(ChannelOutboundActivityType.DRAFT_COMPLETE, ChannelOutboundActivityType.TYPING_STOP);
            case ERROR -> List.of(ChannelOutboundActivityType.DRAFT_DISCARD, ChannelOutboundActivityType.TYPING_STOP);
            case MODEL_STARTED,
                MODEL_COMPLETED,
                ACTION_TOOL_STARTED,
                ACTION_TOOL_COMPLETED,
                FINAL_OUTCOME -> List.of();
        };
    }

    private static List<ChannelOutboundActivityType> customerOnly(
        AgentTurnStreamFrame frame,
        ChannelOutboundActivityType activityType
    ) {
        return frame.visibility() == StreamVisibility.CUSTOMER ? List.of(activityType) : List.of();
    }

    private static Map<String, Object> activityPayload(
        AgentTurnStreamFrame frame,
        ChannelOutboundActivityType activityType
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activityType", activityType.name());
        payload.put("frameId", frame.frameId());
        if (isDraftActivity(activityType)) {
            Object messageId = frame.payload().get("messageId");
            if (messageId != null) {
                payload.put("messageId", messageId);
            }
        }
        Object blockId = frame.payload().get("blockId");
        if (blockId != null) {
            payload.put("blockId", blockId);
        }
        Object blockType = frame.payload().get("blockType");
        if (blockType != null) {
            payload.put("blockType", blockType);
        }
        Object delta = frame.payload().get("delta");
        if (delta != null) {
            payload.put("delta", delta);
        }
        Object text = frame.payload().get("text");
        if (text != null) {
            payload.put("text", text);
        }
        Object block = frame.payload().get("block");
        if (block != null) {
            payload.put("block", block);
        }
        return Map.copyOf(payload);
    }

    private static boolean isDraftActivity(ChannelOutboundActivityType activityType) {
        return activityType == ChannelOutboundActivityType.DRAFT_UPDATE
            || activityType == ChannelOutboundActivityType.DRAFT_COMPLETE
            || activityType == ChannelOutboundActivityType.DRAFT_DISCARD;
    }

    private static String activityIdempotencyKey(String frameId, ChannelOutboundActivityType activityType) {
        return "stream-frame:" + frameId + ":" + activityType.name();
    }
}
