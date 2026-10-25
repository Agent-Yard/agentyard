package com.agentyard.platform.channel;

import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Durable channel deliveries derived from participant messages, independently of Agent execution. */
@Repository
public class ChannelCollaborationOutbox {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public ChannelCollaborationOutbox(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }

    public List<String> materialize() {
        return dsl.fetch("""
            insert into channel_collaboration_outbox(channel_profile_id,binding_id,message_id)
            select b.channel_profile_id,b.binding_id,m.id
            from collaboration_message m
            join channel_session_binding_snapshot b on b.participant_id=m.recipient_id
            join channel_participant_identity i on i.participant_id=b.participant_id and i.channel_profile_id=b.channel_profile_id
                and i.external_conversation_id=b.external_conversation_id and i.external_user_id=b.external_user_id
            join agent_reception_binding r on r.session_id=b.session_id and r.participant_id=b.participant_id and r.entry_id=b.entry_id
            join agent_session s on s.id=r.session_id
            join catalog_entry e on e.id=r.entry_id
            where b.binding_status='ACTIVE' and b.profile_status='ACTIVE' and s.status<>'CLOSED' and e.enabled
              and not exists(select 1 from channel_collaboration_outbox o where o.binding_id=b.binding_id and o.message_id=m.id)
            order by m.created_at,m.id,b.binding_id limit 1000
            on conflict(binding_id,message_id) do nothing
            returning channel_profile_id
            """).getValues("channel_profile_id", String.class).stream().distinct().toList();
    }

    public List<ChannelOutboundFrame> listFinalFrames(String profileId, long afterSequence, int limit) {
        return dsl.fetch("""
            select o.sequence,m.id,m.reply_to_message_id,m.sender_id,m.body,m.created_at,a.id as activation_id,
                   b.channel_profile_id,b.provider_type,b.entry_id,b.external_conversation_id,b.session_id
            from channel_collaboration_outbox o
            join collaboration_message m on m.id=o.message_id
            left join agent_activation a on a.reply_message_id=m.id
            join channel_session_binding_snapshot b on b.binding_id=o.binding_id
            join channel_participant_identity i on i.participant_id=b.participant_id and i.channel_profile_id=b.channel_profile_id
                and i.external_conversation_id=b.external_conversation_id and i.external_user_id=b.external_user_id
            join agent_reception_binding r on r.session_id=b.session_id and r.participant_id=b.participant_id and r.entry_id=b.entry_id
            join agent_session s on s.id=r.session_id
            join catalog_entry e on e.id=r.entry_id
            where o.channel_profile_id=? and o.sequence>? and b.binding_status='ACTIVE' and b.profile_status='ACTIVE'
              and s.status<>'CLOSED' and e.enabled
            order by o.sequence limit ?
            """, profileId, afterSequence, limit).map(row -> {
                Map<String, Object> body = mapper.readValue(row.get("body", JSONB.class).data(), new TypeReference<>() {});
                List<?> blocks = messageBlocks(body);
                long sequence = row.get("sequence", Long.class);
                String messageId = row.get("id", String.class);
                String frameId = profileId + ":" + sequence + ":FINAL_DELIVERY";
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sessionMessageId", messageId);
                payload.put("messageSequence", sequence);
                payload.put("messageBlocks", blocks);
                payload.put("senderParticipantId", row.get("sender_id", String.class));
                String activationId=row.get("activation_id",String.class);
                if(activationId!=null) payload.put("draftMessageId",activationId+":reply");
                return new ChannelOutboundFrame(ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL, frameId, profileId,
                    row.get("provider_type", String.class), row.get("entry_id", String.class),
                    row.get("external_conversation_id", String.class), row.get("session_id", String.class),
                    null, null, null, sequence, ChannelOutboundFrameKind.FINAL_DELIVERY,
                    row.get("created_at", OffsetDateTime.class).toInstant(), ChannelContracts.channelOutboundFrameIdempotencyKey(frameId), payload, null);
            });
    }

    private List<?> messageBlocks(Map<String, Object> body) {
        if (body.get("blocks") instanceof List<?> blocks && !blocks.isEmpty()) return blocks;
        String text = body.get("text") instanceof String value ? value : body.get("summary") instanceof String summary ? summary : "工作有新进展，请在协作入口查看。";
        return List.of(Map.of("type", "TEXT", "text", text));
    }
}
