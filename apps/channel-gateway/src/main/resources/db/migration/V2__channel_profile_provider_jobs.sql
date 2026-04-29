create table channel_profile_job (
    id varchar(64) primary key,
    channel_profile_id varchar(64) not null references channel_profile(id),
    job_type varchar(128) not null,
    status varchar(32) not null,
    schedule_config jsonb not null,
    cursor text,
    last_run_id varchar(64),
    last_run_at timestamp with time zone,
    last_success_at timestamp with time zone,
    last_error text,
    last_error_at timestamp with time zone,
    next_run_at timestamp with time zone,
    failure_count integer not null default 0,
    revision bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table channel_profile_job_run (
    id varchar(64) primary key,
    job_id varchar(64) not null references channel_profile_job(id),
    status varchar(32) not null,
    scheduled_at timestamp with time zone not null,
    started_at timestamp with time zone not null,
    job_timeout_seconds integer not null,
    finished_at timestamp with time zone,
    duration_ms bigint,
    idempotency_key varchar(128) not null,
    attempt integer not null,
    events_ingested integer not null default 0,
    next_cursor text,
    error jsonb not null default '{}'::jsonb,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index uk_channel_profile_job_profile_type
    on channel_profile_job (channel_profile_id, job_type);
create index idx_channel_profile_job_status_next_run
    on channel_profile_job (status, next_run_at);
create index idx_channel_profile_job_status_last_run
    on channel_profile_job (status, last_run_at);
create index idx_channel_profile_job_profile_status
    on channel_profile_job (channel_profile_id, status);
create unique index uk_channel_profile_job_run_idempotency_key
    on channel_profile_job_run (idempotency_key);
create index idx_channel_profile_job_run_job_scheduled
    on channel_profile_job_run (job_id, scheduled_at desc);
create index idx_channel_profile_job_run_job_started
    on channel_profile_job_run (job_id, started_at desc);
create index idx_channel_profile_job_run_status_started
    on channel_profile_job_run (status, started_at desc);
