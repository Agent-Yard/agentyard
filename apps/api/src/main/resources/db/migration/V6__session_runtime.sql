create table session_runtime_session (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    title varchar(255) not null,
    customer_id varchar(255) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    status varchar(32) not null,
    primary_agent_id varchar(64) not null,
    current_owner_agent_id varchar(64) not null,
    active_playbook_run_id varchar(64),
    agent_turn_active boolean not null,
    session_human_handoff_active boolean not null,
    pending_owner_reevaluation boolean not null,
    draining boolean not null,
    shared_state jsonb not null,
    idle_deadline timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    ended_at timestamp with time zone
);

create table session_runtime_event (
    event_id varchar(64) primary key,
    session_id varchar(64) not null,
    sequence bigint not null,
    event_type varchar(64) not null,
    created_at timestamp with time zone not null,
    actor_type varchar(32) not null,
    actor_id varchar(255),
    payload jsonb not null,
    related_playbook_run_id varchar(64),
    related_owner_agent_id varchar(64)
);

create table session_runtime_playbook_run (
    run_id varchar(64) primary key,
    session_id varchar(64) not null,
    parent_session_event_id varchar(64) not null,
    playbook_id varchar(64) not null,
    owner_agent_id varchar(64) not null,
    status varchar(32) not null,
    input jsonb not null,
    result jsonb not null,
    failure_reason text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    waiting_reason varchar(255)
);

create unique index uk_session_runtime_event_sequence on session_runtime_event (session_id, sequence);
create index idx_session_runtime_session_customer_assistant_status
    on session_runtime_session (customer_id, assistant_id, status, updated_at desc);
create index idx_session_runtime_session_updated on session_runtime_session (updated_at desc);
create index idx_session_runtime_event_session on session_runtime_event (session_id, sequence asc, created_at asc);
create index idx_session_runtime_playbook_run_session on session_runtime_playbook_run (session_id, updated_at desc);
