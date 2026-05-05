create table channel_session_binding_snapshot (
    binding_id varchar(64) primary key,
    session_id varchar(64),
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    external_conversation_id varchar(255),
    external_user_id varchar(255),
    assistant_id varchar(64),
    customer_id varchar(255),
    binding_status varchar(32) not null,
    profile_status varchar(32) not null,
    profile_revision bigint not null,
    binding_updated_at timestamp with time zone,
    profile_updated_at timestamp with time zone,
    updated_at timestamp with time zone not null
);

create unique index uk_channel_session_binding_snapshot_active_session
    on channel_session_binding_snapshot (session_id)
    where binding_status = 'ACTIVE' and profile_status = 'ACTIVE' and session_id is not null;

create unique index uk_channel_session_binding_snapshot_active_profile_conversation
    on channel_session_binding_snapshot (channel_profile_id, external_conversation_id)
    where binding_status = 'ACTIVE' and profile_status = 'ACTIVE' and external_conversation_id is not null;

create index idx_channel_session_binding_snapshot_session
    on channel_session_binding_snapshot (session_id);

create index idx_channel_session_binding_snapshot_profile_session
    on channel_session_binding_snapshot (channel_profile_id, session_id);

create index idx_channel_session_binding_snapshot_profile_conversation
    on channel_session_binding_snapshot (channel_profile_id, external_conversation_id);

create index idx_channel_session_binding_snapshot_updated
    on channel_session_binding_snapshot (updated_at, binding_id);
