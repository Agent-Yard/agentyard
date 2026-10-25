package com.agentyard.platform.channel;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.*;
import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.platform.agent.AgentService;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Publishes only the public presentation of a verified reception Activation. */
@Service
public class AgentChannelActivityRelay {
    private final AgentStore agents;
    private final AgentService service;
    private final JooqChannelBindingSnapshotRepository bindings;
    private final ChannelOutboundFramePublisher publisher;

    public AgentChannelActivityRelay(AgentService service, JooqChannelBindingSnapshotRepository bindings,
        ChannelOutboundFramePublisher publisher) {
        this.service=service; this.agents=service.store(); this.bindings=bindings; this.publisher=publisher;
    }

    public boolean accept(String activationId, RuntimeFrame frame) {
        if (!"agentyard.activation-stream.v1".equals(frame.protocol()) || frame.seq()<1
            || frame.frameId()==null || frame.frameId().isBlank()) throw new IllegalArgumentException("invalid activation stream frame");
        var activation=agents.activation(activationId,false).orElseThrow(()->new IllegalArgumentException("activation not found"));
        var session=agents.session(activation.sessionId(),false).orElseThrow();
        if (!activationId.equals(frame.activationId()) || !session.id().equals(frame.sessionId())
            || !session.participantId().equals(frame.participantId())) throw new IllegalArgumentException("activation stream identity mismatch");
        if (activation.status()!=ActivationStatus.RUNNING || session.status()==SessionStatus.CLOSED) return false;
        var binding=bindings.findActiveBySessionId(session.id()).orElse(null);
        if (binding==null) return false;
        var trigger=agents.message(activation.inputMessageId()).orElseThrow();
        String recipient=service.replyRecipient(agents,session,trigger);
        if (!binding.participantId().equals(recipient)) return false;
        // Public model visibility is insufficient: the eventual recipient must still have work access.
        try { service.authorization().requireMessage(agents,session.participantId(),recipient,trigger.taskId()); }
        catch(ResponseStatusException inaccessible) { return false; }
        Map<String,Object> payload=frame.payload()==null?Map.of():frame.payload();
        // Draft identity belongs to this Activation. Final deliveries use the durable Message ID instead.
        String messageId=activationId+":reply";
        switch (frame.kind()) {
            case "TURN_STARTED" -> publish(binding,activation,frame,ChannelOutboundFrameKind.TYPING_START,Map.of("messageId",messageId));
            case "REPLY_BLOCK_DELTA" -> {
                if (!"CUSTOMER".equals(frame.visibility())) return false;
                publish(binding,activation,frame,ChannelOutboundFrameKind.DRAFT_UPDATE,Map.of("messageId",messageId,
                    "blockId",text(payload,"blockId"),"blockType","TEXT","delta",text(payload,"delta")));
            }
            case "REPLY_BLOCK_COMPLETED" -> {
                if (!"CUSTOMER".equals(frame.visibility())) return false;
                if (!(payload.get("block") instanceof Map<?,?> block) || !"TEXT".equals(block.get("type"))) return false;
                publish(binding,activation,frame,ChannelOutboundFrameKind.DRAFT_COMPLETE,Map.of("messageId",messageId,
                    "blockId",text(payload,"blockId"),"blockType","TEXT","block",Map.of("type","TEXT","text",Objects.toString(block.get("text"),""))));
            }
            case "ERROR" -> {
                publish(binding,activation,frame,ChannelOutboundFrameKind.DRAFT_DISCARD,Map.of("messageId",messageId));
                publish(binding,activation,frame,ChannelOutboundFrameKind.TYPING_STOP,Map.of("messageId",messageId));
            }
            case "FINAL_OUTCOME" -> publish(binding,activation,frame,ChannelOutboundFrameKind.TYPING_STOP,Map.of("messageId",messageId));
            default -> { return false; }
        }
        return true;
    }

    private void publish(ChannelOutboundBindingSnapshot binding, Activation activation, RuntimeFrame frame,
        ChannelOutboundFrameKind kind, Map<String,Object> payload) {
        String id=binding.channelProfileId()+":"+frame.frameId()+":"+kind;
        publisher.publishTransient(new ChannelOutboundFrame(ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,id,
            binding.channelProfileId(),binding.providerType(),binding.entryId(),binding.externalConversationId(),activation.sessionId(),
            activation.inputMessageId(),activation.id(),frame.seq(),null,kind,frame.occurredAt(),
            ChannelContracts.channelOutboundFrameIdempotencyKey(id),payload,null));
    }

    private static String text(Map<String,Object> payload,String key) {
        if (!(payload.get(key) instanceof String text)) throw new IllegalArgumentException("stream "+key+" is required");
        return text;
    }

    public record RuntimeFrame(String protocol,String frameId,String streamId,String sessionId,String activationId,
        String participantId,long seq,String kind,String visibility,Instant occurredAt,Map<String,Object> payload) {}
}
