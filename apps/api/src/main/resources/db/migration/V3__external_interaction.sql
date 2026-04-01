create table external_interaction_task (
    id varchar(64) primary key,
    interaction_type varchar(64) not null,
    status varchar(64) not null,
    session_id varchar(64) not null,
    task_id varchar(64) not null,
    workflow_instance_id varchar(64) not null,
    message_id varchar(64) not null,
    title varchar(255) not null,
    instruction text not null,
    provider varchar(128),
    provider_reference varchar(255),
    launch_url text,
    return_token varchar(128) not null,
    return_path text,
    expires_at timestamp with time zone,
    latest_result jsonb,
    last_event_source varchar(64) not null,
    resumed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table external_interaction_event (
    id varchar(64) primary key,
    interaction_task_id varchar(64) not null,
    event_type varchar(64) not null,
    event_source varchar(64) not null,
    dedupe_key varchar(255) not null,
    payload jsonb not null,
    result jsonb,
    created_at timestamp with time zone not null
);

create index idx_external_interaction_task_session on external_interaction_task (session_id, updated_at desc);
create index idx_external_interaction_task_workflow on external_interaction_task (workflow_instance_id);
create index idx_external_interaction_task_provider_ref on external_interaction_task (provider, provider_reference);
create unique index uk_external_interaction_task_message on external_interaction_task (message_id);
create index idx_external_interaction_event_task on external_interaction_event (interaction_task_id, created_at asc, id asc);
create unique index uk_external_interaction_event_dedupe on external_interaction_event (interaction_task_id, dedupe_key);
