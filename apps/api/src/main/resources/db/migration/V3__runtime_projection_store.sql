drop table if exists human_intervention;
drop table if exists conversation_message;
drop table if exists conversation_session;
drop table if exists workflow_instance;
drop table if exists task_instance;

create table task_instance (
    id varchar(64) primary key,
    session_id varchar(64),
    scenario_id varchar(64) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    question text not null,
    requester varchar(255) not null,
    status varchar(32) not null,
    workflow_instance_id varchar(64) not null,
    created_at timestamp with time zone not null
);

create table workflow_instance (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    status varchar(32) not null,
    summary text,
    final_reply text,
    current_node_key varchar(255),
    escalation_required boolean not null default false,
    checkpoint jsonb,
    human_task jsonb,
    pause_reason jsonb,
    latest_tool_outcome jsonb,
    resource_anchors jsonb not null,
    nodes jsonb not null,
    tool_calls jsonb not null,
    loaded_skill_resource_version_ids jsonb not null,
    shared_state jsonb not null
);

create table conversation_session (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    title varchar(255) not null,
    requester varchar(255) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    latest_task_id varchar(64),
    latest_workflow_instance_id varchar(64),
    latest_tool_outcome jsonb,
    latest_human_task jsonb,
    latest_pause_reason jsonb,
    loaded_skill_resource_version_ids jsonb not null,
    shared_state jsonb not null
);

create table conversation_message (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    role varchar(32) not null,
    sender_type varchar(32) not null,
    sender_id varchar(64),
    sender_name varchar(255) not null,
    content text not null,
    created_at timestamp with time zone not null,
    task_id varchar(64),
    workflow_instance_id varchar(64)
);

create table human_intervention (
    id varchar(64) primary key,
    workflow_instance_id varchar(64) not null,
    action varchar(64) not null,
    operator_id varchar(255) not null,
    comment text,
    attributes jsonb not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    applied_at timestamp with time zone,
    failure_reason text
);

create index idx_task_instance_workflow on task_instance (workflow_instance_id);
create index idx_task_instance_status on task_instance (status);
create index idx_workflow_instance_status on workflow_instance (status, updated_at desc);
create index idx_conversation_session_updated on conversation_session (updated_at desc);
create index idx_conversation_session_latest_workflow on conversation_session (latest_workflow_instance_id);
create index idx_conversation_message_session on conversation_message (session_id, created_at asc, id asc);
create index idx_human_intervention_workflow on human_intervention (workflow_instance_id, created_at asc, id asc);
