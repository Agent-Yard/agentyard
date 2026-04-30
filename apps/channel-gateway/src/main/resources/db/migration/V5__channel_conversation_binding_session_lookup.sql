create index idx_channel_binding_session
    on channel_conversation_binding (session_id)
    where session_id is not null;
