package com.agentyard.platform.channel;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelParticipantIdentityRepository {
    private final DSLContext db;
    public ChannelParticipantIdentityRepository(DSLContext db) { this.db=db; }
    public void record(String participantId,String profileId,String conversationId,String externalUserId) {
        db.execute("""
            insert into channel_participant_identity(participant_id,channel_profile_id,external_conversation_id,external_user_id)
            values (?,?,?,?) on conflict do nothing
            """,participantId,profileId,conversationId,externalUserId);
        if(!db.fetchExists(db.selectOne().from("channel_participant_identity")
            .where("participant_id=? and channel_profile_id=? and external_conversation_id=? and external_user_id=?",
                participantId,profileId,conversationId,externalUserId))) {
            throw new IllegalStateException("channel participant identity conflicts with the verified identity");
        }
    }
    static Condition matchesSnapshot() {
        return DSL.condition("""
            exists(select 1 from channel_participant_identity i
                where i.participant_id=channel_session_binding_snapshot.participant_id
                and i.channel_profile_id=channel_session_binding_snapshot.channel_profile_id
                and i.external_conversation_id=channel_session_binding_snapshot.external_conversation_id
                and i.external_user_id=channel_session_binding_snapshot.external_user_id)
            """);
    }
}
