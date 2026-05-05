create sequence channel_outbound_final_sequence;

alter table session_runtime_message
    add column final_sequence bigint not null default nextval('channel_outbound_final_sequence');

alter sequence channel_outbound_final_sequence
    owned by session_runtime_message.final_sequence;

create unique index uk_session_runtime_message_final_sequence
    on session_runtime_message (final_sequence);

create index idx_session_runtime_message_final_replay
    on session_runtime_message (final_sequence, session_id, role, status);
