drop table session_runtime_turn, session_runtime_event, session_runtime_message,
    session_runtime_playbook_run, session_runtime_session cascade;
drop table agent_tool_receipt;
drop table llm_usage_record;
create table llm_usage_record (
    id text primary key,
    source_type text not null,
    session_id text not null references agent_session(id),
    participant_id text not null references collaboration_actor(id),
    activation_id text not null references agent_activation(id),
    task_id text references collaboration_task(id),
    template_version_id text not null references catalog_role_template_version(id),
    provider_type text not null,
    model_resource_id text,
    model_resource_version_id text,
    model_id text not null,
    usage_available boolean not null,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    raw_usage jsonb not null,
    call_sequence integer not null,
    tool_loop_step integer not null,
    occurred_at timestamptz not null
);
create index llm_usage_session on llm_usage_record(session_id,occurred_at);
create index llm_usage_task on llm_usage_record(task_id,occurred_at);
alter table agent_tool_run add column dispatched_control text not null default 'ACTIVE';
alter table agent_activation add column reply_message_id text unique references collaboration_message(id);
