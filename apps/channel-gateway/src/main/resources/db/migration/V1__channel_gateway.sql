create table channel_profile (
    id varchar(64) primary key,
    provider_type varchar(64) not null,
    name varchar(255) not null,
    status varchar(32) not null,
    config jsonb not null,
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

create table channel_outbound_delivery (
    delivery_id varchar(64) primary key,
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    session_id varchar(64),
    session_message_id varchar(64),
    external_conversation_id varchar(255),
    payload jsonb not null,
    status varchar(32) not null,
    attempt_count integer not null,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index uk_channel_conversation_binding_profile_external_conversation
    on channel_conversation_binding (channel_profile_id, external_conversation_id);
create unique index uk_channel_inbound_event_dedup_key on channel_inbound_event (dedup_key);
create index idx_channel_profile_updated on channel_profile (updated_at desc);
create index idx_channel_binding_profile_updated on channel_conversation_binding (channel_profile_id, updated_at desc);
create index idx_channel_inbound_profile_created on channel_inbound_event (channel_profile_id, created_at desc);
create index idx_channel_outbound_profile_created on channel_outbound_delivery (channel_profile_id, created_at desc);
