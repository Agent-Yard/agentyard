create table channel_profile (
    id varchar(64) primary key,
    provider_type varchar(64) not null,
    display_name varchar(255) not null,
    status varchar(32) not null,
    inbound_enabled boolean not null,
    config jsonb not null,
    assistant_binding jsonb not null,
    integration_account_id varchar(128),
    external_secret_ref varchar(512),
    revision bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table channel_conversation_binding (
    id varchar(64) primary key,
    channel_profile_id varchar(64) not null,
    external_conversation_id varchar(255) not null,
    external_user_id varchar(255),
    assistant_id varchar(64),
    customer_id varchar(255),
    session_id varchar(64),
    status varchar(32) not null,
    metadata jsonb not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table channel_inbound_event (
    event_id varchar(64) primary key,
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    event_type varchar(128) not null,
    external_event_id varchar(255),
    external_conversation_id varchar(255),
    external_message_id varchar(255),
    dedup_key varchar(255) not null,
    raw_payload jsonb not null,
    normalized_payload jsonb not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table channel_outbound_final_checkpoint (
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    consumer_kind varchar(32) not null,
    consumer_id varchar(255) not null,
    registration_id varchar(128),
    last_acked_final_sequence bigint,
    last_acked_final_frame_id varchar(512),
    last_acked_session_id varchar(64),
    last_acked_session_message_id varchar(64),
    last_acked_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_channel_outbound_final_checkpoint
        primary key (channel_profile_id, provider_type, consumer_kind, consumer_id)
);

create unique index uk_channel_conversation_binding_profile_external_conversation
    on channel_conversation_binding (channel_profile_id, external_conversation_id);
create unique index uk_channel_inbound_event_dedup_key on channel_inbound_event (dedup_key);
create index idx_channel_profile_updated on channel_profile (updated_at desc);
create index idx_channel_binding_profile_updated on channel_conversation_binding (channel_profile_id, updated_at desc);
create index idx_channel_inbound_profile_created on channel_inbound_event (channel_profile_id, created_at desc);
