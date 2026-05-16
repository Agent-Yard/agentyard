create table channel_inbound_turn (
    turn_id varchar(64) primary key,
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    dedup_key varchar(255) not null,
    external_conversation_id varchar(255) not null,
    external_user_id varchar(255),
    normalized_payload jsonb not null,
    raw_payload jsonb not null,
    trace_context jsonb not null,
    metadata jsonb not null,
    status varchar(32) not null,
    session_id varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

alter table channel_inbound_turn
    add constraint ck_channel_inbound_turn_status
    check (status in (
        'RECEIVED',
        'DUPLICATE',
        'DISPATCHED',
        'PARTIALLY_DISPATCHED',
        'REJECTED',
        'FAILED'
    ));

create table channel_inbound_turn_message (
    turn_id varchar(64) not null,
    channel_profile_id varchar(64) not null,
    external_conversation_id varchar(255) not null,
    request_index integer not null,
    external_event_id varchar(255),
    external_message_id varchar(255) not null,
    occurred_at timestamp with time zone,
    role varchar(32) not null,
    sender jsonb not null,
    message_type varchar(64),
    text text,
    attachments jsonb not null,
    metadata jsonb not null,
    status varchar(32) not null,
    session_message_id varchar(64),
    duplicate_of_turn_id varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (turn_id, request_index)
);

alter table channel_inbound_turn_message
    add constraint ck_channel_inbound_turn_message_request_index
    check (request_index >= 0);

alter table channel_inbound_turn_message
    add constraint ck_channel_inbound_turn_message_role
    check (role in ('USER', 'ASSISTANT', 'HUMAN_OPERATOR', 'SYSTEM'));

alter table channel_inbound_turn_message
    add constraint ck_channel_inbound_turn_message_status
    check (status in ('RECEIVED', 'DUPLICATE', 'ACCEPTED', 'REJECTED'));

create table channel_inbound_message_dedupe (
    channel_profile_id varchar(64) not null,
    external_conversation_id varchar(255) not null,
    external_message_id varchar(255) not null,
    first_turn_id varchar(64) not null,
    first_request_index integer not null,
    session_id varchar(64),
    session_message_id varchar(64),
    created_at timestamp with time zone not null,
    primary key (channel_profile_id, external_conversation_id, external_message_id)
);

create unique index uk_channel_inbound_turn_dedup
    on channel_inbound_turn (dedup_key);

create index idx_channel_inbound_turn_profile_created
    on channel_inbound_turn (channel_profile_id, created_at desc);

create index idx_channel_inbound_turn_message_status
    on channel_inbound_turn_message (turn_id, status, request_index);
