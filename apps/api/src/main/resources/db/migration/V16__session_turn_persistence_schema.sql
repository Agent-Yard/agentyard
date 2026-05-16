alter table session_runtime_session
    add column entry_scope varchar(32) not null,
    add column channel_profile_id varchar(64),
    add column external_conversation_id varchar(255),
    add column next_message_sequence bigint not null default 1,
    add column shared_state_revision bigint not null default 0;

alter table session_runtime_session
    add constraint ck_session_runtime_session_entry_scope
    check (entry_scope in ('WEB', 'CHANNEL'));

alter table session_runtime_session
    add constraint ck_session_runtime_session_channel_identity
    check (
        (
            entry_scope = 'WEB'
            and channel_profile_id is null
            and external_conversation_id is null
        )
        or
        (
            entry_scope = 'CHANNEL'
            and channel_profile_id is not null
            and external_conversation_id is not null
        )
    );

create unique index uk_session_runtime_active_web
    on session_runtime_session (customer_id, assistant_id)
    where entry_scope = 'WEB' and status <> 'ENDED';

create unique index uk_session_runtime_active_channel
    on session_runtime_session (
        channel_profile_id,
        external_conversation_id,
        customer_id,
        assistant_id
    )
    where entry_scope = 'CHANNEL' and status <> 'ENDED';

create table session_runtime_turn (
    turn_id varchar(64) primary key,
    session_id varchar(64) not null,
    dedup_key varchar(128) not null,
    trigger_type varchar(64) not null,
    status varchar(32) not null,
    input_allocations jsonb not null,
    accepted_input_message_ids jsonb not null,
    duplicate_external_message_ids jsonb not null,
    message_ids jsonb not null,
    temporal_update_id varchar(128),
    metadata jsonb not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

alter table session_runtime_turn
    add constraint ck_session_runtime_turn_status
    check (status in (
        'ALLOCATED_IDS',
        'MESSAGES_APPENDED',
        'WORKFLOW_ACCEPTED',
        'REJECTED',
        'FAILED'
    ));

create unique index uk_session_runtime_turn_dedup
    on session_runtime_turn (session_id, dedup_key);

create index idx_session_runtime_turn_session_created
    on session_runtime_turn (session_id, created_at desc);

alter table session_runtime_message
    add column turn_id varchar(64) not null,
    add column turn_index integer not null,
    add column producer_type varchar(32) not null,
    add column external_message_id varchar(255),
    add column client_message_id varchar(255),
    add column occurred_at timestamp with time zone;

alter table session_runtime_message
    add constraint ck_session_runtime_message_turn_index_nonnegative
    check (turn_index >= 0);

alter table session_runtime_message
    add constraint ck_session_runtime_message_producer_type
    check (producer_type in ('EXTERNAL', 'PLATFORM'));

create unique index uk_session_runtime_message_turn_index
    on session_runtime_message (session_id, turn_id, turn_index);

create unique index uk_session_runtime_message_external
    on session_runtime_message (session_id, external_message_id)
    where external_message_id is not null;

create index idx_session_runtime_message_turn
    on session_runtime_message (session_id, turn_id, turn_index);

create index idx_session_runtime_message_outbound
    on session_runtime_message (producer_type, role, status, final_sequence);
