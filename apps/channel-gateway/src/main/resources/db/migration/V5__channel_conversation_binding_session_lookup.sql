create unique index uk_channel_binding_active_session
    on channel_conversation_binding (session_id)
    where session_id is not null and status = 'ACTIVE';

create index idx_channel_binding_session
    on channel_conversation_binding (session_id)
    where session_id is not null;
