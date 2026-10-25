package com.agentyard.platform.channel;

import com.agentyard.contracts.channel.ChannelContracts.*;
import java.time.Instant;
import org.jooq.DSLContext;

final class ChannelCollaborationFixture {
    static void reception(DSLContext dsl, String sessionId, String humanId, String entryId) {
        dsl.execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values (?,'HUMAN',?,?,now()) on conflict do nothing",humanId,humanId,humanId);
        dsl.execute("insert into collaboration_actor(id,kind,subject_id,display_name,created_at) values (?,'AGENT',?,?,now()) on conflict do nothing","agent-"+sessionId,sessionId,sessionId);
        dsl.execute("insert into catalog_role_template(id,name,description,owner_type,owner_id) values ('template','Reception','','PERSON',?) on conflict do nothing",humanId);
        dsl.execute("insert into catalog_role_template_version(id,template_id,version,status,created_at,configuration) values ('template-v1','template','1','PUBLISHED',now(),'{}') on conflict do nothing");
        dsl.execute("insert into catalog_entry(id,name,description,owner_type,owner_id,share_scope,reception_template_version_id) values (?,?,'','PERSON',?,'SHARED','template-v1') on conflict do nothing",entryId,entryId,humanId);
        dsl.execute("insert into agent_session(id,participant_id,template_version_id,name,status,created_by,created_at,updated_at) values (?,?,'template-v1',?,'ACTIVE',?,now(),now())",sessionId,"agent-"+sessionId,sessionId,humanId);
        dsl.execute("insert into agent_reception_binding(participant_id,entry_id,session_id,created_at) values (?,?,?,now())",humanId,entryId,sessionId);
    }

    static ChannelOutboundBindingSnapshot snapshot(String bindingId,String sessionId,String profileId,String conversation,String humanId,String entryId) {
        Instant now = Instant.parse("2026-10-04T00:00:00Z");
        return new ChannelOutboundBindingSnapshot(bindingId,sessionId,profileId,"feishu",conversation,"external-"+humanId,
            entryId,humanId,"ACTIVE",ChannelProfileStatus.ACTIVE,1,now,now,now);
    }

    static void verifiedIdentity(DSLContext dsl,String humanId,String profileId,String conversation) {
        new ChannelParticipantIdentityRepository(dsl).record(humanId,profileId,conversation,"external-"+humanId);
    }
}
