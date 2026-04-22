create table llm_usage_record (
    id varchar(64) primary key,
    source_type varchar(64) not null,
    session_id varchar(64) not null,
    trigger_event_id varchar(64) not null,
    trigger_type varchar(64) not null,
    playbook_run_id varchar(64),
    scenario_id varchar(64) not null,
    customer_id varchar(255) not null,
    assistant_id varchar(64) not null,
    assistant_release_version varchar(64) not null,
    agent_id varchar(64) not null,
    provider_type varchar(64) not null,
    model_resource_id varchar(64),
    model_resource_version_id varchar(64),
    model_id varchar(255) not null,
    usage_available boolean not null,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    raw_usage jsonb not null,
    call_sequence integer not null,
    tool_loop_step integer not null,
    occurred_at timestamp with time zone not null
);

create index idx_llm_usage_record_session_occurred
    on llm_usage_record (session_id, occurred_at desc);

create index idx_llm_usage_record_playbook_occurred
    on llm_usage_record (playbook_run_id, occurred_at desc);

create index idx_llm_usage_record_assistant_occurred
    on llm_usage_record (assistant_id, occurred_at desc);

create index idx_llm_usage_record_scenario_occurred
    on llm_usage_record (scenario_id, occurred_at desc);

create index idx_llm_usage_record_customer_occurred
    on llm_usage_record (customer_id, occurred_at desc);
